package com.zai.divia.core;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test the user's EXACT widget config (L5 -> Universite, stop Suquet) on the live feed,
 *  plus the multi-id (quay) behaviour of whole-stop mode. */
public final class ProbeUserConfig {
    public static void main(String[] args) throws Exception {
        String proj = "/home/z/my-project/scripts/diviaproj";
        Catalog cat = new Catalog(new FileInputStream(proj + "/assets/catalog.gz"));
        byte[] feed = DiviaApi.fetch(DiviaApi.FEED_URL, 15000);
        long now = System.currentTimeMillis() / 1000L;

        // ---- L5 route, both directions, find Suquet in the line stop lists
        String l5 = null;
        for (Catalog.Route r : cat.routes.values()) if ("L5".equals(r.shortName)) l5 = r.id;
        System.out.println("L5 route id: " + l5);
        for (int d = 0; d <= 1; d++) {
            System.out.println("L5 dir " + d + " label: " + cat.dirLabel(l5, d));
            String[] stops = cat.lineStopIds(l5, d);
            for (String s : stops) {
                if (cat.stopName(s).toLowerCase().contains("suquet")) {
                    System.out.println("  Suquet id in dir-" + d + " stop list: " + s);
                    List<Departure> deps = DiviaApi.nextDepartures(feed, cat, s, l5, d, 5, now);
                    System.out.println("    line-mode departures: " + deps.size());
                    for (Departure dp : deps)
                        System.out.println("      " + dp.lineShort + " -> " + dp.headsign
                                + "  +" + ((dp.timeSec - now) / 60) + " min");
                }
            }
        }

        // ---- whole-stop mode for each of the Suquet twins
        for (String s : new String[]{"4-1224", "4-1228"}) {
            List<Departure> deps = DiviaApi.nextDepartures(feed, cat, s, null, -1, 5, now);
            System.out.println("whole-stop " + s + " (" + cat.stopName(s) + "): " + deps.size() + " departures");
            for (Departure dp : deps)
                System.out.println("      " + dp.lineShort + " -> " + dp.headsign + "  +" + ((dp.timeSec - now) / 60) + " min");
        }

        // ---- duplicate names across ids (the quay problem)
        Map<String, List<String>> byName = new HashMap<String, List<String>>();
        for (Map.Entry<String, String> e : cat.stopNames.entrySet()) {
            List<String> l = byName.get(e.getValue());
            if (l == null) { l = new ArrayList<String>(); byName.put(e.getValue(), l); }
            l.add(e.getKey());
        }
        int dupNames = 0, maxIds = 0;
        for (Map.Entry<String, List<String>> e : byName.entrySet()) {
            if (e.getValue().size() > 1) {
                dupNames++;
                maxIds = Math.max(maxIds, e.getValue().size());
            }
        }
        System.out.println("\nstop names with >1 id: " + dupNames + " (max ids per name: " + maxIds + ")");
        int shown = 0;
        for (Map.Entry<String, List<String>> e : byName.entrySet()) {
            if (e.getValue().size() > 1 && shown++ < 8) System.out.println("  " + e.getValue() + "  " + e.getKey());
        }

        // ---- a big stop for a hard assertion: Republique 4-1605
        List<Departure> rep = DiviaApi.nextDepartures(feed, cat, "4-1605", null, -1, 5, now);
        System.out.println("\nwhole-stop 4-1605 République: " + rep.size() + " departures");
        for (Departure dp : rep)
            System.out.println("      " + dp.lineShort + " -> " + dp.headsign + "  +" + ((dp.timeSec - now) / 60) + " min");

        // ---- do feed events at Suquet use the SAME ids as catalog line lists? summary by id+route
        Set<String> suquetIds = new HashSet<String>(byName.get("Suquet"));
        Map<String, Integer> seen = new HashMap<String, Integer>();
        for (ProtoRt.Event e : ProtoRt.parse(feed)) {
            if (suquetIds.contains(e.stopId) && e.timeSec > now) {
                String k = e.stopId + " " + e.routeId + " dir" + e.direction;
                Integer v = seen.get(k);
                seen.put(k, v == null ? 1 : v + 1);
            }
        }
        System.out.println("\nfuture Suquet events by (id, route, dir): " + seen);
    }
}
