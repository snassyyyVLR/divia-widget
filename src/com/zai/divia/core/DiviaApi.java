/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import com.zai.divia.core.ProtoRt.Event;

/**
 * Fetches the Divia GTFS-RT trip-update feed and turns it into next
 * departures for a given stop (whole-stop mode) or stop+line+direction
 * (line mode). Pure Java.
 */
public final class DiviaApi {

    public static final String FEED_URL =
            "https://proxy.transport.data.gouv.fr/resource/divia-dijon-gtfs-rt-trip-update";

    private DiviaApi() {}

    /** HTTP GET of the protobuf feed. Handles gzip Content-Encoding.
     *  The connect timeout is capped so a dead network can never push the
     *  total past the ~10 s goAsync() window of a widget broadcast. */
    public static byte[] fetch(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(Math.min(4000, timeoutMs));
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "DiviaWidget/1.4 (Android)");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            InputStream raw = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                raw = new java.util.zip.GZIPInputStream(raw, 8192);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(96 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = raw.read(buf)) > 0) out.write(buf, 0, n);
            raw.close();
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Next departures at a stop.
     *
     * @param feed      raw protobuf bytes of the trip-update feed
     * @param cat       network catalog (for line names / headsigns)
     * @param stopId    GTFS stop id, e.g. "4-1462"
     * @param routeId   route filter or null for whole-stop mode
     * @param direction direction filter or -1 to ignore
     * @param count     max number of departures to return
     * @param nowSec    current epoch seconds
     */
    public static List<Departure> nextDepartures(byte[] feed, Catalog cat, String stopId,
                                                 String routeId, int direction, int count, long nowSec) {
        List<Event> events = ProtoRt.parse(feed);
        ArrayList<Departure> result = new ArrayList<Departure>(16);
        HashSet<String> seenTrips = new HashSet<String>(32);

        // Whole-stop mode: one physical stop is split into several GTFS quays
        // that share a name (e.g. "Suquet" = 4-1224 + 4-1228); match them all.
        // Line mode keeps the exact id: the line's stop list uses the
        // direction-specific quay and the feed reports that same id.
        HashSet<String> targets = null;
        if (routeId == null && cat != null) {
            targets = new HashSet<String>();
            for (String id : cat.siblingIds(stopId)) targets.add(id);
        }

        for (Event e : events) {
            if (e.timeSec <= nowSec) continue;                 // past
            if (targets != null) {
                if (!targets.contains(e.stopId)) continue;     // wrong quay
            } else if (!e.stopId.equals(stopId)) continue;     // wrong stop
            if (routeId != null && !routeId.equals(e.routeId)) continue;
            if (direction >= 0 && e.direction != direction) continue;
            if (e.tripId != null && !seenTrips.add(e.tripId)) continue;   // first event of trip only

            Catalog.Route route = cat.routes.get(e.routeId);
            String lineShort = route != null ? route.shortName : e.routeId;
            Catalog.Trip trip = e.tripId != null ? cat.trips.get(e.tripId) : null;
            String headsign;
            if (trip != null && !trip.headsign.isEmpty()) headsign = trip.headsign;
            else headsign = cat.dirLabel(e.routeId, Math.max(e.direction, 0));

            result.add(new Departure(e.routeId, lineShort, headsign, e.direction,
                    e.timeSec, e.delaySec));
            if (result.size() >= 60) break;   // plenty
        }

        Collections.sort(result);
        return result.size() > count ? new ArrayList<Departure>(result.subList(0, count)) : result;
    }
}
