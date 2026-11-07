#!/usr/bin/env python3
"""Probe the Divia GTFS-RT feed: parse FeedEntity/TripUpdate/StopTimeUpdate
 departure times and compare to real now, to detect a clock/timezone skew."""
import urllib.request, gzip, io, time, sys

URL = "https://proxy.transport.data.gouv.fr/resource/divia-dijon-gtfs-rt-trip-update"
req = urllib.request.Request(URL, headers={"User-Agent": "DiviaWidget/1.4-probe", "Accept-Encoding": "gzip"})
resp = urllib.request.urlopen(req, timeout=20)
raw = resp.read()
data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw

def varint(b, i):
    v = 0; s = 0
    while True:
        x = b[i]; i += 1
        v |= (x & 0x7f) << s
        if not x & 0x80: return v, i
        s += 7

# walk protobuf: message FeedMessage { header=1, entity=2 repeated }
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

times = []
trips = 0
now = time.time()
for f, w, ent in fields(data):
    if f != 2 or w != 2: continue          # FeedEntity
    for f2, w2, tripupd in fields(ent):
        if f2 != 3 or w2 != 2: continue    # TripUpdate (field 3)
        trips += 1
        for f3, w3, stu in fields(tripupd):
            if f3 != 2 or w3 != 2: continue  # StopTimeUpdate (field 2)
            for f4, w4, ev in fields(stu):
                if (f4 == 2 or f4 == 3) and w4 == 2:  # arrival(2) / departure(3) event
                    for f5, w5, v in fields(ev):
                        if f5 == 2 and w5 == 0:       # StopTimeEvent.time (field 2)
                            times.append(v)

print("trips:", trips, " stop-time events:", len(times))
if times:
    lo, hi = min(times), max(times)
    print("now epoch      :", int(now))
    print("min event time :", lo, " (now - min = %.1f h)" % ((now - lo) / 3600))
    print("max event time :", hi, " (max - now = %.1f h)" % ((hi - now) / 3600))
    past = sum(1 for t in times if t <= now)
    fut = sum(1 for t in times if t > now)
    print("events past:", past, " future:", fut)
    # histogram of offset vs now in hours
    from collections import Counter
    c = Counter(int((t - now) / 3600) for t in times)
    print("offset-hours histogram:", dict(sorted(c.items())))
    # what if we assume the feed is naive local time (CET, UTC+1)?
    fut2 = sum(1 for t in times if t > now - 3600)
    print("future if now-3600 (naive-local hypothesis):", fut2)
