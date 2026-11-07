#!/usr/bin/env python3
"""Exploratory decoder for Divia GTFS-RT trip updates.
Hand-rolled protobuf wire format parsing - this validates the approach
that will be ported to Java in the Android app."""

def read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7

def parse_fields(buf):
    """Generic protobuf parser: yields (field_number, wire_type, value)."""
    pos = 0
    n = len(buf)
    while pos < n:
        key, pos = read_varint(buf, pos)
        field, wire = key >> 3, key & 7
        if wire == 0:  # varint
            val, pos = read_varint(buf, pos)
        elif wire == 2:  # length-delimited
            length, pos = read_varint(buf, pos)
            val = buf[pos:pos+length]
            pos += length
        elif wire == 5:  # 32-bit
            val = buf[pos:pos+4]; pos += 4
        elif wire == 1:  # 64-bit
            val = buf[pos:pos+8]; pos += 8
        else:
            raise ValueError(f"bad wire type {wire}")
        yield field, wire, val

def feed_entities(pb):
    for field, wire, val in parse_fields(pb):
        if field == 2:  # FeedMessage.entity
            yield val

def entity_trip_update(ent):
    for field, wire, val in parse_fields(ent):
        if field == 3:  # FeedEntity.trip_update
            return val
    return None

def trip_update_info(tu):
    """Returns (trip_desc, stop_time_updates list)."""
    trip_desc = {}
    stus = []
    for field, wire, val in parse_fields(tu):
        if field == 1:  # TripUpdate.trip (TripDescriptor)
            for f2, w2, v2 in parse_fields(val):
                if f2 == 1: trip_desc['trip_id'] = v2.decode()
                elif f2 == 5: trip_desc['route_id'] = v2.decode()
                elif f2 == 6: trip_desc['direction_id'] = v2
                elif f2 == 2: trip_desc['start_time'] = v2.decode()
                elif f2 == 3: trip_desc['start_date'] = v2.decode()
        elif field == 2:  # TripUpdate.stop_time_update (repeated)
            stu = {'stop_id': None, 'arrival': None, 'departure': None, 'stop_sequence': None, 'sched': None}
            for f2, w2, v2 in parse_fields(val):
                if f2 == 4: stu['stop_id'] = v2.decode()
                elif f2 == 1: stu['stop_sequence'] = v2
                elif f2 == 5: stu['sched'] = v2
                elif f2 in (2, 3):  # arrival / departure StopTimeEvent
                    ev = {}
                    for f3, w3, v3 in parse_fields(v2):
                        if f3 == 1: ev['delay'] = v3 if v3 < 2**63 else v3 - 2**64
                        elif f3 == 2: ev['time'] = v3
                    stu['arrival' if f2 == 2 else 'departure'] = ev
            stus.append(stu)
    return trip_desc, stus

if __name__ == '__main__':
    import sys, csv, datetime
    pb = open('/tmp/divia_tu.pb', 'rb').read()
    print(f"feed size: {len(pb)} bytes")

    # GTFS lookups
    routes = {r['route_id']: r for r in csv.DictReader(open('/tmp/gtfs/routes.txt'))}
    stops = {s['stop_id']: s for s in csv.DictReader(open('/tmp/gtfs/stops.txt'))}
    trips = {t['trip_id']: t for t in csv.DictReader(open('/tmp/gtfs/trips.txt'))}

    n_ent = 0
    n_tu = 0
    total_stu = 0
    route_ids_seen = {}
    sample = []
    for ent in feed_entities(pb):
        n_ent += 1
        tu = entity_trip_update(ent)
        if tu is None:
            continue
        n_tu += 1
        td, stus = trip_update_info(tu)
        total_stu += len(stus)
        rid = td.get('route_id', '?')
        route_ids_seen[rid] = route_ids_seen.get(rid, 0) + 1
        for stu in stus:
            dep = stu['departure'] or stu['arrival']
            if dep and stu['stop_id'] and len(sample) < 12:
                r = routes.get(rid, {})
                s = stops.get(stu['stop_id'], {})
                t = trips.get(td.get('trip_id', ''), {})
                when = datetime.datetime.fromtimestamp(dep.get('time', 0))
                sample.append(
                    f"  line {r.get('route_short_name','?'):>3} ({rid}) dir {td.get('direction_id')} "
                    f"-> {t.get('trip_headsign','?'):<28} stop {s.get('stop_name','?')} ({stu['stop_id']}) "
                    f"seq {stu['stop_sequence']} at {when:%H:%M:%S} sched={stu['sched']}"
                )

    print(f"entities: {n_ent}, trip_updates: {n_tu}, stop_time_updates: {total_stu}")
    print("\nroutes seen in feed (count):")
    for rid, c in sorted(route_ids_seen.items(), key=lambda x: -x[1])[:15]:
        r = routes.get(rid, {})
        print(f"  {rid:<8} {r.get('route_short_name','?'):>4} x{c}")
    print("\nsample departures:")
    print("\n".join(sample))
