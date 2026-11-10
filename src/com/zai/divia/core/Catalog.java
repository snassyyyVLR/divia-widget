/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * In-memory catalog of the Divia network, parsed from the gzipped line-based
 * asset produced at build time from the official GTFS.
 * Pure Java: the caller supplies the InputStream (asset on Android, file on JVM).
 *
 * Format (one record per line, fields separated by '|'):
 *   L|route_id|short_name|long_name|route_type|color_hex
 *   S|stop_id|stop_name
 *   T|trip_id|route_id|direction|headsign
 *   D|route_id|direction|stop_id,stop_id,...
 *   B|route_id|direction|direction_label
 *   A|stop_id|line_short,line_short,...
 *   X|yyyymmdd|route_id,route_id,...   (routes on an adapted/special plan that day)
 *   M|yyyymmdd                          (v1.5.0: last service date covered by this catalog)
 *   Z|route_id|direction|stop_id|sec    (v1.5.0: last departure time-of-day at that
 *                                        stop, seconds since 00:00 — GTFS allows
 *                                        >86400 for after-midnight trips; the max
 *                                        over the whole calendar)
 */
public final class Catalog {

    public static final class Route {
        public final String id;
        public final String shortName;
        public final String longName;
        public final int type;          // 0 = tram, 3 = bus
        public final String colorHex;   // without '#'
        Route(String id, String sh, String ln, int t, String c) {
            this.id = id; this.shortName = sh; this.longName = ln; this.type = t; this.colorHex = c;
        }
    }

    public static final class Trip {
        public final String routeId;
        public final int direction;
        public final String headsign;
        Trip(String r, int d, String h) { routeId = r; direction = d; headsign = h; }
    }

    public final HashMap<String, Route> routes = new HashMap<String, Route>(80);
    public final HashMap<String, String> stopNames = new HashMap<String, String>(1300);
    public final HashMap<String, Trip> trips = new HashMap<String, Trip>(31000);
    public final HashMap<String, String[]> lineStops = new HashMap<String, String[]>(160);   // "routeId|dir" -> ordered stop ids
    public final HashMap<String, String> dirLabels = new HashMap<String, String>(160);       // "routeId|dir" -> label
    public final HashMap<String, String[]> stopLines = new HashMap<String, String[]>(1300);  // stopId -> line short names

    /** yyyymmdd -> route ids running an ADAPTED plan that day (strikes, demos, holidays). */
    public final HashMap<String, String[]> adapted = new HashMap<String, String[]>(16);

    /** v1.5.0: last service date this catalog covers ("" when the record is absent). */
    public String validUntil = "";

    /** v1.5.0: (routeId|dir|stopId) -> last departure seconds-of-day. */
    private final HashMap<String, Integer> lastDep = new HashMap<String, Integer>(2100);

    /** stop NAME -> every id of that physical stop (several quays share a name). */
    private HashMap<String, ArrayList<String>> idsByName;

    private List<Route> sortedRoutes;

    public Catalog(InputStream gzipped) throws IOException {
        InputStream in = new GZIPInputStream(gzipped, 64 * 1024);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024);
        String line;
        while ((line = r.readLine()) != null) {
            if (line.length() < 3 || line.charAt(1) != '|') continue;
            char kind = line.charAt(0);
            switch (kind) {
                case 'L': {
                    String[] p = split(line, 6);
                    routes.put(p[1], new Route(p[1], p[2], p[3], Integer.parseInt(p[4]), p[5]));
                    break;
                }
                case 'S': {
                    String[] p = split(line, 3);
                    stopNames.put(p[1], p[2]);
                    break;
                }
                case 'T': {
                    String[] p = split(line, 5);
                    trips.put(p[1], new Trip(p[2], Integer.parseInt(p[3]), p[4]));
                    break;
                }
                case 'D': {
                    String[] p = split(line, 4);
                    lineStops.put(p[1] + "|" + p[2], p[3].split(","));
                    break;
                }
                case 'B': {
                    String[] p = split(line, 4);
                    dirLabels.put(p[1] + "|" + p[2], p[3]);
                    break;
                }
                case 'A': {
                    String[] p = split(line, 3);
                    stopLines.put(p[1], p[2].split(","));
                    break;
                }
                case 'X': {
                    String[] p = split(line, 3);
                    adapted.put(p[1], p[2].split(","));
                    break;
                }
                case 'M': {
                    String[] p = split(line, 2);
                    validUntil = p[1];
                    break;
                }
                case 'Z': {
                    String[] p = split(line, 5);
                    try {
                        lastDep.put(p[1] + "|" + p[2] + "|" + p[3], Integer.valueOf(p[4]));
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                }
                default: // ignore
            }
        }
        r.close();
    }

    private static String[] split(String line, int limit) {
        ArrayList<String> parts = new ArrayList<String>(limit);
        int start = 0;
        for (int i = 0; i < line.length() && parts.size() < limit - 1; i++) {
            if (line.charAt(i) == '|') {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[parts.size()]);
    }

    /** Routes ordered for display: trams first, then Lignes (L*), then numeric buses. */
    public List<Route> sortedRoutes() {
        if (sortedRoutes == null) {
            ArrayList<Route> list = new ArrayList<Route>(routes.values());
            Collections.sort(list, new Comparator<Route>() {
                public int compare(Route a, Route b) {
                    long ka = sortKey(a), kb = sortKey(b);
                    if (ka != kb) return ka < kb ? -1 : 1;
                    return a.shortName.compareTo(b.shortName);
                }
            });
            sortedRoutes = list;
        }
        return sortedRoutes;
    }

    private static long sortKey(Route r) {
        if (r.type == 0) return 0;                                  // trams T1, T2
        char c0 = r.shortName.isEmpty() ? ' ' : Character.toUpperCase(r.shortName.charAt(0));
        if (c0 == 'L') return 1;                                    // Liannes L1..L9
        if (c0 == 'P' || c0 == 'B') return 3;                      // specialty lines
        try { Integer.parseInt(r.shortName); return 2; }           // numeric buses
        catch (NumberFormatException e) { return 3; }
    }

    public String stopName(String stopId) {
        String n = stopNames.get(stopId);
        return n == null ? stopId : n;
    }

    public String dirLabel(String routeId, int direction) {
        String l = dirLabels.get(routeId + "|" + direction);
        return l == null ? "" : l;
    }

    public String[] lineStopIds(String routeId, int direction) {
        return lineStops.get(routeId + "|" + direction);
    }

    /**
     * Every id of the physical stop the given id belongs to (the GTFS splits
     * one stop into several quay ids that share the same name). Always
     * contains at least the given id itself.
     */
    public String[] siblingIds(String stopId) {
        if (idsByName == null) {
            idsByName = new HashMap<String, ArrayList<String>>(stopNames.size());
            for (Map.Entry<String, String> e : stopNames.entrySet()) {
                ArrayList<String> l = idsByName.get(e.getValue());
                if (l == null) {
                    l = new ArrayList<String>(2);
                    idsByName.put(e.getValue(), l);
                }
                l.add(e.getKey());
            }
        }
        String name = stopNames.get(stopId);
        ArrayList<String> l = name != null ? idsByName.get(name) : null;
        if (l == null || l.isEmpty()) return new String[]{stopId};
        // stable order: keep the catalog order, but the requested id first
        ArrayList<String> out = new ArrayList<String>(l.size());
        out.add(stopId);
        for (String s : l) if (!s.equals(stopId)) out.add(s);
        return out.toArray(new String[out.size()]);
    }

    /** Lines serving a stop, "T1, T2" style, or "". */
    public String stopLinesLabel(String stopId) {
        String[] ls = stopLines.get(stopId);
        if (ls == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : ls) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** Lines serving ANY quay of the physical stop, de-duplicated, or "". */
    public String stopLinesLabelAny(String stopId) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        for (String id : siblingIds(stopId)) {
            String[] ls = stopLines.get(id);
            if (ls != null) for (String s : ls) set.add(s);
        }
        if (set.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ adapted days

    /** v1.5.0: the last departure at a stop for a line+direction, in seconds
     *  of day (GTFS convention — may exceed 86400 for after-midnight trips);
     *  -1 when unknown (catalog without Z records / foreign stop id). */
    public int lastDepartureSec(String routeId, int direction, String stopId) {
        Integer v = lastDep.get(routeId + "|" + direction + "|" + stopId);
        return v != null ? v.intValue() : -1;
    }

    /** v1.5.0: same, taking the max over every quay of the physical stop —
     *  used in whole-stop mode where the departure's exact quay is unknown. */
    public int lastDepartureSecAny(String routeId, int direction, String stopId) {
        int best = lastDepartureSec(routeId, direction, stopId);
        if (best >= 0) {
            // A sibling quay may run later than the requested one.
            for (String sib : siblingIds(stopId)) {
                int v = lastDepartureSec(routeId, direction, sib);
                if (v > best) best = v;
            }
        }
        return best;
    }

    /** v1.5.0: days between today (local) and a yyyymmdd string; a large
     *  number when the date is absent or unparsable, negative once past.
     *  Pure Java so the JVM harness pins the math. */
    public static int daysLeft(String ymd, long nowMs) {
        if (ymd == null || ymd.length() != 8) return Integer.MAX_VALUE;
        try {
            int y = Integer.parseInt(ymd.substring(0, 4));
            int mo = Integer.parseInt(ymd.substring(4, 6));
            int d = Integer.parseInt(ymd.substring(6, 8));
            java.util.Calendar target = java.util.Calendar.getInstance();
            target.clear();
            target.set(y, mo - 1, d);
            java.util.Calendar today = java.util.Calendar.getInstance();
            today.setTimeInMillis(nowMs);
            java.util.Calendar t0 = java.util.Calendar.getInstance();
            t0.clear();
            t0.set(today.get(java.util.Calendar.YEAR),
                    today.get(java.util.Calendar.MONTH),
                    today.get(java.util.Calendar.DAY_OF_MONTH));
            long diff = target.getTimeInMillis() - t0.getTimeInMillis();
            return (int) Math.round(diff / 86400000.0);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** True when the route runs an adapted (strike/demo/holiday) plan that day. */
    public boolean isAdapted(String ymd, String routeId) {
        String[] rs = adapted.get(ymd);
        if (rs == null) return false;
        for (String r : rs) if (r.equals(routeId)) return true;
        return false;
    }

    /** True when ANY line serving the stop (any quay) is adapted that day. */
    public boolean stopAdapted(String ymd, String stopId) {
        String[] rs = adapted.get(ymd);
        if (rs == null || rs.length == 0) return false;
        String lines = stopLinesLabelAny(stopId);
        if (lines.isEmpty()) return false;
        // exact token match ("L5" must not match "L51" inside the joined list)
        String[] tokens = lines.split(", ");
        for (String rid : rs) {
            Route r = routes.get(rid);
            if (r == null) continue;
            for (String t : tokens) if (t.equals(r.shortName)) return true;
        }
        return false;
    }
}
