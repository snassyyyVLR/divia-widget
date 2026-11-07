#!/usr/bin/env python3
"""Generate the compact catalog asset for the Divia widget app.

Reads static GTFS (routes/stops/trips/stop_times) and emits gzipped text:

  L|route_id|short_name|long_name|route_type|color_hex
  S|stop_id|stop_name
  T|trip_id|route_id|direction|headsign
  D|route_id|direction|stop_id,stop_id,...   (ordered stops, canonical trip = longest)
  B|route_id|direction|label                 (most frequent headsign of that dir)
  A|stop_id|short1,short2,...                (lines serving each stop, for stop-mode UI)

Usage:
  python3 tools/gen_catalog.py <gtfs-dir> [output.gz]

  <gtfs-dir>  a directory with the extracted Divia GTFS (routes.txt, stops.txt,
              trips.txt, stop_times.txt, calendar.txt...). Download the current
              zip from https://transport.data.gouv.fr (dataset
              "gtfs-diviamobilites") and unzip it.
  [output.gz] where to write the catalog; default: assets/catalog.gz of this
              repository (the file the app bundles).

Re-run this whenever the bundled catalog expires (the About screen of the app
shows the validity date; the 2026 edition runs until 2027-01-03), then rebuild
the APK with build.sh.
"""
import csv, gzip, os, sys

GTFS = sys.argv[1] if len(sys.argv) > 1 else 'gtfs'
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), '..', 'assets', 'catalog.gz')

for f in ('routes.txt', 'stops.txt', 'trips.txt', 'stop_times.txt'):
    p = os.path.join(GTFS, f)
    if not os.path.isfile(p):
        sys.exit("missing %s — pass the extracted GTFS directory as argument" % p)
os.makedirs(os.path.dirname(OUT), exist_ok=True)

routes = list(csv.DictReader(open(f'{GTFS}/routes.txt')))
stops = list(csv.DictReader(open(f'{GTFS}/stops.txt')))
trips = list(csv.DictReader(open(f'{GTFS}/trips.txt')))
trip_by_id = {t['trip_id']: t for t in trips}
print(f"routes={len(routes)} stops={len(stops)} trips={len(trips)}")

# ---- pass 1: stop count per trip; stop -> routes set ----
from collections import defaultdict, Counter
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

data = ("\n".join(out) + "\n").encode('utf-8')
with gzip.open(OUT, 'wb', compresslevel=9) as f:
    f.write(data)
print(f"records={len(out)} raw={len(data)} gz={os.path.getsize(OUT)} -> {OUT}")
