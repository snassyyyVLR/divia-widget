/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia.core;

/** A display-ready upcoming departure. */
public final class Departure implements Comparable<Departure> {
    public final String routeId;
    public final String lineShort;   // "T1", "L3", "18"
    public final String headsign;    // destination
    public final int direction;
    public final long timeSec;       // epoch seconds
    public final int delaySec;       // delay vs timetable in s; Integer.MIN_VALUE when unknown

    public Departure(String routeId, String lineShort, String headsign, int direction,
                     long timeSec, int delaySec) {
        this.routeId = routeId;
        this.lineShort = lineShort;
        this.headsign = headsign;
        this.direction = direction;
        this.timeSec = timeSec;
        this.delaySec = delaySec;
    }

    public int compareTo(Departure other) {
        long d = timeSec - other.timeSec;
        return d < 0 ? -1 : (d > 0 ? 1 : 0);
    }
}
