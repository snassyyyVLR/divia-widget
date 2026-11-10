#!/usr/bin/env python3
"""Generate the compact catalog asset for the Divia widget app.

Reads static GTFS (routes/stops/trips/stop_times/calendar) and emits gzipped text:

  L|route_id|short_name|long_name|route_type|color_hex
  S|stop_id|stop_name
  P|stop_id|lat|lon                     (v1.7.0: stop coordinates, "nearby stops" picker)
  T|trip_id|route_id|direction|headsign
  D|route_id|direction|stop_id,stop_id,...   (ordered stops, canonical trip = longest)
  B|route_id|direction|label                 (most frequent headsign of that dir)
  A|stop_id|short1,short2,...                (lines serving each stop, for stop-mode UI)
  X|yyyymmdd|route_id,route_id,...           (routes on an adapted plan that day)
  M|yyyymmdd                                 (v1.5.0: last service date covered)
  Z|route_id|direction|stop_id|sec           (v1.5.0: last departure time-of-day,
                                             seconds past noon-12h — GTFS allows
                                             >86400 for after-midnight trips; the
                                             MAX over the whole calendar, i.e. the
                                             "dernier départ" the widget tags)

The X records are the disruption-day detector: the Divia GTFS defines EVERY
service date explicitly through calendar_dates.txt (a Saturday can run a
special "14_A" plan for a demonstration, a Wednesday the Sunday plan for
Armistice day...). For each dated calendar entry, the per-route trip count
is compared with the modal count of that weekday; routes deviating by
>= max(3, 10%) are flagged, so the widget can show an "horaires adaptés"
chip exactly on those days.
"""
import csv, gzip, os, sys
from collections import defaultdict, Counter
from datetime import date

GTFS = sys.argv[1] if len(sys.argv) > 1 else 'gtfs'
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), '..', 'assets', 'catalog.gz')
os.makedirs(os.path.dirname(OUT), exist_ok=True)

routes = list(csv.DictReader(open(f'{GTFS}/routes.txt')))
stops = list(csv.DictReader(open(f'{GTFS}/stops.txt')))
trips = list(csv.DictReader(open(f'{GTFS}/trips.txt')))
trip_by_id = {t['trip_id']: t for t in trips}
print(f"routes={len(routes)} stops={len(stops)} trips={len(trips)}")

# ---- pass 1: stop count per trip; stop -> routes set ----
trip_len = Counter()
stop_routes = defaultdict(set)
with open(f'{GTFS}/stop_times.txt') as f:
    for row in csv.DictReader(f):
        tid = row['trip_id']
        trip_len[tid] += 1
        t = trip_by_id.get(tid)
        if t:
            stop_routes[row['stop_id']].add(t['route_id'])

# longest trip per (route, dir)
best = {}
for tid, n in trip_len.items():
    t = trip_by_id.get(tid)
    if not t:
        continue
    key = (t['route_id'], t['direction_id'])
    if key not in best or n > best[key][0]:
        best[key] = (n, tid)

# ---- pass 2: collect ordered stop list only for canonical trips ----
want = {tid for (_, tid) in best.values()}
canon_stops = defaultdict(list)
with open(f'{GTFS}/stop_times.txt') as f:
    for row in csv.DictReader(f):
        tid = row['trip_id']
        if tid in want:
            canon_stops[tid].append((int(row['stop_sequence']), row['stop_id']))

# ---- headsign frequency per (route, dir) ----
hs_count = defaultdict(Counter)
for t in trips:
    hs_count[(t['route_id'], t['direction_id'])][t['trip_headsign']] += 1

# ---- write catalog ----
route_short = {r['route_id']: r['route_short_name'] for r in routes}
out = []
for r in routes:
    out.append(f"L|{r['route_id']}|{r['route_short_name']}|{r['route_long_name']}|{r['route_type']}|{r['route_color']}")
for s in stops:
    out.append(f"S|{s['stop_id']}|{s['stop_name']}")
# v1.7.0: stop coordinates (6 decimals ≈ 11 cm) for the "stops near me"
# picker. Old app versions ignore the unknown record kind, so the file
# stays compatible with every published version.
for s in stops:
    try:
        lat = f"{float(s['stop_lat']):.6f}"
        lon = f"{float(s['stop_lon']):.6f}"
        out.append(f"P|{s['stop_id']}|{lat}|{lon}")
    except (KeyError, ValueError):
        continue
for t in trips:
    out.append(f"T|{t['trip_id']}|{t['route_id']}|{t['direction_id']}|{t['trip_headsign']}")
for (rid, d), (n, tid) in best.items():
    lst = [sid for _, sid in sorted(canon_stops[tid])]
    out.append(f"D|{rid}|{d}|{','.join(lst)}")
for (rid, d), c in hs_count.items():
    out.append(f"B|{rid}|{d}|{c.most_common(1)[0][0]}")
for sid, rset in stop_routes.items():
    shorts = sorted(route_short.get(r, r) for r in rset)
    out.append(f"A|{sid}|{','.join(shorts)}")

# ---- adapted-day detection (X records) ----
# calendar_dates.txt is exhaustive in this feed: every real service date is
# an explicit entry (calendar.txt only carries a dummy all-year service).
svc_by_date = defaultdict(set)
for r in csv.DictReader(open(f'{GTFS}/calendar_dates.txt')):
    if r['exception_type'] == '1':
        svc_by_date[r['date']].add(r['service_id'])
    else:
        svc_by_date[r['date']].discard(r['service_id'])

trips_per_svc_route = Counter((t['service_id'], t['route_id']) for t in trips)
all_route_ids = [r['route_id'] for r in routes]

def date_route_count(d, rid):
    return sum(trips_per_svc_route.get((s, rid), 0) for s in svc_by_date[d])

date_objs = {d: date(int(d[:4]), int(d[4:6]), int(d[6:])) for d in svc_by_date}
by_weekday = defaultdict(list)
for d, obj in date_objs.items():
    by_weekday[obj.weekday()].append(d)

# Modal per-route trip count per weekday (needs >=3 dated instances).
# Value frequency matters: Saturdays alternate between two REGULAR plans
# (school-term 26_A vs holiday 21_A) whose tram counts differ — a count
# shared by >=2 dates (or equal to the modal) is a normal rhythm, not a
# disruption. Only a UNIQUE count value can flag a day as adapted.
modal = {}
value_freq = {}
for wd, dates in by_weekday.items():
    if len(dates) < 3:
        continue
    for rid in all_route_ids:
        c = Counter(date_route_count(d, rid) for d in dates)
        modal[(wd, rid)] = c.most_common(1)[0][0]
        value_freq[(wd, rid)] = c

adapted = {}
for d, obj in date_objs.items():
    wd = obj.weekday()
    for rid in all_route_ids:
        m = modal.get((wd, rid))
        freq = value_freq.get((wd, rid))
        if not m or freq is None:
            continue
        c = date_route_count(d, rid)
        if c == m or freq.get(c, 0) >= 2:
            continue           # modal value, or a value other dates share too
        if abs(c - m) >= max(3, round(m * 0.10)):
            adapted.setdefault(d, set()).add(rid)

n_x = 0
for d in sorted(adapted):
    out.append(f"X|{d}|{','.join(sorted(adapted[d]))}")
    n_x += 1
print(f"adapted days: {n_x}")
for d in sorted(adapted):
    pretty = date_objs[d].isoformat()
    print(f"  {d} ({pretty}, {['Mon','Tue','Wed','Thu','Fri','Sat','Sun'][date_objs[d].weekday()]}): "
          f"{len(adapted[d])} routes")

# ---- v1.5.0: catalog expiry (M) + last departure per stop (Z) ----
# valid_until: the last service date that actually has trips running.
used_svc = {t['service_id'] for t in trips}
service_dates = sorted(d for d, svcs in svc_by_date.items() if svcs & used_svc)
valid_until = service_dates[-1] if service_dates else max(svc_by_date)
out.append(f"M|{valid_until}")
print(f"valid until: {valid_until} ({len(service_dates)} dated services)")

# Last departure time-of-day per (route, dir, stop): the MAX over every
# trip of the whole calendar (any date). Using the max is deliberate: a
# bus within the match window of the max that is the last of ITS day gets
# the "dernier" tag; days whose service ends earlier simply never match.
def hhmmss(sec):
    return f"{sec // 3600:02d}:{(sec % 3600) // 60:02d}:{sec % 60:02d}"

last_dep = defaultdict(int)   # (route_id, dir, stop_id) -> seconds
with open(f'{GTFS}/stop_times.txt') as f:
    for row in csv.DictReader(f):
        t = trip_by_id.get(row['trip_id'])
        if not t:
            continue
        dt = row['departure_time']
        parts = dt.split(':')
        if len(parts) != 3:
            continue
        try:
            sec = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
        except ValueError:
            continue
        key = (t['route_id'], t['direction_id'], row['stop_id'])
        if sec > last_dep[key]:
            last_dep[key] = sec

for (rid, d, sid), sec in sorted(last_dep.items()):
    out.append(f"Z|{rid}|{d}|{sid}|{sec}")
print(f"last-departure records: {len(last_dep)} "
      f"(latest anywhere: {hhmmss(max(last_dep.values()))})")

data = ("\n".join(out) + "\n").encode('utf-8')
with gzip.open(OUT, 'wb', compresslevel=9) as f:
    f.write(data)
print(f"records={len(out)} raw={len(data)} gz={os.path.getsize(OUT)} -> {OUT}")
