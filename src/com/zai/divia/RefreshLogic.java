/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.zai.divia.core.Catalog;
import com.zai.divia.core.Departure;
import com.zai.divia.core.DiviaApi;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

/**
 * The shared update engine — v2.0 clean rewrite.
 *
 * Downloads the feed once and renders every bound widget. All entry points
 * are called from worker threads (the alarm receiver's goAsync window, the
 * provider's synchronous bind path, the boot receiver or the config
 * screen), and every one of them is crash-proof:
 *
 *  - No exception ever escapes a public entry point; each widget gets a
 *    RemoteViews push on every call even when the network, the feed or
 *    the catalog is broken.
 *  - Local-only repaints never touch the network, so bind-time paints are
 *    sub-millisecond and safe to run synchronously inside onUpdate.
 *  - The layout pushed here (res/layout/widget_divia.xml) contains ONLY
 *    @RemoteView-whitelisted classes; the JVM harness re-asserts this on
 *    every run so a regression cannot ship again (the v1.x "Problem
 *    loading widget on every launcher" bug was a plain <View> divider
 *    inflating inside the launcher's process).
 */
public final class RefreshLogic {

    private static Catalog sCatalog;
    /** Last good departures per widget id, so a failed refresh keeps the rows. */
    static final HashMap<Integer, List<Departure>> CACHE = new HashMap<Integer, List<Departure>>();
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Test seam for the JVM harness: when non-null, no network fetch happens. */
    static volatile byte[] sTestFeed;

    private static final int[] ROW_IDS = {R.id.row0, R.id.row1, R.id.row2};
    private static final int[] DEST_IDS = {R.id.dest0, R.id.dest1, R.id.dest2};
    private static final int[] COUNT_IDS = {R.id.count0, R.id.count1, R.id.count2};
    private static final int[] CLOCK_IDS = {R.id.clock0, R.id.clock1, R.id.clock2};

    /** A vehicle this many seconds behind schedule is flagged as late. */
    static final int LATE_MIN_SEC = 120;

    // ------------------------------------------------------------------ api

    /**
     * Fire-and-forget refresh used from the foreground (config screen /
     * app open). Also (re)arms the periodic alarm, which doubles as a
     * self-heal for launchers that lost track of the widget.
     */
    public static void kick(final Context c) {
        try {
            AlarmScheduler.schedule(c);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "schedule: " + t);
        }
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    refreshAll(c, null);
                } catch (Throwable t) {
                    // never crash the app from a refresh
                    WidgetPrefs.setLastError(c, "kick: " + t);
                } finally {
                    RUNNING.set(false);
                }
            }
        }, "divia-kick").start();
    }

    static synchronized Catalog catalog(Context c) throws IOException {
        if (sCatalog == null) {
            InputStream in = c.getAssets().open("catalog.gz");
            try {
                sCatalog = new Catalog(in);
            } finally {
                in.close();
            }
        }
        return sCatalog;
    }

    private static Catalog catalogQuiet(Context c) {
        try {
            return catalog(c);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "catalog: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------- refresh

    /** Refreshes every bound widget (or the given subset). Never throws. */
    public static void refreshAll(Context context, int[] widgetIds) {
        try {
            refreshAllInner(context, widgetIds);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "refresh: " + t);
        }
    }

    private static void refreshAllInner(Context context, int[] widgetIds) {
        AppWidgetManager awm = AppWidgetManager.getInstance(context);
        int[] all = boundIds(context, awm, widgetIds);
        if (all == null || all.length == 0) return;

        // Split: configured widgets get live data, unconfigured ones get
        // the tap-to-configure placeholder immediately.
        ArrayList<Integer> ids = new ArrayList<Integer>();
        ArrayList<WidgetPrefs.Config> cfgs = new ArrayList<WidgetPrefs.Config>();
        for (int id : all) {
            WidgetPrefs.Config cfg = WidgetPrefs.load(context, id);
            if (cfg != null && !cfg.stopId.isEmpty()) {
                ids.add(id);
                cfgs.add(cfg);
            } else {
                safeRenderPlaceholder(context, awm, id);
            }
        }

        Catalog cat = catalogQuiet(context);

        // ---- pass 1: instant repaint from the last good data, so a broken
        // launcher-side state never survives this call even offline.
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            WidgetPrefs.Config cfg = cfgs.get(i);
            List<Departure> cached = peekCache(id);
            if (cached != null && !cached.isEmpty() && cfg.lastOk > 0) {
                safeRender(context, awm, id, cfg, cat, cached, cfg.lastOk * 1000L, false);
            }
        }

        // ---- pass 2: live data.
        byte[] feed = sTestFeed;
        Throwable fetchErr = null;
        if (feed == null) {
            try {
                // connect<=4s + read<=6s: never exceed the ~10s goAsync window
                feed = DiviaApi.fetch(DiviaApi.FEED_URL, 6000);
            } catch (Throwable t) {
                fetchErr = t;
            }
        }

        if (feed == null || cat == null) {
            if (feed == null && fetchErr != null) WidgetPrefs.setLastError(context, "feed: " + fetchErr);
            // Offline / catalog-less: keep last good rows, mark footer offline.
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.get(i);
                WidgetPrefs.Config cfg = cfgs.get(i);
                List<Departure> cached = peekCache(id);
                if (cached == null) cached = new ArrayList<Departure>();
                safeRender(context, awm, id, cfg, cat, cached, System.currentTimeMillis(), true);
            }
            return;
        }

        long nowSec = System.currentTimeMillis() / 1000L;
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            WidgetPrefs.Config cfg = cfgs.get(i);
            List<Departure> deps = null;
            Throwable err = null;
            try {
                deps = DiviaApi.nextDepartures(feed, cat, cfg.stopId,
                        cfg.lineMode ? cfg.routeId : null,
                        cfg.lineMode ? cfg.dir : -1, 3, nowSec);
            } catch (Throwable t) {
                err = t;
            }
            if (err != null) {
                WidgetPrefs.setLastError(context, "parse: " + err);
                List<Departure> cached = peekCache(id);
                if (cached == null) cached = new ArrayList<Departure>();
                safeRender(context, awm, id, cfg, cat, cached, System.currentTimeMillis(), true);
                continue;
            }
            synchronized (CACHE) {
                CACHE.put(id, deps);
            }
            WidgetPrefs.setLastOk(context, id, nowSec);
            cfg.lastOk = nowSec;
            safeRender(context, awm, id, cfg, cat, deps, nowSec * 1000L, false);
        }
        WidgetPrefs.setLastRefreshOk(context);
    }

    // -------------------------------------------------------------- repaint

    /**
     * Immediate, network-free repaint of every bound widget (or the given
     * subset): configured widgets get their last good data, unconfigured
     * ones get the setup placeholder. Used at bind time, on resize, by the
     * repair button and right after an app update — pushing fresh views
     * is the ONLY thing that replaces a launcher's "Can't load widget"
     * state. Never throws, never touches the network.
     */
    public static void repaintAll(Context context, int[] widgetIds) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(context);
            int[] all = boundIds(context, awm, widgetIds);
            if (all == null || all.length == 0) return;
            Catalog cat = catalogQuiet(context);
            for (int id : all) {
                repaintOne(context, awm, id, cat);
            }
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "repaint: " + t);
        }
    }

    /** Local-only repaint of one widget id; never throws. */
    private static void repaintOne(Context context, AppWidgetManager awm, int id, Catalog cat) {
        try {
            WidgetPrefs.Config cfg = WidgetPrefs.load(context, id);
            if (cfg != null && !cfg.stopId.isEmpty()) {
                List<Departure> cached = peekCache(id);
                if (cached == null) cached = new ArrayList<Departure>();
                boolean offline = cached.isEmpty() || cfg.lastOk <= 0;
                long nowMs = cfg.lastOk > 0 ? cfg.lastOk * 1000L : System.currentTimeMillis();
                render(context, awm, id, cfg, cat, cached, nowMs, offline);
            } else {
                renderPlaceholder(context, awm, id);
            }
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "repaintOne: " + t);
        }
    }

    // ---------------------------------------------------------------- render

    static void safeRenderPlaceholder(Context c, AppWidgetManager awm, int id) {
        try {
            renderPlaceholder(c, awm, id);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "placeholder: " + t);
        }
    }

    static void safeRender(Context c, AppWidgetManager awm, int id, WidgetPrefs.Config cfg,
                           Catalog cat, List<Departure> deps, long nowMs, boolean offline) {
        try {
            render(c, awm, id, cfg, cat, deps, nowMs, offline);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "render: " + t);
        }
    }

    /** Placeholder shown by a bound-but-unconfigured widget: tap to set up. */
    static void renderPlaceholder(Context context, AppWidgetManager awm, int id) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_divia);
        rv.setTextViewText(R.id.line_badge, "•");
        rv.setTextViewText(R.id.stop_name, context.getString(R.string.setup_title));
        rv.setTextViewText(R.id.line_dest, context.getString(R.string.setup_hint));
        for (int i = 0; i < 3; i++) rv.setViewVisibility(ROW_IDS[i], android.view.View.GONE);
        rv.setViewVisibility(R.id.empty_status, android.view.View.VISIBLE);
        rv.setTextViewText(R.id.empty_status, context.getString(R.string.setup_hint2));
        rv.setTextViewText(R.id.updated, "");

        Intent cfg = new Intent(context, ConfigActivity.class);
        cfg.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        cfg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                context, 100000 + id, cfg,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, pi);

        awm.updateAppWidget(id, rv);
    }

    /**
     * Builds the RemoteViews for one configured widget and pushes it.
     * Tolerates a null catalog (stop name falls back to the raw stop id)
     * and an empty departure list (offline / no service).
     */
    static void render(Context context, AppWidgetManager awm, int id,
                       WidgetPrefs.Config cfg, Catalog cat, List<Departure> deps,
                       long nowMs, boolean offline) {

        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_divia);

        String stopName = cat != null ? cat.stopName(cfg.stopId) : cfg.stopId;
        String badge;
        String subLine;

        Catalog.Route route = cat != null ? cat.routes.get(cfg.routeId) : null;
        String lineShort = route != null ? route.shortName : "";

        if (cfg.lineMode) {
            badge = lineShort;
            String label = cat != null ? cat.dirLabel(cfg.routeId, cfg.dir) : "";
            subLine = label.isEmpty() ? "" : context.getString(R.string.direction_fmt, label);
        } else {
            badge = deps.isEmpty() ? "•" : deps.get(0).lineShort;
            subLine = context.getString(R.string.all_lines);
        }

        rv.setTextViewText(R.id.line_badge, badge);
        rv.setTextViewText(R.id.stop_name, stopName);
        rv.setTextViewText(R.id.line_dest, subLine);

        for (int i = 0; i < 3; i++) {
            if (i < deps.size()) {
                Departure d = deps.get(i);
                String dest = cfg.lineMode ? d.headsign : d.lineShort + " · " + d.headsign;
                rv.setTextViewText(DEST_IDS[i], dest);
                rv.setTextViewText(COUNT_IDS[i], formatCountdown(context, d.timeSec - nowMs / 1000L));
                // Lateness: the times shown are already the live prediction,
                // so a delay is baked into them; the "+N'" marker and the
                // amber countdown make the deviation from the timetable
                // visible on top of that.
                boolean late = d.delaySec >= LATE_MIN_SEC;
                String clockText = formatClock(context, d.timeSec * 1000L);
                if (late) clockText = clockText + " · " + lateMarker(context, d.delaySec);
                rv.setTextViewText(CLOCK_IDS[i], clockText);
                rv.setTextColor(COUNT_IDS[i], context.getResources().getColor(
                        late ? R.color.late : R.color.countdown));
                rv.setViewVisibility(ROW_IDS[i], android.view.View.VISIBLE);
            } else {
                rv.setViewVisibility(ROW_IDS[i], android.view.View.GONE);
            }
        }

        if (deps.isEmpty()) {
            rv.setViewVisibility(R.id.empty_status, android.view.View.VISIBLE);
            rv.setTextViewText(R.id.empty_status,
                    context.getString(offline ? R.string.offline : R.string.no_departures));
        } else {
            rv.setViewVisibility(R.id.empty_status, android.view.View.GONE);
        }

        String footer;
        if (offline) {
            if (cfg.lastOk > 0) {
                footer = context.getString(R.string.offline_since_fmt,
                        formatClock(context, cfg.lastOk * 1000L));
            } else {
                footer = context.getString(R.string.offline);
            }
        } else {
            footer = context.getString(R.string.updated_fmt, formatClock(context, nowMs));
        }
        rv.setTextViewText(R.id.updated, footer);

        // Tap the widget -> instant refresh. A broadcast is used (not a
        // service) because Android 8+ forbids background service starts.
        Intent tap = new Intent(RefreshReceiver.ACTION_REFRESH);
        tap.setClass(context, RefreshReceiver.class);
        tap.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                context, id, tap,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, pi);

        awm.updateAppWidget(id, rv);
    }

    // ---------------------------------------------------------------- helpers

    /** The ids to operate on: the given subset, or every bound widget id. */
    private static int[] boundIds(Context context, AppWidgetManager awm, int[] widgetIds) {
        return widgetIds != null ? widgetIds
                : awm.getAppWidgetIds(new ComponentName(context, DiviaWidgetProvider.class));
    }

    /** Defensive copy of the cached departures for one widget (or null). */
    private static List<Departure> peekCache(int id) {
        synchronized (CACHE) {
            List<Departure> c2 = CACHE.get(id);
            return c2 != null ? new ArrayList<Departure>(c2) : null;
        }
    }

    static String formatCountdown(Context c, long deltaSec) {
        long mins = (deltaSec + 59) / 60;
        if (mins < 1) return c.getString(R.string.approaching);
        if (mins < 60) return c.getString(R.string.min_fmt, (int) mins);
        return c.getString(R.string.hour_fmt, (int) (mins / 60), (int) (mins % 60));
    }

    /** Compact lateness marker appended to the clock: "+4'" / "+2h05". */
    static String lateMarker(Context c, int delaySec) {
        if (delaySec >= 3600) {
            return c.getString(R.string.late_hour_fmt,
                    Integer.valueOf(delaySec / 3600), Integer.valueOf((delaySec % 3600 + 30) / 60));
        }
        return c.getString(R.string.late_min_fmt, Integer.valueOf((delaySec + 30) / 60));
    }

    static String formatClock(Context c, long ms) {
        DateFormat f = android.text.format.DateFormat.getTimeFormat(c);
        return f.format(new Date(ms));
    }
}
