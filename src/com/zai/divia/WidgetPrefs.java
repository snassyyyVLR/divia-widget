/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Per-widget configuration storage. */
public final class WidgetPrefs {

    public static final class Config {
        public boolean lineMode = true;   // true: line+direction+stop, false: whole stop
        public String routeId = "";
        public int dir = 0;
        public String stopId = "";
        public long lastOk = 0;           // epoch seconds of last successful refresh
        // v1.1.0 fields -------------------------------------------------------
        /** Return-direction leg (commute mode): stop + direction, same route. */
        public String stopId2 = "";
        public int dir2 = 0;
        /** Rows to show: 0 = auto from the widget height, else 2/3/5. */
        public int rows = 0;
        /** Last height the launcher reported for this widget, in dp. */
        public int heightDp = 0;
    }

    /** A "time to leave" alert attached to one widget. */
    public static final class Alert {
        public final String tripId;   // "" when unknown
        public final long timeSec;    // departure the user pinned
        public final int leadMin;     // notify this many minutes before
        public final String line;
        public final String headsign;

        public Alert(String tripId, long timeSec, int leadMin, String line, String headsign) {
            this.tripId = tripId == null ? "" : tripId;
            this.timeSec = timeSec;
            this.leadMin = leadMin;
            this.line = line == null ? "" : line;
            this.headsign = headsign == null ? "" : headsign;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("divia_widgets", Context.MODE_PRIVATE);
    }

    private static SharedPreferences diagPrefs(Context c) {
        return c.getSharedPreferences("divia_diag", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------- app-level diagnostics

    /** Last error hit by a refresh/repaint, for the diagnostics panel. */
    public static void setLastError(Context c, String msg) {
        if (msg == null) return;
        if (msg.length() > 300) msg = msg.substring(0, 300);
        diagPrefs(c).edit()
                .putString("last_err", msg)
                .putLong("last_err_at", System.currentTimeMillis())
                .apply();
    }

    /** @return {message, epochMs} or null when no error was recorded. */
    public static Object[] getLastError(Context c) {
        SharedPreferences p = diagPrefs(c);
        String m = p.getString("last_err", null);
        if (m == null) return null;
        return new Object[]{m, Long.valueOf(p.getLong("last_err_at", 0L))};
    }

    public static void setLastRefreshOk(Context c) {
        diagPrefs(c).edit().putLong("last_ok_at", System.currentTimeMillis()).apply();
    }

    public static long getLastRefreshOk(Context c) {
        return diagPrefs(c).getLong("last_ok_at", 0L);
    }

    // --------------------------------------------------------- widget config

    public static void save(Context c, int widgetId, Config cfg) {
        prefs(c).edit()
                .putString(key(widgetId, "mode"), cfg.lineMode ? "line" : "stop")
                .putString(key(widgetId, "route"), cfg.routeId)
                .putInt(key(widgetId, "dir"), cfg.dir)
                .putString(key(widgetId, "stop"), cfg.stopId)
                .putLong(key(widgetId, "lastok"), cfg.lastOk)
                .putString(key(widgetId, "stop2"), cfg.stopId2)
                .putInt(key(widgetId, "dir2"), cfg.dir2)
                .putInt(key(widgetId, "rows"), cfg.rows)
                .putInt(key(widgetId, "hdp"), cfg.heightDp)
                .apply();
    }

    /** Returns null when the widget has never been configured. */
    public static Config load(Context c, int widgetId) {
        SharedPreferences p = prefs(c);
        if (!p.contains(key(widgetId, "mode"))) return null;
        Config cfg = new Config();
        cfg.lineMode = "line".equals(p.getString(key(widgetId, "mode"), "line"));
        cfg.routeId = p.getString(key(widgetId, "route"), "");
        cfg.dir = p.getInt(key(widgetId, "dir"), 0);
        cfg.stopId = p.getString(key(widgetId, "stop"), "");
        cfg.lastOk = p.getLong(key(widgetId, "lastok"), 0);
        cfg.stopId2 = p.getString(key(widgetId, "stop2"), "");
        cfg.dir2 = p.getInt(key(widgetId, "dir2"), 0);
        cfg.rows = p.getInt(key(widgetId, "rows"), 0);
        cfg.heightDp = p.getInt(key(widgetId, "hdp"), 0);
        return cfg;
    }

    public static void setLastOk(Context c, int widgetId, long epochSec) {
        prefs(c).edit().putLong(key(widgetId, "lastok"), epochSec).apply();
    }

    /** Stores the widget height (dp) reported by the launcher on resize. */
    public static void setHeightDp(Context c, int widgetId, int heightDp) {
        prefs(c).edit().putInt(key(widgetId, "hdp"), heightDp).apply();
    }

    public static void delete(Context c, int widgetId) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : new String[]{"mode", "route", "dir", "stop", "lastok",
                "stop2", "dir2", "rows", "hdp",
                "n_trip", "n_time", "n_lead", "n_line", "n_head"}) {
            e.remove(key(widgetId, k));
        }
        e.apply();
    }

    // ------------------------------------------------------ departure alerts

    /** Stores (or replaces) the alert of one widget. */
    public static void setAlert(Context c, int widgetId, Alert a) {
        prefs(c).edit()
                .putString(key(widgetId, "n_trip"), a.tripId)
                .putLong(key(widgetId, "n_time"), a.timeSec)
                .putInt(key(widgetId, "n_lead"), a.leadMin)
                .putString(key(widgetId, "n_line"), a.line)
                .putString(key(widgetId, "n_head"), a.headsign)
                .apply();
    }

    /** The widget's pending alert, or null. */
    public static Alert getAlert(Context c, int widgetId) {
        SharedPreferences p = prefs(c);
        if (!p.contains(key(widgetId, "n_time"))) return null;
        return new Alert(
                p.getString(key(widgetId, "n_trip"), ""),
                p.getLong(key(widgetId, "n_time"), 0L),
                p.getInt(key(widgetId, "n_lead"), 5),
                p.getString(key(widgetId, "n_line"), ""),
                p.getString(key(widgetId, "n_head"), ""));
    }

    public static void clearAlert(Context c, int widgetId) {
        prefs(c).edit()
                .remove(key(widgetId, "n_trip"))
                .remove(key(widgetId, "n_time"))
                .remove(key(widgetId, "n_lead"))
                .remove(key(widgetId, "n_line"))
                .remove(key(widgetId, "n_head"))
                .apply();
    }

    /** Every widget id that currently has a pending alert (for boot re-arm). */
    public static List<Integer> alertWidgetIds(Context c) {
        List<Integer> out = new ArrayList<Integer>(4);
        Map<String, ?> all = prefs(c).getAll();
        for (String k : all.keySet()) {
            // w_<id>_n_time
            if (k == null || !k.startsWith("w_") || !k.endsWith("_n_time")) continue;
            try {
                out.add(Integer.valueOf(k.substring(2, k.length() - "_n_time".length())));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------ trafic alerts

    public static boolean getTrafficAlerts(Context c) {
        return diagPrefs(c).getBoolean("trafic_on", true);
    }

    public static void setTrafficAlerts(Context c, boolean on) {
        diagPrefs(c).edit().putBoolean("trafic_on", on).apply();
    }

    /** Newest item pubDate already seen (only fresh items trigger a notify). */
    public static long getTrafficLastTs(Context c) {
        return diagPrefs(c).getLong("trafic_last_ts", 0L);
    }

    public static void setTrafficLastTs(Context c, long ts) {
        diagPrefs(c).edit().putLong("trafic_last_ts", ts).apply();
    }

    /** Last RSS check time — throttles the feed poll to one per 30 min. */
    public static long getTrafficLastCheck(Context c) {
        return diagPrefs(c).getLong("trafic_last_check", 0L);
    }

    public static void setTrafficLastCheck(Context c, long ts) {
        diagPrefs(c).edit().putLong("trafic_last_check", ts).apply();
    }

    /** Whether the POST_NOTIFICATIONS permission was already requested once. */
    public static boolean getNotifAsked(Context c) {
        return diagPrefs(c).getBoolean("notif_asked", false);
    }

    public static void setNotifAsked(Context c, boolean asked) {
        diagPrefs(c).edit().putBoolean("notif_asked", asked).apply();
    }

    private static String key(int id, String k) {
        return "w_" + id + "_" + k;
    }

    private WidgetPrefs() {}
}
