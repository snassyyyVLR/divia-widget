package com.zai.divia.core;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Diagnose stop_id / route_id overlap between the live feed and the catalog. */
public final class ProbeIds {
    public static void main(String[] args) throws Exception {
        String proj = "/home/z/my-project/scripts/diviaproj";
        Catalog cat = new Catalog(new FileInputStream(proj + "/assets/catalog.gz"));
        byte[] feed = DiviaApi.fetch(DiviaApi.FEED_URL, 15000);
        List<ProtoRt.Event> events = ProtoRt.parse(feed);

        Set<String> feedStops = new HashSet<String>();
        Set<String> feedRoutes = new HashSet<String>();
        long now = System.currentTimeMillis() / 1000L;
        int future = 0;
        for (ProtoRt.Event e : events) {
            feedStops.add(e.stopId);
            if (e.routeId != null) feedRoutes.add(e.routeId);
            if (e.timeSec > now) future++;
        }
        System.out.println("events=" + events.size() + " future=" + future
                + " feedStops=" + feedStops.size() + " feedRoutes=" + feedRoutes.size());

        // overlap
        Set<String> catStops = cat.stopNames.keySet();
        Set<String> common = new HashSet<String>(feedStops);
        common.retainAll(catStops);
        System.out.println("catalog stops=" + catStops.size()
                + " | feed stops ALSO in catalog=" + common.size()
                + " | feed stops NOT in catalog=" + (feedStops.size() - common.size()));

        Set<String> catRoutes = cat.routes.keySet();
        Set<String> commonR = new HashSet<String>(feedRoutes);
        commonR.retainAll(catRoutes);
        System.out.println("catalog routes=" + catRoutes.size()
                + " | feed routes ALSO in catalog=" + commonR.size());
        Set<String> feedOnly = new HashSet<String>(feedRoutes);
        feedOnly.removeAll(catRoutes);
        System.out.println("feed-only routes: " + feedOnly);

        // future events per stop, top 15
        Map<String, Integer> futByStop = new HashMap<String, Integer>();
        for (ProtoRt.Event e : events) {
            if (e.timeSec > now) {
                Integer v = futByStop.get(e.stopId);
                futByStop.put(e.stopId, v == null ? 1 : v + 1);
            }
        }
        List<String> top = new ArrayList<String>(futByStop.keySet());
        top.sort((a, b) -> futByStop.get(b) - futByStop.get(a));
        System.out.println("\ntop future stops (feed id -> name in catalog if known):");
        for (int i = 0; i < Math.min(15, top.size()); i++) {
            String s = top.get(i);
            System.out.println("  " + s + "  x" + futByStop.get(s) + "  ->  "
                    + (cat.stopNames.containsKey(s) ? cat.stopNames.get(s) : "?? NOT IN CATALOG"));
        }

        // the two user-relevant stops: find catalog ids whose name contains Valmy / Monge / Suquet
        for (String needle : new String[]{"valmy", "monge", "suquet"}) {
            System.out.println("\ncatalog stops matching '" + needle + "':");
            for (Map.Entry<String, String> en : cat.stopNames.entrySet()) {
                if (en.getValue().toLowerCase().contains(needle)) {
                    String id = en.getKey();
                    Integer fut = futByStop.get(id);
                    System.out.println("  " + id + "  " + en.getValue()
                            + "  futureEvents=" + (fut == null ? 0 : fut));
                }
            }
        }

        // check route short names for feed routes
        System.out.println("\nfeed routes with catalog names:");
        for (String r : new ArrayList<String>(feedRoutes)) {
            Catalog.Route cr = cat.routes.get(r);
            System.out.println("  " + r + " -> " + (cr == null ? "?? NOT IN CATALOG"
                    : cr.shortName + " " + cr.longName));
        }
    }
}
