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
import com.zai.divia.core.TrafficFeed;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * The shared update engine — v1.1.0.
 *
 * Downloads the feed once and renders every bound widget. All entry points
 * are called from worker threads (the alarm receiver's goAsync window, the
 * provider's synchronous bind path, the boot receiver or the config
 * screen), and every one of them is crash-proof:
 *
 *  - No exception ever escapes a public entry point; each widget gets a
 *    RemoteViews push on every call even when the network, the feed or the
 *    catalog is broken.
 *  - Local-only repaints never touch the network, so bind-time paints are
 *    sub-millisecond and safe to run synchronously inside onUpdate.
 *  - The layout pushed here (res/layout/widget_divia.xml) contains ONLY
 *    @RemoteView-whitelisted classes; the JVM harness re-asserts this on
 *    every run so a regression cannot ship again (the v1.x "Problem
 *    loading widget on every launcher" bug was a plain <View> divider
 *    inflating inside the launcher's process).
 *
 * v1.1.0 rendering: 2/3/5 rows (auto from the widget height), a bitmap line
 * badge painted in the line's official color, an optional second direction
 * (commute mode), an "horaires adaptés" chip on disruption days, and a
 * per-row tap action that opens the time-to-leave alert sheet.
 */
public final class RefreshLogic {

    private static Catalog sCatalog;
    /** Last good departures per widget id, so a failed refresh keeps the rows. */
    static final HashMap<Integer, List<Departure>> CACHE = new HashMap<Integer, List<Departure>>();
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Test seam for the JVM harness: when non-null, no network fetch happens. */
    static volatile byte[] sTestFeed;

    static final int MAX_ROWS = 5;
    private static final int[] ROW_IDS = {R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4};
    private static final int[] DEST_IDS = {R.id.dest0, R.id.dest1, R.id.dest2, R.id.dest3, R.id.dest4};
    private static final int[] COUNT_IDS = {R.id.count0, R.id.count1, R.id.count2, R.id.count3, R.id.count4};
    private static final int[] CLOCK_IDS = {R.id.clock0, R.id.clock1, R.id.clock2, R.id.clock3, R.id.clock4};
    /** v1.5.0: per-row line badges of the COMPACT layout (new ids, appended
     *  to stable-ids.txt — every other id of the compact layout is reused
     *  from the detailed one so both keep the pinned numbering). */
    private static final int[] CBADGE_IDS = {R.id.cbadge0, R.id.cbadge1, R.id.cbadge2, R.id.cbadge3, R.id.cbadge4};

    /** A vehicle this many seconds behind schedule is flagged as late. */
    static final int LATE_MIN_SEC = 120;

    /** v1.5.0: a departure is tagged "dernier" when its time-of-day is within
     *  this window of the catalog's static last departure (and no later live
     *  departure is known). Wide enough to absorb a live delay at day's end,
     *  narrow enough that the previous-but-one bus is not tagged. */
    static final int LAST_WIN_SEC = 12 * 60;

    /** v1.5.0: departures before this local hour belong to the previous
     *  service day (after-midnight service) for the last-bus comparison. */
    static final int NIGHT_CUTOFF_SEC = 6 * 3600;

    /** v1.5.0: rows fetched per side beyond the display count, so the
     *  "dernier" logic can tell a truncated list from a true last bus. */
    static final int FETCH_SLACK = 2;

    /** Traffic RSS poll interval (shared by every refresh path). */
    static final long TRAFFIC_CHECK_MS = 30L * 60L * 1000L;
    /** Only items newer than this ever ring (skip the historical backlog). */
    static final long TRAFFIC_FRESH_MS = 24L * 3600L * 1000L;
    static final int TRAFFIC_MAX_NOTIFY = 3;

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
                        cfg.lineMode ? cfg.dir : -1, MAX_ROWS + FETCH_SLACK, nowSec);
                // Commute mode: same line, opposite direction, second stop.
                if (cfg.lineMode && cfg.stopId2 != null && !cfg.stopId2.isEmpty()) {
                    List<Departure> deps2 = DiviaApi.nextDepartures(feed, cat,
                            cfg.stopId2, cfg.routeId, cfg.dir2, MAX_ROWS + FETCH_SLACK, nowSec);
                    ArrayList<Departure> merged =
                            new ArrayList<Departure>(deps.size() + deps2.size());
                    merged.addAll(deps);
                    merged.addAll(deps2);
                    deps = merged;
                }
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
            // v1.3.0: keep the recurring "time to leave" alarm aimed at
            // the first departure after the rule's target time.
            maybeArmRecurring(context, id, cfg, feed, cat, nowSec);
        }
        WidgetPrefs.setLastRefreshOk(context);

        // ---- info-trafic watch (throttled, disruption posts only).
        maybeCheckTraffic(context);
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

    // v1.5.0 layout variants (NOT color themes — those were rejected):
    // disposition 0 (detailed) = widget_divia.xml, the full layout with
    // destination + clock per row; disposition 1 (compact) =
    // widget_divia_compact.xml, "line badge + minutes only" on denser
    // 20dp rows. Both layouts share every view id except the compact
    // per-row badges (cbadge0..4), so launchers re-applying cached
    // RemoteViews keep resolving after an in-place update either way.

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
        setBadge(rv, context, "•", null);
        rv.setTextViewText(R.id.stop_name, context.getString(R.string.setup_title));
        rv.setTextViewText(R.id.line_dest, context.getString(R.string.setup_hint));
        for (int i = 0; i < MAX_ROWS; i++) rv.setViewVisibility(ROW_IDS[i], android.view.View.GONE);
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

        // v1.5.0: layout variant — compact widgets get the dense "line
        // badge + minutes" layout, detailed ones the full layout. Colors
        // always come from the XML defaults, so the launcher's day/night
        // resource switching stays intact in both dispositions.
        boolean compact = cfg.compact;
        RemoteViews rv = new RemoteViews(context.getPackageName(),
                compact ? R.layout.widget_divia_compact : R.layout.widget_divia);

        String stopName = cat != null ? cat.stopName(cfg.stopId) : cfg.stopId;
        boolean commute = cfg.lineMode && cfg.stopId2 != null && !cfg.stopId2.isEmpty();

        Catalog.Route route = cat != null ? cat.routes.get(cfg.routeId) : null;

        // ---- header badge: bitmap painted in the line's official color.
        String badgeText;
        String badgeColor;
        if (cfg.lineMode) {
            badgeText = route != null ? route.shortName : "";
            badgeColor = route != null ? route.colorHex : null;
        } else if (!deps.isEmpty()) {
            Catalog.Route r0 = cat != null ? cat.routes.get(deps.get(0).routeId) : null;
            badgeText = r0 != null ? r0.shortName : deps.get(0).lineShort;
            badgeColor = r0 != null ? r0.colorHex : null;
        } else {
            badgeText = "•";
            badgeColor = null;
        }
        setBadge(rv, context, badgeText, badgeColor);

        // ---- header texts.
        String subLine;
        if (cfg.lineMode) {
            if (cat == null) {
                subLine = "";
            } else if (commute) {
                String a = cat.dirLabel(cfg.routeId, cfg.dir);
                String b = cat.dirLabel(cfg.routeId, cfg.dir2);
                if (a.isEmpty()) subLine = b;
                else if (b.isEmpty()) subLine = a;
                else subLine = a + " / " + b;
            } else {
                String label = cat.dirLabel(cfg.routeId, cfg.dir);
                subLine = label.isEmpty() ? "" : context.getString(R.string.direction_fmt, label);
            }
        } else {
            subLine = context.getString(R.string.all_lines);
        }
        rv.setTextViewText(R.id.stop_name, stopName);
        if (!compact) rv.setTextViewText(R.id.line_dest, subLine);

        // ---- rows (split between the two directions in commute mode).
        int rows = effRows(cfg);
        int rowsA = commute ? (rows + 1) / 2 : rows;
        int rowsB = commute ? rows / 2 : 0;
        // The cached/merged list is [A-deps..., B-deps...]; b0 = index of the
        // first B departure = the number of A departures in the list
        // (defaults to deps.size() when the B leg found nothing).
        int b0 = deps.size();
        if (commute) {
            for (int i = 0; i < deps.size(); i++) {
                if (deps.get(i).direction != cfg.dir) { b0 = i; break; }
            }
        }
        long nowSec = nowMs / 1000L;
        for (int i = 0; i < MAX_ROWS; i++) {
            Departure d = null;
            if (commute) {
                if (i < rowsA) {
                    // A side: only while the entry at i is really an A departure.
                    if (i < deps.size() && i < b0) d = deps.get(i);
                } else {
                    int j = i - rowsA;
                    int k = b0 + j;
                    if (j < rowsB && k < deps.size()) d = deps.get(k);
                }
            } else if (i < rows && i < deps.size()) {
                d = deps.get(i);
            }

            if (d != null) {
                boolean late = d.delaySec >= LATE_MIN_SEC;
                // v1.5.0: "dernier" tag — this is the last known live
                // departure of its side AND its time sits on the static
                // timetable's last slot for that stop.
                boolean laterKnown;
                if (commute) {
                    laterKnown = i < rowsA ? (i + 1 < b0) : (b0 + (i - rowsA) + 1 < deps.size());
                } else {
                    laterKnown = i + 1 < deps.size();
                }
                int staticLast = cat != null
                        ? (cfg.lineMode
                            ? cat.lastDepartureSec(cfg.routeId, i < rowsA || !commute ? cfg.dir : cfg.dir2,
                                    i < rowsA || !commute ? cfg.stopId : cfg.stopId2)
                            : cat.lastDepartureSecAny(d.routeId, d.direction, cfg.stopId))
                        : -1;
                boolean dernier = !laterKnown
                        && lastStaticMatch(secOfDay(d.timeSec), staticLast);

                if (compact) {
                    // Compact row: per-row line badge + minutes countdown.
                    Catalog.Route rr = cat != null ? cat.routes.get(d.routeId) : null;
                    setRowBadge(rv, context, CBADGE_IDS[i],
                            rr != null ? rr.shortName : d.lineShort,
                            rr != null ? rr.colorHex : null);
                    rv.setTextViewText(COUNT_IDS[i], formatCountdown(context, d.timeSec - nowSec));
                    rv.setTextColor(COUNT_IDS[i], late
                            ? context.getResources().getColor(R.color.late)
                            : context.getResources().getColor(R.color.countdown));
                    // destN carries ONLY the "dernier" tag in compact.
                    if (dernier) {
                        rv.setTextViewText(DEST_IDS[i], context.getString(R.string.dernier_tag));
                        rv.setViewVisibility(DEST_IDS[i], android.view.View.VISIBLE);
                    } else {
                        rv.setViewVisibility(DEST_IDS[i], android.view.View.GONE);
                    }
                } else {
                    // Detailed row: destination, minutes, clock.
                    String dest = cfg.lineMode ? d.headsign : d.lineShort + " · " + d.headsign;
                    if (dernier) {
                        dest = dest + " · " + context.getString(R.string.dernier_tag);
                    }
                    rv.setTextViewText(DEST_IDS[i], dest);
                    rv.setTextViewText(COUNT_IDS[i], formatCountdown(context, d.timeSec - nowSec));
                    // Lateness: the times shown are already the live prediction,
                    // so a delay is baked into them; the "+N'" marker and the
                    // amber countdown make the deviation from the timetable
                    // visible on top of that.
                    String clockText = formatClock(context, d.timeSec * 1000L);
                    if (late) clockText = clockText + " · " + lateMarker(context, d.delaySec);
                    rv.setTextViewText(CLOCK_IDS[i], clockText);
                    rv.setTextColor(COUNT_IDS[i], late
                            ? context.getResources().getColor(R.color.late)
                            : context.getResources().getColor(R.color.countdown));
                }
                rv.setViewVisibility(ROW_IDS[i], android.view.View.VISIBLE);

                // Row tap -> time-to-leave alert sheet for this departure.
                Intent row = new Intent(context, NotifyActivity.class);
                row.putExtra(NotifyScheduler.EXTRA_WIDGET, id);
                row.putExtra("trip", d.tripId != null ? d.tripId : "");
                row.putExtra("time", d.timeSec);
                row.putExtra("line", d.lineShort);
                row.putExtra("head", d.headsign);
                row.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                android.app.PendingIntent rpi = android.app.PendingIntent.getActivity(
                        context, 200000 + id * 10 + i, row,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE);
                rv.setOnClickPendingIntent(ROW_IDS[i], rpi);
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

        // ---- footer, with the disruption-day chip.
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
        if (!offline && cat != null) {
            String ymd = ymdOf(nowMs);
            boolean adapted = cfg.lineMode
                    ? cat.isAdapted(ymd, cfg.routeId)
                    : cat.stopAdapted(ymd, cfg.stopId);
            if (adapted) {
                footer = context.getString(R.string.adapted_footer_fmt, footer);
            }
        }
        rv.setTextViewText(R.id.updated, footer);

        // Tap the widget (outside any row) -> instant refresh. A broadcast
        // is used (not a service) because Android 8+ forbids background
        // service starts.
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

    /**
     * v1.3.0: (re)aims the recurring alarm of one widget from live data.
     * Called after every successful refresh of a configured widget:
     *  - no rule / rule day not enabled / already rang today -> no-op;
     *  - otherwise the first departure at/after the rule's target time
     *    becomes the aim; when it slips (delay) or vanishes (cancellation)
     *    the next refresh re-aims, so the notification follows the bus.
     * A wide departure horizon (12) lets evening rules arm early.
     */
    static void maybeArmRecurring(Context c, int id, WidgetPrefs.Config cfg,
                                  byte[] feed, Catalog cat, long nowSec) {
        try {
            WidgetPrefs.Recurring r = WidgetPrefs.getRecurring(c, id);
            if (r == null || !r.enabled) return;
            if (!WidgetPrefs.dayEnabled(r.daysMask, nowSec * 1000L)) return;
            if (ymdOf(nowSec * 1000L).equals(WidgetPrefs.getRecurFired(c, id))) return;

            List<Departure> deps = DiviaApi.nextDepartures(feed, cat, cfg.stopId,
                    cfg.lineMode ? cfg.routeId : null,
                    cfg.lineMode ? cfg.dir : -1, 12, nowSec);
            Departure first = NotifyScheduler.firstAfterTarget(deps, r.targetMin, nowSec);
            if (first == null) return;

            // Stable aim: don't churn the alarm when nothing moved.
            WidgetPrefs.Alert armed = WidgetPrefs.getRecurArm(c, id);
            if (armed != null && Math.abs(armed.timeSec - first.timeSec) <= 60L) return;

            WidgetPrefs.Alert aim = new WidgetPrefs.Alert(first.tripId, first.timeSec,
                    r.leadMin, first.lineShort, first.headsign);
            WidgetPrefs.setRecurArm(c, id, aim);
            NotifyScheduler.armRecurring(c, id, aim);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "recur-arm: " + t);
        }
    }

    /** Effective row count: the user override, or auto from the widget height. */
    static int effRows(WidgetPrefs.Config cfg) {
        int r = cfg.rows;
        if (r <= 0) {
            int h = cfg.heightDp;
            if (h <= 0) h = 110;              // default 4x2-ish placement
            r = h < 100 ? 2 : (h < 155 ? 3 : 5);
        }
        if (r < 2) r = 2;
        if (r > MAX_ROWS) r = MAX_ROWS;
        return r;
    }

    private static void setBadge(RemoteViews rv, Context c, String text, String colorHex) {
        try {
            android.graphics.Bitmap bm = BadgeRender.make(c, text, colorHex);
            if (bm != null) rv.setImageViewBitmap(R.id.line_badge, bm);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "badge: " + t);
        }
    }

    /** v1.5.0: per-row badge of the compact layout (smaller pill). */
    private static void setRowBadge(RemoteViews rv, Context c, int viewId, String text, String colorHex) {
        try {
            android.graphics.Bitmap bm = BadgeRender.makeCompact(c, text, colorHex);
            if (bm != null) rv.setImageViewBitmap(viewId, bm);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "row-badge: " + t);
        }
    }

    /** yyyymmdd of the given epoch-ms in the device's local timezone. */
    static String ymdOf(long ms) {
        try {
            return new SimpleDateFormat("yyyyMMdd", Locale.US)
                    .format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Polls the info-trafic RSS at most every 30 minutes, and notifies the
     * DISRUPTION posts published since the last check (strikes, demos,
     * replaced trams…), never promo posts. On the very first run the
     * backlog is silently marked as seen.
     */
    static void maybeCheckTraffic(Context c) {
        try {
            // Test seam: the JVM harness installs a canned feed to stay
            // deterministic (and offline-proof); skip the RSS poll there.
            if (sTestFeed != null) return;
            if (!WidgetPrefs.getTrafficAlerts(c)) return;
            long now = System.currentTimeMillis();
            if (now - WidgetPrefs.getTrafficLastCheck(c) < TRAFFIC_CHECK_MS) return;
            WidgetPrefs.setTrafficLastCheck(c, now);

            List<TrafficFeed.Item> items = TrafficFeed.fetch(6000);
            if (items.isEmpty()) return;

            long lastTs = WidgetPrefs.getTrafficLastTs(c);
            long newest = lastTs;
            int notified = 0;
            for (TrafficFeed.Item it : items) {
                if (it.pubDateMs > newest) newest = it.pubDateMs;
                if (it.pubDateMs <= lastTs || it.pubDateMs < now - TRAFFIC_FRESH_MS) continue;
                if (!TrafficFeed.isDisruption(it)) continue;
                if (notified++ >= TRAFFIC_MAX_NOTIFY) break;
                Notifier.postTraffic(c, it.title, it.link);
            }
            WidgetPrefs.setTrafficLastTs(c, newest);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "traffic: " + t);
        }
    }

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

    /** v1.5.0: local seconds-of-day of an epoch-seconds timestamp. */
    static int secOfDay(long epochSec) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(epochSec * 1000L);
        return cal.get(Calendar.HOUR_OF_DAY) * 3600
                + cal.get(Calendar.MINUTE) * 60
                + cal.get(Calendar.SECOND);
    }

    /**
     * v1.5.0: does this live departure sit on the static timetable's last
     * slot? Departures in the small hours belong to the previous service
     * day and are shifted past midnight, matching the GTFS convention where
     * the last trip can be "25:30". Pure — JVM-harness tested.
     */
    static boolean lastStaticMatch(int depSecOfDay, int staticLastSec) {
        if (staticLastSec < 0) return false;
        int dep = depSecOfDay;
        if (dep < NIGHT_CUTOFF_SEC) dep += 86400;
        return Math.abs(dep - staticLastSec) <= LAST_WIN_SEC;
    }
}
