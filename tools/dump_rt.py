#!/usr/bin/env python3
"""Dump all StopTimeEvents of the Divia RT feed with trip/route/stop/time/delay."""
import gzip, sys, time, datetime

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

def stop_time_event(buf):
    delay = None; time_ = None
    for f, w, v in fields(buf):
        if w == 0 and f == 1: delay = v
        elif w == 0 and f == 2: time_ = v
    return delay, time_

def signed(v):
    return v - (1 << 64) if v >= (1 << 63) else v

path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/divia/rt.pb"
data = gzip.decompress(open(path, "rb").read())
now = time.time()

out = []
ntrips = 0
for f, w, ent in fields(data):
    if f != 2 or w != 2: continue
    for f2, w2, tripupd in fields(ent):
        if f2 != 3 or w2 != 2: continue
        ntrips += 1
        trip_id = None; route_id = None; direction = -1; trip_sched = 0
        stus = []
        for f3, w3, v3 in fields(tripupd):
            if f3 == 1 and w3 == 2:
                for f4, w4, v4 in fields(v3):
                    if f4 == 1 and w4 == 2: trip_id = v4.decode()
                    elif f4 == 5 and w4 == 2: route_id = v4.decode()
                    elif f4 == 6 and w4 == 0: direction = v4
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
            ev = dep if dep is not None else arr
            kind = "dep" if dep is not None else "arr"
            if ev is None: continue
            d, t = stop_time_event(ev)
            out.append((trip_id, route_id, direction, stop_id, kind, t, None if d is None else signed(d)))

print("parsed %d trips, %d events; now=%s (%s)" % (
    ntrips, len(out), int(now),
    datetime.datetime.fromtimestamp(now).strftime("%H:%M:%S")))

# filter arg: stop ids to keep
keep = sys.argv[2:] if len(sys.argv) > 2 else None
rows = [r for r in out if keep is None or r[3] in keep]
rows.sort(key=lambda r: (r[5] or 0))
print("%-28s %-8s %3s %-9s %3s %-10s %8s" % ("trip_id", "route", "dir", "stop", "ev", "time", "delay"))
for trip_id, route_id, direction, stop_id, kind, t, d in rows:
    ts = datetime.datetime.fromtimestamp(t).strftime("%H:%M:%S") if t else "-"
    if t and d is not None:
        sched = datetime.datetime.fromtimestamp(t - d).strftime("%H:%M:%S")
        ts = "%s sched~%s" % (ts, sched)
    print("%-28s %-8s %3d %-9s %3s %s %+6s" % (trip_id, route_id, direction, stop_id, kind, ts, d if d is not None else "-"))
