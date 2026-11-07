/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.Context;
import android.content.SharedPreferences;

/** Per-widget configuration storage. */
public final class WidgetPrefs {

    public static final class Config {
        public boolean lineMode = true;   // true: line+direction+stop, false: whole stop
        public String routeId = "";
        public int dir = 0;
        public String stopId = "";
        public long lastOk = 0;           // epoch seconds of last successful refresh
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

    public static void save(Context c, int widgetId, Config cfg) {
        prefs(c).edit()
                .putString(key(widgetId, "mode"), cfg.lineMode ? "line" : "stop")
                .putString(key(widgetId, "route"), cfg.routeId)
                .putInt(key(widgetId, "dir"), cfg.dir)
                .putString(key(widgetId, "stop"), cfg.stopId)
                .putLong(key(widgetId, "lastok"), cfg.lastOk)
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
        return cfg;
    }

    public static void setLastOk(Context c, int widgetId, long epochSec) {
        prefs(c).edit().putLong(key(widgetId, "lastok"), epochSec).apply();
    }

    public static void delete(Context c, int widgetId) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : new String[]{"mode", "route", "dir", "stop", "lastok"}) {
            e.remove(key(widgetId, k));
        }
        e.apply();
    }

    private static String key(int id, String k) {
        return "w_" + id + "_" + k;
    }

    private WidgetPrefs() {}
}
