#!/usr/bin/env python3
"""For EVERY service pattern in the GTFS, print the L5 direction-1 (Université)
evening board at Suquet quay 4-1224 — to find which pattern, if any, matches
Google's board (19:57 / 20:12 / 20:27 / 20:47 / 21:14 / 21:43 / 22:19 / 22:58).
Also: which L5 trips were actually present in the live RT feed that evening.
"""
import csv, collections, datetime

GTFS = '/tmp/gtfs'

GOOGLE = {'19:57', '20:12', '20:27', '20:47', '21:14', '21:43', '22:19', '22:58'}

# service_id -> list of dates it runs (from calendar_dates; calendar.txt only xx_0)
svc_dates = collections.defaultdict(list)
for r in csv.DictReader(open(f'{GTFS}/calendar_dates.txt')):
    svc_dates[r['service_id']].append((r['date'], r['exception_type']))
for r in csv.DictReader(open(f'{GTFS}/calendar.txt')):
    svc_dates[r['service_id']].append((r['start_date'] + '..' + r['end_date'], 'base'))

# L5 trips grouped by service
svc_trips = collections.defaultdict(list)
for r in csv.DictReader(open(f'{GTFS}/trips.txt')):
    if r['route_id'] == '4-L5':
        svc_trips[r['service_id']].append(r)

print("L5 trips per service pattern:")
for s in sorted(svc_trips, key=lambda x: (-len(svc_trips[x]), x)):
    dates = ', '.join(d for d, _ in sorted(svc_dates.get(s, []))[:6])
    print("  %-6s %3d trips   runs: %s" % (s, len(svc_trips[s]), dates))

# collect Suquet 4-1224 departure times per service (direction 1)
want = {t['trip_id']: t for s in svc_trips for t in svc_trips[s]}
per_svc = collections.defaultdict(list)
for r in csv.DictReader(open(f'{GTFS}/stop_times.txt')):
    t = want.get(r['trip_id'])
    if t and t['direction_id'] == '1' and r['stop_id'] == '4-1224':
        per_svc[t['service_id']].append(r['departure_time'])

print("\nL5 -> Universite at Suquet (4-1224), evening, per service pattern:")
print("service  dates-served            evening departures (18:00-00:30)")
for s in sorted(per_svc):
    times = sorted(t for t in per_svc[s] if t >= '18:00:00' and t <= '24:30:00')
    if not times:
        continue
    dates = ', '.join(d for d, _ in sorted(svc_dates.get(s, []))[:5])
    hhmm = [t[:5] for t in times]
    match = " <== MATCHES GOOGLE'S BOARD" if set(hhmm) & GOOGLE == GOOGLE else ""
    overlap = ""
    inter = set(hhmm) & GOOGLE
    if inter and not match:
        overlap = "   (shares %s)" % ', '.join(sorted(inter))
    print("  %-6s %-22s %s%s%s" % (s, dates, ' '.join(hhmm), match, overlap))
