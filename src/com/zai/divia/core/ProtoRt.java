/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia.core;

/**
 * Minimal hand-rolled parser for GTFS-Realtime trip-update feeds.
 * Pure Java (no dependencies) so it can be unit-tested on a desktop JVM.
 *
 * Only the subset of the protobuf schema we need is decoded:
 *   FeedMessage{ header=1, entity=2 }
 *     FeedEntity{ id=1, trip_update=3 }
 *       TripUpdate{ trip=1 (TripDescriptor), stop_time_update=2 }
 *         TripDescriptor{ trip_id=1, route_id=5, direction_id=6, schedule_relationship=4 }
 *         StopTimeUpdate{ arrival=2 (StopTimeEvent), departure=3, stop_id=4, schedule_relationship=5 }
 *           StopTimeEvent{ delay=1, time=2 }
 */
public final class ProtoRt {

    /** One predicted departure event at one stop. */
    public static final class Event {
        public final String tripId;
        public final String routeId;
        public final int direction;      // -1 when absent
        public final String stopId;
        public final long timeSec;       // epoch seconds (departure preferred, arrival fallback)
        public final int schedRel;       // StopTimeUpdate schedule_relationship
        public final int delaySec;       // delay vs timetable in s; Integer.MIN_VALUE when absent

        Event(String tripId, String routeId, int direction, String stopId, long timeSec,
              int schedRel, int delaySec) {
            this.tripId = tripId;
            this.routeId = routeId;
            this.direction = direction;
            this.stopId = stopId;
            this.timeSec = timeSec;
            this.schedRel = schedRel;
            this.delaySec = delaySec;
        }
    }

    private ProtoRt() {}

    /** Parse a whole FeedMessage; returns every stop event found. */
    public static java.util.List<Event> parse(byte[] pb) {
        java.util.ArrayList<Event> out = new java.util.ArrayList<Event>(512);
        Cursor c = new Cursor(pb, 0, pb.length);
        while (c.hasMore()) {
            int key = c.readKey();
            int field = key >>> 3;
            int wire = key & 7;
            if (wire == 2) {
                int len = (int) c.readVarint();
                if (field == 2) parseEntity(c.readBytes(len), out);
                else c.skip(len);
            } else {
                c.skipValue(wire);
            }
        }
        return out;
    }

    private static void parseEntity(byte[] ent, java.util.List<Event> out) {
        Cursor c = new Cursor(ent, 0, ent.length);
        while (c.hasMore()) {
            int key = c.readKey();
            int field = key >>> 3;
            int wire = key & 7;
            if (wire == 2) {
                int len = (int) c.readVarint();
                if (field == 3) parseTripUpdate(c.readBytes(len), out);
                else c.skip(len);
            } else {
                c.skipValue(wire);
            }
        }
    }

    private static void parseTripUpdate(byte[] tu, java.util.List<Event> out) {
        String tripId = null, routeId = null;
        int direction = -1;
        int tripSched = 0;
        java.util.ArrayList<byte[]> stus = new java.util.ArrayList<byte[]>(32);

        Cursor c = new Cursor(tu, 0, tu.length);
        while (c.hasMore()) {
            int key = c.readKey();
            int field = key >>> 3;
            int wire = key & 7;
            if (wire == 2) {
                int len = (int) c.readVarint();
                if (field == 1) {
                    byte[] td = c.readBytes(len);
                    Cursor tc = new Cursor(td, 0, td.length);
                    while (tc.hasMore()) {
                        int k2 = tc.readKey();
                        int f2 = k2 >>> 3;
                        int w2 = k2 & 7;
                        if (w2 == 2) {
                            int l2 = (int) tc.readVarint();
                            if (f2 == 1) tripId = new String(tc.readBytes(l2), java.nio.charset.StandardCharsets.UTF_8);
                            else if (f2 == 5) routeId = new String(tc.readBytes(l2), java.nio.charset.StandardCharsets.UTF_8);
                            else tc.skip(l2);
                        } else if (w2 == 0) {
                            long v = tc.readVarint();
                            if (f2 == 6) direction = (int) v;
                            else if (f2 == 4) tripSched = (int) v;
                        } else {
                            tc.skipValue(w2);
                        }
                    }
                } else if (field == 2) {
                    stus.add(c.readBytes(len));
                } else {
                    c.skip(len);
                }
            } else {
                c.skipValue(wire);
            }
        }

        if (tripSched == 3 /* CANCELED */) return;

        for (byte[] stu : stus) {
            String stopId = null;
            long time = 0;
            boolean haveTime = false;
            int schedRel = 0;
            long arrival = 0, departure = 0;
            boolean haveArr = false, haveDep = false;
            int arrDelay = Integer.MIN_VALUE, depDelay = Integer.MIN_VALUE;

            Cursor sc = new Cursor(stu, 0, stu.length);
            while (sc.hasMore()) {
                int key = sc.readKey();
                int field = key >>> 3;
                int wire = key & 7;
                if (wire == 2) {
                    int len = (int) sc.readVarint();
                    if (field == 4) {
                        stopId = new String(sc.readBytes(len), java.nio.charset.StandardCharsets.UTF_8);
                    } else if (field == 2 || field == 3) {
                        byte[] ev = sc.readBytes(len);
                        long t = 0;
                        boolean ht = false;
                        int d = Integer.MIN_VALUE;
                        Cursor ec = new Cursor(ev, 0, ev.length);
                        while (ec.hasMore()) {
                            int k3 = ec.readKey();
                            int f3 = k3 >>> 3;
                            int w3 = k3 & 7;
                            if (w3 == 0) {
                                long v = ec.readVarint();
                                if (f3 == 2) { t = v; ht = true; }
                                else if (f3 == 1) { d = (int) v; }   // delay (s), may be negative
                            } else if (w3 == 2) {
                                int l3 = (int) ec.readVarint();
                                ec.skip(l3);
                            } else {
                                ec.skipValue(w3);
                            }
                        }
                        if (field == 2) { arrival = t; haveArr = ht; arrDelay = d; }
                        else { departure = t; haveDep = ht; depDelay = d; }
                    } else {
                        sc.skip(len);
                    }
                } else if (wire == 0) {
                    long v = sc.readVarint();
                    if (field == 5) schedRel = (int) v;
                } else {
                    sc.skipValue(wire);
                }
            }

            if (schedRel == 1 /* SKIPPED */ || stopId == null) continue;
            // The delay travels with the same StopTimeEvent as the time we
            // display: departure's delay when we show the departure time,
            // arrival's otherwise.
            int delay = Integer.MIN_VALUE;
            if (haveDep) { time = departure; haveTime = true; delay = depDelay; }
            else if (haveArr) { time = arrival; haveTime = true; delay = arrDelay; }
            if (!haveTime || time <= 0) continue;
            out.add(new Event(tripId, routeId, direction, stopId, time, schedRel, delay));
        }
    }

    /** Byte cursor over one protobuf message. */
    static final class Cursor {
        final byte[] buf;
        int pos;
        final int end;

        Cursor(byte[] buf, int start, int end) { this.buf = buf; this.pos = start; this.end = end; }

        boolean hasMore() { return pos < end; }

        int readKey() { return (int) readVarint(); }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (true) {
                if (pos >= end) throw new IllegalArgumentException("truncated varint");
                int b = buf[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift >= 64) throw new IllegalArgumentException("varint too long");
            }
        }

        byte[] readBytes(int len) {
            if (pos + len > end) throw new IllegalArgumentException("truncated field");
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        void skip(int len) {
            if (pos + len > end) throw new IllegalArgumentException("truncated skip");
            pos += len;
        }

        void skipValue(int wire) {
            switch (wire) {
                case 0: readVarint(); break;
                case 1: skip(8); break;
                case 5: skip(4); break;
                default: throw new IllegalArgumentException("bad wire type " + wire);
            }
        }
    }
}
