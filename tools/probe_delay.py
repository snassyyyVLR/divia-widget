#!/usr/bin/env python3
"""Probe the Divia GTFS-RT trip-update feed.

Question to answer: does the feed carry StopTimeEvent.delay (deviation from
the timetable, in seconds)? If yes, an explicit "late / early" indicator is
implementable; if no, only the absolute predicted time is available.

Schema (mirrors ProtoRt.java):
  FeedMessage{ entity=2 }
    FeedEntity{ trip_update=3 }
      TripUpdate{ trip=1, stop_time_update=2 }
        TripDescriptor{ trip_id=1, route_id=5, direction_id=6, sched_rel=4 }
        StopTimeUpdate{ arrival=2, departure=3, stop_id=4, sched_rel=5 }
          StopTimeEvent{ delay=1, time=2 }
"""
import sys


class Cursor:
    def __init__(self, buf):
        self.buf = buf
        self.pos = 0

    def has_more(self):
        return self.pos < len(self.buf)

    def read_varint(self):
        result = 0
        shift = 0
        while True:
            b = self.buf[self.pos]
            self.pos += 1
            result |= (b & 0x7F) << shift
            if not (b & 0x80):
                # interpret as signed 64-bit (delay can be negative)
                if result >= 1 << 63:
                    result -= 1 << 64
                return result
            shift += 7

    def read_key(self):
        k = self.read_varint()
        return k >> 3, k & 7

    def read_bytes(self, n):
        out = self.buf[self.pos:self.pos + n]
        self.pos += n
        return out

    def skip(self, wire):
        if wire == 0:
            self.read_varint()
        elif wire == 1:
            self.pos += 8
        elif wire == 5:
            self.pos += 4
        else:
            raise ValueError("bad wire %d" % wire)


def fields(buf):
    """Top-level (field, wire, value) triples of one message."""
    c = Cursor(buf)
    out = []
    while c.has_more():
        f, w = c.read_key()
        if w == 2:
            n = c.read_varint()
            out.append((f, w, c.read_bytes(n)))
        elif w == 0:
            out.append((f, w, c.read_varint()))
        else:
            c.skip(w)
    return out


def stop_time_event(buf):
    """(delay_or_None, time_or_None) of a StopTimeEvent."""
    delay = None
    time_ = None
    for f, w, v in fields(buf):
        if w == 0 and f == 1:
            delay = v
        elif w == 0 and f == 2:
            time_ = v
    return delay, time_


def main(path):
    data = open(path, "rb").read()
    total = 0
    with_time = 0
    with_delay = 0
    delay_neg = 0
    delay_pos = 0
    delays = []
    trips = 0
    canceled = 0
    samples = []

    for f, w, v in fields(data):
        if f != 2 or w != 2:
            continue
        for f2, w2, v2 in fields(v):            # FeedEntity
            if f2 != 3 or w2 != 2:
                continue
            trips += 1
            trip_desc = None
            stus = []
            for f3, w3, v3 in fields(v2):        # TripUpdate
                if f3 == 1 and w3 == 2:
                    trip_desc = v3
                elif f3 == 2 and w3 == 2:
                    stus.append(v3)
            # canceled trips (TripDescriptor.schedule_relationship == 3)
            td_fields = fields(trip_desc) if trip_desc is not None else []
            if any(f4 == 4 and w4 == 0 and v4 == 3 for f4, w4, v4 in td_fields):
                canceled += 1
                continue
            route = next((v4 for f4, w4, v4 in td_fields if f4 == 5 and w4 == 2), b"").decode()
            for stu in stus:
                stop_id = None
                dep = None
                arr = None
                for f5, w5, v5 in fields(stu):   # StopTimeUpdate
                    if f5 == 4 and w5 == 2:
                        stop_id = v5.decode(errors="replace")
                    elif f5 == 3 and w5 == 2:
                        dep = v5
                    elif f5 == 2 and w5 == 2:
                        arr = v5
                ev = dep if dep is not None else arr
                if ev is None:
                    continue
                total += 1
                d, t = stop_time_event(ev)
                if t is not None:
                    with_time += 1
                if d is not None:
                    with_delay += 1
                    delays.append(d)
                    if d < 0:
                        delay_neg += 1
                    elif d > 0:
                        delay_pos += 1
                    if len(samples) < 8 and abs(d) >= 60:
                        samples.append((route, stop_id, d, t))

    print("trips seen:            %d (canceled: %d)" % (trips, canceled))
    print("stop events:           %d" % total)
    print("  with absolute time:  %d" % with_time)
    print("  with delay field:    %d" % with_delay)
    if delays:
        delays.sort()
        print("delay min/med/max:     %s / %s / %s s"
              % (delays[0], delays[len(delays) // 2], delays[-1]))
        print("delay >0: %d   <0: %d   ==0: %d"
              % (delay_pos, delay_neg, with_delay - delay_pos - delay_neg))
    print()
    if samples:
        print("samples with |delay| >= 60 s:")
        for route, stop_id, d, t in samples:
            print("  route %-4s stop %-12s delay %+5d s" % (route, stop_id, d))
    else:
        print("no sample with |delay| >= 60 s in this snapshot")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/divia/trip-updates.pb")
