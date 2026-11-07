#!/usr/bin/env python3
"""Which L5 trips existed in the live RT feed (fetched ~20:15 Paris on 2026-11-07)?
Cross-reference each with its service pattern and its static schedule."""
import csv, gzip, collections

GTFS = '/tmp/gtfs'
RT = '/tmp/divia/rt.pb'

def varint(b, i):
    v = 0; s = 0
    while True:
        x = b[i]; i += 1
        v |= (x & 0x7f) << s
        if not x & 0x80: return v, i
        s += 7

def fields(b):
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        f, w = key >> 3, key & 7
        if w == 0:
            v, i = varint(b, i); yield f, 0, v
        elif w == 2:
            ln, i = varint(b, i); yield f, 2, b[i:i+ln]; i += ln
        else:
            raise ValueError("wire %d" % w)

def signed(v):
    return v - (1 << 64) if v >= (1 << 63) else v

data = gzip.decompress(open(RT, 'rb').read())

# L5 TripUpdates in the feed: trip_id -> earliest predicted stop time + all events
feed_trips = {}
for f, w, ent in fields(data):
    if f != 2 or w != 2: continue
    for f2, w2, tu in fields(ent):
        if f2 != 3 or w2 != 2: continue
        trip_id = None; route = None; tsc = 0; evs = []
        for f3, w3, v3 in fields(tu):
            if f3 == 1 and w3 == 2:
                for f4, w4, v4 in fields(v3):
                    if f4 == 1 and w4 == 2: trip_id = v4.decode()
                    elif f4 == 5 and w4 == 2: route = v4.decode()
                    elif f4 == 4 and w4 == 0: tsc = v4
            elif f3 == 2 and w3 == 2:
                stop_id = None; t = None; d = None
                for f5, w5, v5 in fields(v3):
                    if f5 == 4 and w5 == 2: stop_id = v5.decode()
                    elif f5 in (2, 3) and w5 == 2:
                        for f6, w6, v6 in fields(v5):
                            if f6 == 2 and w6 == 0: t = v6
                            elif f6 == 1 and w6 == 0: d = signed(v6)
                if stop_id and t:
                    evs.append((t, stop_id, d))
        if route == '4-L5':
            feed_trips[trip_id] = evs

# static info
trip_svc = {}
for r in csv.DictReader(open(f'{GTFS}/trips.txt')):
    if r['route_id'] == '4-L5':
        trip_svc[r['trip_id']] = (r['service_id'], r['direction_id'])

svc_dates = collections.defaultdict(list)
for r in csv.DictReader(open(f'{GTFS}/calendar_dates.txt')):
    svc_dates[r['service_id']].append(r['date'])

first_static = {}
for r in csv.DictReader(open(f'{GTFS}/stop_times.txt')):
    if r['trip_id'] in trip_svc and r['trip_id'] not in first_static:
        first_static[r['trip_id']] = r['departure_time']

print("L5 trips present in the live feed (2026-11-07 ~20:15 Paris):")
for tid, evs in sorted(feed_trips.items(), key=lambda kv: min(e[0] for e in kv[1])):
    svc, dirn = trip_svc.get(tid, ('?', '?'))
    evs.sort()
    t0, stop0, d0 = evs[0]
    import datetime
    t0s = datetime.datetime.fromtimestamp(t0).strftime('%H:%M:%S')
    sched0 = datetime.datetime.fromtimestamp(t0 - (d0 or 0)).strftime('%H:%M:%S')
    dates = ', '.join(sorted(svc_dates.get(svc, ['(calendar.txt)'])))
    print("  %-24s svc=%-4s dir=%s  %d stops  first: %s (sched %s, %s%ds)"
          % (tid, svc, dirn, len(evs), t0s, sched0,
             '+' if (d0 or 0) >= 0 else '-', abs(d0 or 0)))
    print("       static first stop: %-8s service runs: %s"
          % (first_static.get(tid, '?'), dates))

print("\nTrip-id naming per service (sanity):")
for svc in ['14_A', '26_A', '21_A', '22_B']:
    ids = [t for t, (s, _) in trip_svc.items() if s == svc][:3]
    print("  %-4s e.g. %s" % (svc, ids))
