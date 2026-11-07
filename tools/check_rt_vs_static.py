#!/usr/bin/env python3
"""Full-feed consistency check: for every RT event carrying a delay, compare
its implied scheduled time (time - delay) with the official static GTFS
stop_time for the same (trip_id, stop_id).

Paris is UTC+1 in November; static GTFS times are local (with >24h for
after-midnight legs). Tolerance buckets: exact, <=60s, <=120s, worse, missing.
"""
import csv, gzip, collections, datetime

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

def ste(buf):
    d = None; t = None
    for f, w, v in fields(buf):
        if w == 0 and f == 1: d = signed(v)
        elif w == 0 and f == 2: t = v
    return d, t

# ---- parse RT feed ----
data = gzip.decompress(open(RT, 'rb').read())
events = []
for f, w, ent in fields(data):
    if f != 2 or w != 2: continue
    for f2, w2, tu in fields(ent):
        if f2 != 3 or w2 != 2: continue
        trip_id = None; trip_sched = 0; stus = []
        for f3, w3, v3 in fields(tu):
            if f3 == 1 and w3 == 2:
                for f4, w4, v4 in fields(v3):
                    if f4 == 1 and w4 == 2: trip_id = v4.decode()
                    elif f4 == 4 and w4 == 0: trip_sched = v4
            elif f3 == 2 and w3 == 2:
                stus.append(v3)
        if trip_sched == 3: continue
        for stu in stus:
            stop_id = None; dep = None; arr = None; srel = 0
            for f5, w5, v5 in fields(stu):
                if f5 == 4 and w5 == 2: stop_id = v5.decode()
                elif f5 == 3 and w5 == 2: dep = v5
                elif f5 == 2 and w5 == 2: arr = v5
                elif f5 == 5 and w5 == 0: srel = v5
            if stop_id is None or srel == 1: continue
            ev, kind = (dep, 'dep') if dep is not None else (arr, 'arr')
            if ev is None: continue
            d, t = ste(ev)
            if t and d is not None:
                events.append((trip_id, stop_id, kind, t, d))

print("RT events with time+delay:", len(events))

# ---- static GTFS stop_times index for these trips ----
want_trips = {e[0] for e in events}
st_index = {}
with open(f'{GTFS}/stop_times.txt') as fh:
    for r in csv.DictReader(fh):
        tid = r['trip_id']
        if tid in want_trips:
            st_index[(tid, r['stop_id'])] = (r['arrival_time'], r['departure_time'])

def to_sec(t):
    h, m, s = t.split(':')
    return int(h) * 3600 + int(m) * 60 + int(s)

buckets = collections.Counter()
worst = []
missing_trips = set()
for trip_id, stop_id, kind, t, d in events:
    row = st_index.get((trip_id, stop_id))
    if row is None:
        buckets['no static row'] += 1
        missing_trips.add(trip_id)
        continue
    static_sec = to_sec(row[0] if kind == 'arr' else row[1])
    # implied scheduled epoch -> Paris local seconds of day (UTC+1)
    local_sec = int((t - d + 3600) % 86400)
    diff = (local_sec - static_sec) % 86400
    if diff > 43200: diff -= 86400
    if diff == 0: buckets['exact (0s)'] += 1
    elif abs(diff) <= 30: buckets['<=30s'] += 1
    elif abs(diff) <= 60: buckets['<=60s'] += 1
    elif abs(diff) <= 120: buckets['<=120s'] += 1
    else:
        buckets['>120s MISMATCH'] += 1
        worst.append((trip_id, stop_id, kind, d, diff))

total = sum(buckets.values())
print("checked against static GTFS:", total)
for k in ['exact (0s)', '<=30s', '<=60s', '<=120s', '>120s MISMATCH', 'no static row']:
    if buckets[k]:
        print("  %-16s %5d  (%.1f%%)" % (k, buckets[k], 100.0 * buckets[k] / total))
if missing_trips:
    print("trips with no static rows:", sorted(missing_trips)[:8])
for trip_id, stop_id, kind, d, diff in worst[:10]:
    print("  MISMATCH trip=%s stop=%s %s delay=%ds implied-vs-static=%+ds"
          % (trip_id, stop_id, kind, d, diff))
