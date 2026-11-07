package com.zai.divia;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.zai.divia.core.Catalog;
import com.zai.divia.core.Departure;
import com.zai.divia.core.DiviaApi;
import com.zai.divia.core.ProtoRt;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * JVM harness for the widget render pipeline, run against the REAL catalog
 * asset, the REAL string resources and the LIVE GTFS-RT feed, with the
 * exact widget configurations reported by the user (T2 Valmy -> Chenove,
 * L4 Monge -> Marsannay).
 *
 * Every RemoteViews action is recorded by the android stubs and asserted:
 * view ids must exist in widget_divia.xml, texts must be non-null, row
 * visibilities must match the departure count, PendingIntents must be
 * broadcasts (never started services).
 */
public final class RenderHarness {

    static final String PROJ = "/home/z/my-project/scripts/diviaproj";
    static int pass = 0, fail = 0;

    static void check(boolean ok, String msg) {
        if (ok) { pass++; System.out.println("  PASS  " + msg); }
        else    { fail++; System.out.println("  FAIL  " + msg); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Divia widget render harness ===");

        // ---------------------------------------------------------------- strings
        System.out.println("[strings]");
        Map<String, String> fr = parseStrings(PROJ + "/res/values/strings.xml");
        Map<String, String> en = parseStrings(PROJ + "/res/values-en/strings.xml");
        Map<Integer, String> frById = new HashMap<Integer, String>();
        for (Field f : R.string.class.getFields()) {
            String name = f.getName();
            check(fr.containsKey(name), "FR string present: " + name);
            if (fr.containsKey(name)) frById.put((Integer) f.get(null), fr.get(name));
        }
        for (String name : fr.keySet()) {
            check(en.containsKey(name), "EN string present: " + name);
            if (en.containsKey(name)) {
                check(fmtCount(fr.get(name)) == fmtCount(en.get(name)),
                        "FR/EN format args match: " + name);
            }
        }
        Context ctx = new Context();
        ctx.assetPath = PROJ + "/assets/catalog.gz";
        ctx.stringValues = frById;

        // Colors (day + night) so getResources().getColor() behaves like on
        // device and lateness palette mistakes fail the test.
        Map<String, Integer> dayColors = parseColors(PROJ + "/res/values/colors.xml");
        Map<String, Integer> nightColors = parseColors(PROJ + "/res/values-night/colors.xml");
        Map<Integer, String> colorNameById = new HashMap<Integer, String>();
        for (Field f : R.color.class.getFields()) colorNameById.put((Integer) f.get(null), f.getName());
        for (Map.Entry<Integer, String> e : colorNameById.entrySet()) {
            check(dayColors.containsKey(e.getValue()), "day color present: " + e.getValue());
            check(nightColors.containsKey(e.getValue()), "night color present: " + e.getValue());
            Integer d = dayColors.get(e.getValue());
            Integer n = nightColors.get(e.getValue());
            if (d != null) ctx.resources.colorValues.put(e.getKey(), d);
        }
        Integer lateDay = dayColors.get("late");
        Integer lateNight = nightColors.get("late");
        Integer cdDay = dayColors.get("countdown");
        Integer cdNight = nightColors.get("countdown");
        check(lateDay != null && lateNight != null, "late color defined in day AND night palettes");
        check(cdDay != null && cdNight != null, "countdown color defined in day AND night palettes");
        check(lateDay != null && cdDay != null && !lateDay.equals(cdDay),
                "late color is distinct from the normal countdown color (day)");
        check(lateNight != null && cdNight != null && !lateNight.equals(cdNight),
                "late color is distinct from the normal countdown color (night)");
        check(lateDay != null && lateNight != null && !lateDay.equals(lateNight),
                "late color has a night variant (not the day value reused)");

        // ---------------------------------------------------------------- layout ids
        System.out.println("[layout ids]");
        Set<String> layoutIdNames = parseLayoutIds(PROJ + "/res/layout/widget_divia.xml");
        Map<Integer, String> idByName = new HashMap<Integer, String>();
        for (Field f : R.id.class.getFields()) {
            idByName.put((Integer) f.get(null), f.getName());
        }
        Set<Integer> validIds = new HashSet<Integer>();
        for (String n : layoutIdNames) {
            Integer v = null;
            for (Map.Entry<Integer, String> e : idByName.entrySet()) {
                if (e.getValue().equals(n)) { v = e.getKey(); break; }
            }
            check(v != null, "R.id exists for layout id: " + n);
            if (v != null) validIds.add(v);
        }
        for (String needed : new String[]{"widget_root", "line_badge", "stop_name", "line_dest",
                "row0", "row1", "row2", "dest0", "dest1", "dest2",
                "count0", "count1", "count2", "clock0", "clock1", "clock2",
                "empty_status", "updated"}) {
            check(layoutIdNames.contains(needed), "widget_divia.xml contains @+id/" + needed);
        }

        // ---------------------------------------------------------------- catalog
        System.out.println("[catalog]");
        long t0 = System.nanoTime();
        Catalog cat = new Catalog(new FileInputStream(PROJ + "/assets/catalog.gz"));
        long catMs = (System.nanoTime() - t0) / 1000000L;
        check(cat.routes.size() > 50, "routes loaded: " + cat.routes.size());
        check(cat.stopNames.size() > 1000, "stops loaded: " + cat.stopNames.size());
        System.out.println("  info  catalog loaded in " + catMs + " ms");

        // ---------------------------------------------------------------- user's configs
        System.out.println("[user configs]");
        String t2Id = null, l4Id = null;
        for (Catalog.Route r : cat.routes.values()) {
            if ("T2".equals(r.shortName)) t2Id = r.id;
            if ("L4".equals(r.shortName)) l4Id = r.id;
        }
        check(t2Id != null, "route T2 found");
        check(l4Id != null, "route L4 found");

        int dirT2 = -1, dirL4 = -1;
        for (int d = 0; d <= 1; d++) {
            String lt2 = cat.dirLabel(t2Id, d), ll4 = cat.dirLabel(l4Id, d);
            System.out.println("  info  T2 dir " + d + ": " + lt2 + " | L4 dir " + d + ": " + ll4);
            if (lt2.toLowerCase().contains("chenove")) dirT2 = d;
            if (ll4.toLowerCase().contains("marsannay")) dirL4 = d;
        }
        check(dirT2 >= 0, "T2 direction toward Chenove found");
        check(dirL4 >= 0, "L4 direction toward Marsannay found");

        String valmyId = findStop(cat, cat.lineStopIds(t2Id, dirT2), "valmy");
        String mongeId = findStop(cat, cat.lineStopIds(l4Id, dirL4), "monge");
        check(valmyId != null, "stop Valmy found on T2: " + valmyId
                + (valmyId != null ? " (" + cat.stopName(valmyId) + ")" : ""));
        check(mongeId != null, "stop Monge found on L4: " + mongeId
                + (mongeId != null ? " (" + cat.stopName(mongeId) + ")" : ""));

        // ---------------------------------------------------------------- live feed
        System.out.println("[live feed]");
        byte[] feed = null;
        for (int attempt = 1; attempt <= 3 && feed == null; attempt++) {
            try {
                long tf = System.nanoTime();
                feed = DiviaApi.fetch(DiviaApi.FEED_URL, 15000);
                System.out.println("  info  fetched " + feed.length + " bytes in "
                        + (System.nanoTime() - tf) / 1000000L + " ms (attempt " + attempt + ")");
            } catch (Exception e) {
                System.out.println("  warn  attempt " + attempt + " failed: " + e);
                Thread.sleep(1500L);
            }
        }
        check(feed != null, "live feed downloaded");
        List<ProtoRt.Event> events = feed != null ? ProtoRt.parse(feed) : new ArrayList<ProtoRt.Event>();
        System.out.println("  info  parsed " + events.size() + " stop events");
        check(events.size() > 100, "feed parses to >100 events");

        // which routes does this snapshot actually contain?
        Set<String> feedRoutes = new HashSet<String>();
        long now = System.currentTimeMillis() / 1000L;
        int futureEvents = 0;
        for (ProtoRt.Event e : events) {
            if (e.routeId != null) feedRoutes.add(e.routeId);
            if (e.timeSec > now) futureEvents++;
        }
        System.out.println("  info  " + feedRoutes.size() + " routes, " + futureEvents + " future events");
        check(futureEvents > 0, "feed contains upcoming events");
        // Delay field coverage (the lateness marker's data source).
        int delayKnown = 0, lateEvents = 0;
        for (ProtoRt.Event e : events) {
            if (e.delaySec != Integer.MIN_VALUE) delayKnown++;
            if (e.delaySec >= RefreshLogic.LATE_MIN_SEC) lateEvents++;
        }
        System.out.println("  info  delay field on " + delayKnown + "/" + events.size()
                + " events; " + lateEvents + " late (>= " + RefreshLogic.LATE_MIN_SEC + " s)");
        check(delayKnown > 0, "feed carries the delay field (lateness marker data source)");
        boolean tramsInFeed = feedRoutes.contains(t2Id);
        System.out.println("  info  trams (T2) present in this snapshot: " + tramsInFeed
                + (tramsInFeed ? "" : " — provider-side gap, not an app bug"));

        // Reuse the fetched bytes for every refreshAll below: deterministic.
        RefreshLogic.sTestFeed = feed;

        List<Departure> depsT2 = feed != null
                ? DiviaApi.nextDepartures(feed, cat, valmyId, t2Id, dirT2, 3, now)
                : new ArrayList<Departure>();
        List<Departure> depsL4 = feed != null
                ? DiviaApi.nextDepartures(feed, cat, mongeId, l4Id, dirL4, 3, now)
                : new ArrayList<Departure>();
        List<Departure> depsStop = feed != null
                ? DiviaApi.nextDepartures(feed, cat, valmyId, null, -1, 3, now)
                : new ArrayList<Departure>();
        printDeps("T2 " + cat.stopName(valmyId) + " -> " + cat.dirLabel(t2Id, dirT2), depsT2, ctx, now);
        printDeps("L4 " + cat.stopName(mongeId) + " -> " + cat.dirLabel(l4Id, dirL4), depsL4, ctx, now);
        printDeps("whole stop " + cat.stopName(valmyId), depsStop, ctx, now);
        if (tramsInFeed) {
            check(!depsT2.isEmpty(), "departures found for Valmy on T2 (trams are in the feed)");
        }
        // With quay grouping, whole-stop mode at a tram quay must find the
        // sibling ids' departures (the Valmy bus hub id 4-15001 shares the
        // "DIJON Valmy" name).
        check(!depsStop.isEmpty(),
                "whole-stop Valmy finds departures through sibling quay ids");

        // ---------------------------------------------------------------- user's real config: L5 Suquet -> Universite
        System.out.println("[user config L5 Suquet -> Universite]");
        String l5Id = null;
        for (Catalog.Route r : cat.routes.values()) if ("L5".equals(r.shortName)) l5Id = r.id;
        check(l5Id != null, "route L5 found");
        int dirUniv = -1;
        String suquetId = null;
        if (l5Id != null) {
            for (int d = 0; d <= 1; d++) {
                String lbl = cat.dirLabel(l5Id, d);
                if (lbl.toLowerCase().contains("universit")) dirUniv = d;
            }
            check(dirUniv >= 0, "L5 direction toward Universite found");
            if (dirUniv >= 0) {
                String[] stops = cat.lineStopIds(l5Id, dirUniv);
                suquetId = findStop(cat, stops, "suquet");
            }
            check(suquetId != null, "stop Suquet found on L5->Universite: " + suquetId);
        }
        boolean l5InFeed = l5Id != null && feedRoutes.contains(l5Id);
        List<Departure> depsL5 = (l5InFeed && suquetId != null)
                ? DiviaApi.nextDepartures(feed, cat, suquetId, l5Id, dirUniv, 3, now)
                : new ArrayList<Departure>();
        printDeps("L5 " + cat.stopName(suquetId != null ? suquetId : "?")
                + " -> " + cat.dirLabel(l5Id != null ? l5Id : "", dirUniv), depsL5, ctx, now);
        if (l5InFeed) {
            check(!depsL5.isEmpty(), "L5 departures found at Suquet toward Universite");
        } else {
            System.out.println("  warn  L5 not in this feed snapshot");
        }

        // Sibling quays: whole-stop at one Suquet id must see BOTH
        // directions (the other quay's departures).
        if (suquetId != null) {
            List<Departure> suquetAll = DiviaApi.nextDepartures(feed, cat, suquetId, null, -1, 10, now);
            printDeps("whole stop Suquet (sibling quays merged)", suquetAll, ctx, now);
            boolean sawUniv = false, sawOther = false;
            for (Departure d : suquetAll) {
                if (d.headsign.toLowerCase().contains("universit")) sawUniv = true;
                else sawOther = true;
            }
            check(suquetAll.size() >= Math.min(2, futureEvents > 0 ? 2 : 0),
                    "whole-stop Suquet returns departures");
            if (l5InFeed) {
                check(sawUniv && sawOther,
                        "whole-stop Suquet merges both quays (saw Universite + other-direction departures)");
            }
            check(cat.siblingIds(suquetId).length >= 2,
                    "siblingIds(Suquet) >= 2 ids, got " + java.util.Arrays.toString(cat.siblingIds(suquetId)));
        }
        // Major stop hard check (buses, present all day)
        if (futureEvents > 0) {
            List<Departure> rep = DiviaApi.nextDepartures(feed, cat, "4-1605", null, -1, 5, now);
            printDeps("whole stop Republique (4-1605)", rep, ctx, now);
            check(!rep.isEmpty(), "whole-stop Republique finds departures");
        }

        // ---------------------------------------------------------------- render: line mode
        System.out.println("[render line mode]");
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        awm.clearUpdates();
        WidgetPrefs.Config cfgT2 = new WidgetPrefs.Config();
        cfgT2.lineMode = true;
        cfgT2.routeId = t2Id;
        cfgT2.dir = dirT2;
        cfgT2.stopId = valmyId;
        cfgT2.lastOk = now;
        RefreshLogic.render(ctx, awm, 8, cfgT2, cat, depsT2, now * 1000L, false);
        assertRender(awm, 8, validIds, idByName, depsT2.size(), "line mode T2",
                "T2", cat.stopName(valmyId));

        // ---------------------------------------------------------------- render: stop mode
        System.out.println("[render stop mode]");
        awm.clearUpdates();
        WidgetPrefs.Config cfgStop = new WidgetPrefs.Config();
        cfgStop.lineMode = false;
        cfgStop.routeId = "";
        cfgStop.dir = 0;
        cfgStop.stopId = valmyId;
        cfgStop.lastOk = now - 600L;
        RefreshLogic.render(ctx, awm, 9, cfgStop, cat, depsStop, now * 1000L, false);
        assertRender(awm, 9, validIds, idByName, depsStop.size(), "stop mode Valmy",
                null, cat.stopName(valmyId));

        // ---------------------------------------------------------------- render: offline, empty
        System.out.println("[render offline empty]");
        awm.clearUpdates();
        WidgetPrefs.Config cfgOff = new WidgetPrefs.Config();
        cfgOff.lineMode = true;
        cfgOff.routeId = t2Id;
        cfgOff.dir = dirT2;
        cfgOff.stopId = valmyId;
        cfgOff.lastOk = now - 1800L;
        RefreshLogic.render(ctx, awm, 10, cfgOff, cat, new ArrayList<Departure>(),
                now * 1000L, true);
        assertRender(awm, 10, validIds, idByName, 0, "offline empty", "T2", cat.stopName(valmyId));

        // ---------------------------------------------------------------- lateness marker
        System.out.println("[lateness marker]");
        // Deterministic synthetic feed: six trips at Darcy (4-1462) on T1
        // direction 1 with known delays, encoded as real GTFS-RT protobuf so
        // the whole chain (ProtoRt -> Departure -> render) is exercised.
        byte[] synFeed = feed(
                trip("syn-a", "4-T1", 1, stu("4-1462", now + 300, Integer.valueOf(0), true)),
                trip("syn-b", "4-T1", 1, stu("4-1462", now + 600, Integer.valueOf(240), true)),
                trip("syn-c", "4-T1", 1, stu("4-1462", now + 900, Integer.valueOf(-60), true)),
                trip("syn-d", "4-T1", 1, stu("4-1462", now + 1200, Integer.valueOf(119), true)),
                trip("syn-e", "4-T1", 1, stu("4-1462", now + 1800, Integer.valueOf(7500), true)),
                trip("syn-f", "4-T1", 1, stu("4-1462", now + 2400, Integer.valueOf(300), false)));
        List<ProtoRt.Event> synEvents = ProtoRt.parse(synFeed);
        check(synEvents.size() == 6, "synthetic feed parses to 6 events, got " + synEvents.size());
        List<Departure> synDeps = DiviaApi.nextDepartures(synFeed, cat, "4-1462", "4-T1", 1, 6, now);
        check(synDeps.size() == 6, "synthetic feed yields 6 departures, got " + synDeps.size());
        int[] wantDelay = {0, 240, -60, 119, 7500, 300};
        for (int i = 0; i < Math.min(synDeps.size(), wantDelay.length); i++) {
            check(synDeps.get(i).delaySec == wantDelay[i],
                    "delay parsed for row " + i + ": " + synDeps.get(i).delaySec
                            + " (want " + wantDelay[i] + ")");
        }

        // Render it and assert the exact texts and colors.
        WidgetPrefs.Config cfgSyn = new WidgetPrefs.Config();
        cfgSyn.lineMode = true;
        cfgSyn.routeId = "4-T1";
        cfgSyn.dir = 1;
        cfgSyn.stopId = "4-1462";
        cfgSyn.lastOk = now;
        awm.clearUpdates();
        RefreshLogic.render(ctx, awm, 30, cfgSyn, cat, synDeps, now * 1000L, false);
        check(awm.updates.size() == 1, "lateness: one update pushed");
        if (awm.updates.size() == 1) {
            RemoteViews rv = (RemoteViews) awm.updates.get(0)[1];
            Map<Integer, Object> texts = textsByView(rv);
            int[] clkIds = {R.id.clock0, R.id.clock1, R.id.clock2};
            String[] wantMarker = {null, "+4\u2032", null, null, "+2h05", "+5\u2032"};
            for (int i = 0; i < 3; i++) {
                String t = String.valueOf(texts.get(clkIds[i]));
                String w = wantMarker[i];
                if (w == null) {
                    check(!t.contains("+"), "lateness row " + i + ": no marker (on time), clock='" + t + "'");
                } else {
                    check(t.endsWith("\u00b7 " + w), "lateness row " + i + ": marker " + w
                            + ", clock='" + t + "'");
                }
            }
            Map<Integer, Integer> colors = colorsByView(rv);
            int[] cntIds = {R.id.count0, R.id.count1, R.id.count2};
            Integer lateDayCol = lateDay;
            Integer cdDayCol = cdDay;
            check(cntIds.length == 3 && colors.get(cntIds[0]) != null
                            && colors.get(cntIds[0]).equals(cdDayCol),
                    "lateness row 0 (on time): countdown keeps the normal color");
            check(colors.get(cntIds[1]) != null && colors.get(cntIds[1]).equals(lateDayCol),
                    "lateness row 1 (+4'): countdown turns amber (day)");
            check(colors.get(cntIds[2]) != null && colors.get(cntIds[2]).equals(cdDayCol),
                    "lateness row 2 (early -60 s): countdown keeps the normal color");
        }

        // Night palette: the same render must resolve the night "late" color.
        Context nightCtx = new Context();
        nightCtx.assetPath = ctx.assetPath;
        nightCtx.stringValues = ctx.stringValues;
        nightCtx.resources.colorValues.putAll(colorMapById(nightColors, colorNameById));
        awm.clearUpdates();
        RefreshLogic.render(nightCtx, awm, 31, cfgSyn, cat, synDeps, now * 1000L, false);
        check(awm.updates.size() == 1, "lateness night: one update pushed");
        if (awm.updates.size() == 1) {
            RemoteViews rv = (RemoteViews) awm.updates.get(0)[1];
            Map<Integer, Integer> colors = colorsByView(rv);
            Integer c1 = colors.get(R.id.count1);
            check(c1 != null && c1.equals(lateNight),
                    "lateness row 1 night: countdown uses the night amber");
            Integer c0 = colors.get(R.id.count0);
            check(c0 != null && c0.equals(cdNight),
                    "lateness row 0 night: on-time keeps the night countdown color");
        }

        // lateMarker formatting directly.
        check("+4\u2032".equals(RefreshLogic.lateMarker(ctx, 240)), "lateMarker(240 s) = +4'");
        check("+4\u2032".equals(RefreshLogic.lateMarker(ctx, 249)), "lateMarker(249 s) = +4' (rounds)");
        check("+2h05".equals(RefreshLogic.lateMarker(ctx, 7500)), "lateMarker(7500 s) = +2h05");
        check("+5\u2032".equals(RefreshLogic.lateMarker(ctx, 300)), "lateMarker(300 s) = +5'");

        // ---------------------------------------------------------------- placeholder
        System.out.println("[placeholder]");
        awm.clearUpdates();
        PendingIntent.created().clear();
        RefreshLogic.renderPlaceholder(ctx, awm, 11);
        check(awm.updates.size() == 1, "placeholder: exactly 1 update");
        if (awm.updates.size() == 1) {
            RemoteViews rv = (RemoteViews) awm.updates.get(0)[1];
            check(rv.layoutId == R.layout.widget_divia, "placeholder: layout is widget_divia");
            Map<Integer, Object> texts = textsByView(rv);
            check(nonEmpty(texts.get(R.id.stop_name)), "placeholder: stop_name text set");
            check(nonEmpty(texts.get(R.id.line_dest)), "placeholder: line_dest text set");
            check(nonEmpty(texts.get(R.id.empty_status)), "placeholder: empty_status text set");
            Map<Integer, Integer> vis = visByView(rv);
            check(vis.get(R.id.empty_status) != null
                            && vis.get(R.id.empty_status) == android.view.View.VISIBLE,
                    "placeholder: empty_status visible");
            boolean rowsGone = true;
            for (int[] row : new int[][]{{R.id.row0}, {R.id.row1}, {R.id.row2}}) {
                Integer v = vis.get(row[0]);
                if (v == null || v != android.view.View.GONE) rowsGone = false;
            }
            check(rowsGone, "placeholder: all rows gone");
            List<RemoteViews.Act> clicks = acts(rv, "click");
            check(clicks.size() == 1, "placeholder: 1 click PendingIntent");
            if (clicks.size() == 1) {
                PendingIntent pi = (PendingIntent) clicks.get(0).value;
                check("activity".equals(pi.kind), "placeholder: PendingIntent is activity");
                check(pi.intent.cls == ConfigActivity.class,
                        "placeholder: opens ConfigActivity");
                Object extra = pi.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
                check(((Integer) extra) == 11, "placeholder: carries widget id 11");
            }
            boolean idsOk = true;
            for (RemoteViews.Act a : rv.acts) if (!validIds.contains(a.viewId)) idsOk = false;
            check(idsOk, "placeholder: all view ids valid");
        }

        // ---------------------------------------------------------------- refreshAll end to end
        System.out.println("[refreshAll end-to-end]");
        WidgetPrefs.save(ctx, 8, cfgT2);
        WidgetPrefs.Config cfgL4 = new WidgetPrefs.Config();
        cfgL4.lineMode = true;
        cfgL4.routeId = l4Id;
        cfgL4.dir = dirL4;
        cfgL4.stopId = mongeId;
        WidgetPrefs.save(ctx, 11, cfgL4);
        awm.boundIds = new int[]{8, 11};
        awm.clearUpdates();
        PendingIntent.created().clear();
        RefreshLogic.refreshAll(ctx, null);
        check(awm.updates.size() == 2, "refreshAll: 2 widgets updated, got " + awm.updates.size());
        Set<Integer> updatedIds = new HashSet<Integer>();
        for (Object[] u : awm.updates) updatedIds.add((Integer) u[0]);
        check(updatedIds.contains(8) && updatedIds.contains(11), "refreshAll: ids 8 and 11");
        check(WidgetPrefs.load(ctx, 8) != null && WidgetPrefs.load(ctx, 8).lastOk > 0,
                "refreshAll: lastOk persisted for widget 8");
        boolean noService = true;
        for (PendingIntent pi : PendingIntent.created()) {
            if (!"broadcast".equals(pi.kind) && !"activity".equals(pi.kind)) noService = false;
        }
        check(noService, "refreshAll: no started-service PendingIntent (the v1.0 crash)");

        // unbound id -> placeholder path
        System.out.println("[refreshAll placeholder for unconfigured]");
        WidgetPrefs.save(ctx, 11, cfgL4);
        android.content.SharedPreferences p = ctx.getSharedPreferences("divia_widgets", 0);
        p.edit().remove("w_11_mode").apply();
        awm.boundIds = new int[]{8, 11};
        awm.clearUpdates();
        RefreshLogic.refreshAll(ctx, null);
        boolean sawPlaceholderFor11 = false;
        for (Object[] u : awm.updates) {
            if (((Integer) u[0]) == 11) {
                for (RemoteViews.Act a : ((RemoteViews) u[1]).acts) {
                    if (a.op.equals("text") && a.viewId == R.id.stop_name
                            && "Divia".equals(a.value)) sawPlaceholderFor11 = true;
                }
            }
        }
        check(sawPlaceholderFor11, "refreshAll: unconfigured widget 11 got the placeholder");

        // ---------------------------------------------------------------- alarm
        System.out.println("[alarm]");
        ctx.alarmManager.setRepeatingCalls.clear();
        ctx.alarmManager.cancels.clear();
        PendingIntent.created().clear();
        AlarmScheduler.schedule(ctx);
        check(ctx.alarmManager.setRepeatingCalls.size() == 1, "alarm: one repeating alarm set");
        if (!ctx.alarmManager.setRepeatingCalls.isEmpty()) {
            Object[] call = ctx.alarmManager.setRepeatingCalls.get(0);
            check(((Long) call[2]).longValue() == AlarmScheduler.INTERVAL_MS,
                    "alarm: interval is " + AlarmScheduler.INTERVAL_MS + " ms");
            PendingIntent pi = (PendingIntent) call[3];
            check("broadcast".equals(pi.kind), "alarm: PendingIntent is broadcast");
            check(pi.intent.cls == RefreshReceiver.class, "alarm: targets RefreshReceiver");
            check("com.zai.divia.ACTION_ALARM".equals(pi.intent.action),
                    "alarm: action ACTION_ALARM");
        }
        AlarmScheduler.cancel(ctx);
        check(ctx.alarmManager.cancels.size() >= 1, "alarm: cancel removes the alarm");

        // ---------------------------------------------------------------- prefs
        System.out.println("[prefs]");
        WidgetPrefs.Config c = new WidgetPrefs.Config();
        c.lineMode = false; c.routeId = "r"; c.dir = 1; c.stopId = "s1"; c.lastOk = 123L;
        WidgetPrefs.save(ctx, 77, c);
        WidgetPrefs.Config back = WidgetPrefs.load(ctx, 77);
        check(back != null && !back.lineMode && "r".equals(back.routeId)
                && back.dir == 1 && "s1".equals(back.stopId) && back.lastOk == 123L,
                "prefs save/load round trip");
        WidgetPrefs.delete(ctx, 77);
        check(WidgetPrefs.load(ctx, 77) == null, "prefs delete");

        // ---------------------------------------------------------------- render: user's L5 widget, live data
        System.out.println("[render user config L5]");
        if (suquetId != null && l5Id != null) {
            awm.clearUpdates();
            WidgetPrefs.Config cfgL5 = new WidgetPrefs.Config();
            cfgL5.lineMode = true;
            cfgL5.routeId = l5Id;
            cfgL5.dir = dirUniv >= 0 ? dirUniv : 1;
            cfgL5.stopId = suquetId;
            cfgL5.lastOk = now;
            RefreshLogic.render(ctx, awm, 20, cfgL5, cat, depsL5, now * 1000L, false);
            assertRender(awm, 20, validIds, idByName, depsL5.size(), "line mode L5",
                    "L5", cat.stopName(suquetId));
        }

        // ---------------------------------------------------------------- render: catalog failed to load
        System.out.println("[render null catalog]");
        awm.clearUpdates();
        WidgetPrefs.Config cfgNC = new WidgetPrefs.Config();
        cfgNC.lineMode = true;
        cfgNC.routeId = l5Id != null ? l5Id : "4-L5";
        cfgNC.dir = 1;
        cfgNC.stopId = "4-1224";
        cfgNC.lastOk = now - 300L;
        boolean ncThrew = false;
        try {
            RefreshLogic.render(ctx, awm, 21, cfgNC, null, depsL5, now * 1000L, false);
        } catch (Throwable t) {
            ncThrew = true;
        }
        check(!ncThrew, "render survives a null catalog");
        if (awm.updates.size() == 1) {
            RemoteViews rv = (RemoteViews) awm.updates.get(0)[1];
            Map<Integer, Object> texts = textsByView(rv);
            check("4-1224".equals(texts.get(R.id.stop_name)),
                    "null catalog: stop name falls back to the raw stop id");
            check(nonEmpty(texts.get(R.id.updated)), "null catalog: footer still set");
        }

        // ---------------------------------------------------------------- repaintAll (no network)
        System.out.println("[repaintAll]");
        WidgetPrefs.Config cfgRp = new WidgetPrefs.Config();
        cfgRp.lineMode = true;
        cfgRp.routeId = l5Id != null ? l5Id : "4-L5";
        cfgRp.dir = dirUniv >= 0 ? dirUniv : 1;
        cfgRp.stopId = suquetId != null ? suquetId : "4-1224";
        cfgRp.lastOk = now - 120L;
        WidgetPrefs.save(ctx, 40, cfgRp);
        synchronized (RefreshLogic.CACHE) {
            RefreshLogic.CACHE.put(40, new ArrayList<Departure>(depsL5));
        }
        awm.boundIds = new int[]{40, 41};
        awm.clearUpdates();
        RefreshLogic.repaintAll(ctx, null);
        check(awm.updates.size() == 2, "repaintAll: one update per bound id, got " + awm.updates.size());
        for (Object[] u : awm.updates) {
            int wid = (Integer) u[0];
            RemoteViews rv = (RemoteViews) u[1];
            check(rv.layoutId == R.layout.widget_divia, "repaintAll: widget " + wid + " layout correct");
            boolean idsOk = true;
            for (RemoteViews.Act a : rv.acts) if (!validIds.contains(a.viewId)) idsOk = false;
            check(idsOk, "repaintAll: widget " + wid + " view ids valid");
            if (wid == 40) {
                Map<Integer, Integer> vis = visByView(rv);
                int visible = 0;
                for (int rid : new int[]{R.id.row0, R.id.row1, R.id.row2}) {
                    Integer v = vis.get(rid);
                    if (v != null && v == android.view.View.VISIBLE) visible++;
                }
                check(visible == Math.min(3, depsL5.size()),
                        "repaintAll: cached rows painted (" + visible + " rows)");
            } else if (wid == 41) {
                Map<Integer, Object> texts = textsByView(rv);
                check("Divia".equals(texts.get(R.id.stop_name)),
                        "repaintAll: unconfigured widget 41 got the placeholder");
            }
        }

        // ---------------------------------------------------------------- refreshAll with a corrupted feed
        System.out.println("[refreshAll broken feed]");
        RefreshLogic.sTestFeed = new byte[]{0x0A, 0x05, 'h', 'e', 'l', 'l', 'o', 0x3F, 0x00, 0x71, 0x2A};
        awm.boundIds = new int[]{40, 41};
        awm.clearUpdates();
        boolean brokenThrew = false;
        try {
            RefreshLogic.refreshAll(ctx, null);
        } catch (Throwable t) {
            brokenThrew = true;
        }
        check(!brokenThrew, "refreshAll never throws on a corrupted feed");
        Set<Integer> painted = new HashSet<Integer>();
        for (Object[] u : awm.updates) painted.add((Integer) u[0]);
        check(painted.contains(40) && painted.contains(41),
                "broken feed: both widgets still got a fresh view");
        check(WidgetPrefs.getLastError(ctx) != null,
                "broken feed: last error recorded for diagnostics");
        System.out.println("  info  last error: " + java.util.Arrays.toString(WidgetPrefs.getLastError(ctx)));
        RefreshLogic.sTestFeed = feed;

        // ---------------------------------------------------------------- summary
        System.out.println();
        System.out.println("=== " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- helpers

    static void assertRender(AppWidgetManager awm, int widgetId, Set<Integer> validIds,
                             Map<Integer, String> idByName, int depCount, String label,
                             String expectedBadge, String expectedStop) {
        check(awm.updates.size() == 1, label + ": exactly 1 update, got " + awm.updates.size());
        if (awm.updates.isEmpty()) return;
        int id = (Integer) awm.updates.get(0)[0];
        RemoteViews rv = (RemoteViews) awm.updates.get(0)[1];
        check(id == widgetId, label + ": update for widget " + widgetId);
        check(rv.layoutId == R.layout.widget_divia, label + ": layout is widget_divia");
        check("com.zai.divia".equals(rv.packageName), label + ": package name correct");

        boolean idsOk = true;
        for (RemoteViews.Act a : rv.acts) {
            if (!validIds.contains(a.viewId)) {
                idsOk = false;
                System.out.println("        bad view id " + a.viewId + " (" + a.op + ")");
            }
        }
        check(idsOk, label + ": all view ids exist in widget_divia.xml");

        Map<Integer, Object> texts = textsByView(rv);
        check(nonEmpty(texts.get(R.id.stop_name))
                        && (!nonEmpty(expectedStop)
                            || String.valueOf(texts.get(R.id.stop_name)).contains(expectedStop)),
                label + ": stop name '" + texts.get(R.id.stop_name) + "'");
        if (expectedBadge != null) {
            check(expectedBadge.equals(texts.get(R.id.line_badge)),
                    label + ": badge is " + expectedBadge);
        }
        check(nonEmpty(texts.get(R.id.line_dest)), label + ": line_dest set");
        check(nonEmpty(texts.get(R.id.updated)), label + ": footer set");

        Map<Integer, Integer> vis = visByView(rv);
        int visible = 0;
        for (int rid : new int[]{R.id.row0, R.id.row1, R.id.row2}) {
            Integer v = vis.get(rid);
            if (v != null && v == android.view.View.VISIBLE) visible++;
        }
        check(visible == Math.min(3, depCount),
                label + ": " + visible + " visible rows for " + depCount + " departures");
        for (int i = 0; i < Math.min(3, depCount); i++) {
            int dest = new int[]{R.id.dest0, R.id.dest1, R.id.dest2}[i];
            int cnt = new int[]{R.id.count0, R.id.count1, R.id.count2}[i];
            int clk = new int[]{R.id.clock0, R.id.clock1, R.id.clock2}[i];
            check(nonEmpty(texts.get(dest)) && nonEmpty(texts.get(cnt)) && nonEmpty(texts.get(clk)),
                    label + ": row " + i + " texts set ('" + texts.get(dest) + "' '"
                            + texts.get(cnt) + "' '" + texts.get(clk) + "')");
        }

        List<RemoteViews.Act> clicks = acts(rv, "click");
        check(clicks.size() == 1, label + ": exactly 1 click PendingIntent");
        if (clicks.size() == 1) {
            check(clicks.get(0).viewId == R.id.widget_root, label + ": click on widget_root");
            PendingIntent pi = (PendingIntent) clicks.get(0).value;
            check("broadcast".equals(pi.kind), label + ": click PendingIntent is broadcast");
            check(pi.intent.cls == RefreshReceiver.class, label + ": click targets RefreshReceiver");
            check("com.zai.divia.ACTION_REFRESH".equals(pi.intent.action),
                    label + ": click action ACTION_REFRESH");
            check(pi.requestCode == widgetId, label + ": click requestCode is the widget id");
            Object extra = pi.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            check(((Integer) extra) == widgetId, label + ": click carries the widget id");
        }
    }

    static void printDeps(String title, List<Departure> deps, Context ctx, long now) {
        System.out.println("  info  " + title + ": " + deps.size() + " departures");
        for (Departure d : deps) {
            String late = d.delaySec == Integer.MIN_VALUE ? ""
                    : (d.delaySec >= RefreshLogic.LATE_MIN_SEC
                        ? "  LATE " + RefreshLogic.lateMarker(ctx, d.delaySec)
                        : (d.delaySec < 0 ? "  (early " + d.delaySec + " s)" : ""));
            System.out.println("        " + d.lineShort + " " + d.headsign + "  "
                    + RefreshLogic.formatClock(ctx, d.timeSec * 1000L) + "  ("
                    + RefreshLogic.formatCountdown(ctx, d.timeSec - now) + ")" + late);
        }
    }

    static Map<Integer, Object> textsByView(RemoteViews rv) {
        Map<Integer, Object> m = new HashMap<Integer, Object>();
        for (RemoteViews.Act a : rv.acts) if (a.op.equals("text")) m.put(a.viewId, a.value);
        return m;
    }

    static Map<Integer, Integer> visByView(RemoteViews rv) {
        Map<Integer, Integer> m = new HashMap<Integer, Integer>();
        for (RemoteViews.Act a : rv.acts) {
            if (a.op.equals("vis")) m.put(a.viewId, (Integer) a.value);
        }
        return m;
    }

    static Map<Integer, Integer> colorsByView(RemoteViews rv) {
        Map<Integer, Integer> m = new HashMap<Integer, Integer>();
        for (RemoteViews.Act a : rv.acts) {
            if (a.op.equals("color")) m.put(a.viewId, (Integer) a.value);
        }
        return m;
    }

    static List<RemoteViews.Act> acts(RemoteViews rv, String op) {
        List<RemoteViews.Act> out = new ArrayList<RemoteViews.Act>();
        for (RemoteViews.Act a : rv.acts) if (a.op.equals(op)) out.add(a);
        return out;
    }

    static boolean nonEmpty(Object o) {
        return o != null && !String.valueOf(o).isEmpty();
    }

    static String findStop(Catalog cat, String[] stopIds, String needle) {
        if (stopIds == null) return null;
        for (String sid : stopIds) {
            if (cat.stopName(sid).toLowerCase().contains(needle)) return sid;
        }
        return null;
    }

    static Map<String, String> parseStrings(String path) throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        Map<String, String> out = new HashMap<String, String>();
        Matcher m = Pattern.compile("<string name=\"([^\"]+)\">(.*?)</string>",
                Pattern.DOTALL).matcher(xml);
        while (m.find()) out.put(m.group(1), unescape(m.group(2)));
        return out;
    }

    static int fmtCount(String s) {
        Matcher m = Pattern.compile("%[0-9$]*[sd]").matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static String unescape(String s) {
        return s.replace("\\'", "'").replace("\\n", "\n").replace("\\\"", "\"")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    static Set<String> parseLayoutIds(String path) throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        Set<String> out = new HashSet<String>();
        Matcher m = Pattern.compile("@\\+id/([A-Za-z0-9_]+)").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** name -> 0xAARRGGBB from a colors.xml (day or night). */
    static Map<String, Integer> parseColors(String path) throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        Map<String, Integer> out = new HashMap<String, Integer>();
        Matcher m = Pattern.compile("<color name=\"([^\"]+)\">(#?[0-9A-Fa-f]{3,8})</color>").matcher(xml);
        while (m.find()) out.put(m.group(1), Integer.valueOf(parseColorValue(m.group(2))));
        return out;
    }

    static int parseColorValue(String v) {
        String h = v.startsWith("#") ? v.substring(1) : v;
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        long rgb = Long.parseLong(h, 16);
        if (h.length() <= 6) return (int) (0xFF000000L | rgb);
        return (int) rgb;
    }

    static Map<Integer, Integer> colorMapById(Map<String, Integer> byName,
                                              Map<Integer, String> nameById) {
        Map<Integer, Integer> out = new HashMap<Integer, Integer>();
        for (Map.Entry<Integer, String> e : nameById.entrySet()) {
            Integer v = byName.get(e.getValue());
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    // -------------------------------------------------- synthetic GTFS-RT feed

    /** Minimal protobuf writer (wire format only, mirrors ProtoRt's reader). */
    static final class Pb {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        Pb varint(long v) {
            while (true) {
                if ((v & ~0x7FL) == 0) { out.write((int) v); return this; }
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
        }

        Pb tag(int field, int wire) { return varint(((long) field << 3) | wire); }

        Pb bytes(int field, byte[] b) {
            tag(field, 2).varint(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        Pb str(int field, String s) {
            return bytes(field, s.getBytes(StandardCharsets.UTF_8));
        }

        Pb vint(int field, long v) { tag(field, 0); return varint(v); }

        byte[] build() { return out.toByteArray(); }
    }

    /** StopTimeUpdate; departure event when {@code dep}, arrival otherwise. */
    static byte[] stu(String stopId, long timeSec, Integer delay, boolean dep) {
        Pb ste = new Pb();
        if (delay != null) ste.vint(1, delay.intValue());   // delay (s), may be negative
        ste.vint(2, timeSec);                               // time
        Pb s = new Pb();
        s.bytes(dep ? 3 : 2, ste.build());
        s.str(4, stopId);
        return s.build();
    }

    static byte[] trip(String tripId, String routeId, int dir, byte[]... stus) {
        Pb td = new Pb();
        td.str(1, tripId);
        td.str(5, routeId);
        td.vint(6, dir);
        Pb tu = new Pb();
        tu.bytes(1, td.build());
        for (byte[] s : stus) tu.bytes(2, s);
        Pb ent = new Pb();
        ent.bytes(3, tu.build());
        return ent.build();
    }

    static byte[] feed(byte[]... entities) {
        Pb msg = new Pb();
        for (byte[] e : entities) msg.bytes(2, e);
        return msg.build();
    }

    private RenderHarness() {}
}
