package com.zai.divia.core;

import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** JVM test harness: not packaged into the APK. */
public class TestCore {
    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        Catalog cat = new Catalog(new FileInputStream("/home/z/my-project/scripts/diviaproj/assets/catalog.gz"));
        long tLoad = System.currentTimeMillis() - t0;
        System.out.println("catalog loaded in " + tLoad + " ms: "
                + cat.routes.size() + " routes, " + cat.stopNames.size() + " stops, "
                + cat.trips.size() + " trips");

        byte[] feed;
        if (args.length > 0 && args[0].equals("--offline")) {
            feed = readAll("/tmp/divia_tu.pb");
            System.out.println("using cached feed /tmp/divia_tu.pb (" + feed.length + " bytes)");
        } else {
            long t1 = System.currentTimeMillis();
            feed = DiviaApi.fetch(DiviaApi.FEED_URL, 15000);
            System.out.println("live feed fetched in " + (System.currentTimeMillis() - t1)
                    + " ms (" + feed.length + " bytes)");
        }

        long now = System.currentTimeMillis() / 1000;
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm:ss");

        System.out.println("\n== LINE MODE: T1 direction 1 (-> DIJON Gare), stop Darcy 4-1462 ==");
        List<Departure> deps = DiviaApi.nextDepartures(feed, cat, "4-1462", "4-T1", 1, 3, now);
        print(deps, now, hm);

        System.out.println("\n== LINE MODE: T2 direction 0 (-> DIJON Valmy), stop Republique 4-1477 ==");
        deps = DiviaApi.nextDepartures(feed, cat, "4-1477", "4-T2", 0, 3, now);
        print(deps, now, hm);

        System.out.println("\n== STOP MODE: all lines at Darcy 4-1462 ==");
        deps = DiviaApi.nextDepartures(feed, cat, "4-1462", null, -1, 3, now);
        print(deps, now, hm);

        System.out.println("\n== STOP MODE: all lines at Republique 4-1605 (Liane stops) ==");
        deps = DiviaApi.nextDepartures(feed, cat, "4-1605", null, -1, 3, now);
        print(deps, now, hm);

        // stress: parse the feed 10 times to measure per-refresh cost
        long t2 = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            List<ProtoRt.Event> evs = ProtoRt.parse(feed);
            if (i == 0) System.out.println("\nfeed events: " + evs.size());
        }
        System.out.println("10x full feed parse: " + (System.currentTimeMillis() - t2) + " ms");
    }

    private static void print(List<Departure> deps, long now, SimpleDateFormat hm) {
        if (deps.isEmpty()) {
            System.out.println("  (no upcoming departures)");
            return;
        }
        for (Departure d : deps) {
            long mins = (d.timeSec - now + 59) / 60;
            System.out.println("  " + String.format("%-3s -> %-28s %3d min  (%s)",
                    d.lineShort, d.headsign, mins, hm.format(new Date(d.timeSec * 1000))));
        }
    }

    private static byte[] readAll(String path) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.InputStream in = new FileInputStream(path);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
