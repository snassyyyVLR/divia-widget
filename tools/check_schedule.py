#!/usr/bin/env python3
"""Cross-check: static GTFS schedule vs RT feed implied schedule vs Google's claim.

Service date: 2026-11-07 (the evening of the screenshots), Dijon local (CET, UTC+1).
"""
import csv, datetime, collections

GTFS = '/tmp/gtfs'

# ---------- calendar: which service_ids run on 2026-11-07 ? ----------
d = datetime.date(2026, 11, 7)
print("2026-11-07 is a", d.strftime("%A"))

active = set()
dow = ['monday','tuesday','wednesday','thursday','friday','saturday','sunday'][d.weekday()]
with open(f'{GTFS}/calendar.txt') as f:
    for r in csv.DictReader(f):
        if r[dow] == '1':
            if datetime.date.fromisoformat(r['start_date']) <= d <= datetime.date.fromisoformat(r['end_date']):
                active.add(r['service_id'])
with open(f'{GTFS}/calendar_dates.txt') as f:
    for r in csv.DictReader(f):
        if r['date'] == '20261107':
            if r['exception_type'] == '1':
                active.add(r['service_id'])
            else:
                active.discard(r['service_id'])
print("active service_ids:", sorted(active))

# ---------- trips on route 4-L5 running that day ----------
route = '4-L5'
trips = {}
with open(f'{GTFS}/trips.txt') as f:
    for r in csv.DictReader(f):
        if r['service_id'] in active and r['route_id'] == route:
            trips[r['trip_id']] = r
print("L5 trips that day:", len(trips))

# ---------- stop_times at the Suquet quays ----------
SUQUET = {'4-1224', '4-1228'}
# find which quay serves which direction
rows = []
with open(f'{GTFS}/stop_times.txt') as f:
    for r in csv.DictReader(f):
        tid = r['trip_id']
        if tid not in trips:
            continue
        if r['stop_id'] in SUQUET:
            rows.append((tid, r['stop_id'], r['departure_time'], int(r['stop_sequence'])))

def to_sec(t):
    h, m, s = t.split(':')
    return int(h) * 3600 + int(m) * 60 + int(s)

# direction of each trip
by_dir = collections.defaultdict(list)
for tid, stop_id, dep, seq in rows:
    by_dir[trips[tid]['direction_id']].append((to_sec(dep), tid, stop_id, seq))

for dirn in sorted(by_dir):
    print(f"\n=== L5 direction {dirn} at Suquet (scheduled, static GTFS) ===")
    for dep, tid, stop_id, seq in sorted(by_dir[dirn]):
        head = trips[tid]['trip_headsign']
        t = datetime.timedelta(seconds=dep)
        print("  %s  %-22s quay=%-8s seq=%-3d %s" % (str(t), tid, stop_id, seq, head))
