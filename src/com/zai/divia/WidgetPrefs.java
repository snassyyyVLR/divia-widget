/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.Context;
import android.content.SharedPreferences;

import com.zai.divia.core.ReleaseCheck;

import java.util.ArrayList;
import java.util.Calendar;
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
        /** v1.6.0 disposition: 0 = detailed, 1 = compact, 2 = modern.
         *  −1 = stored before 1.6.0 (only the compact boolean existed) —
         *  layoutVariant() derives it on the fly. */
        public int layout = -1;
        /** v1.5.0 flag, still written for downgrade safety: an older app
         *  version reading only this boolean keeps rendering sensibly. */
        public boolean compact = false;
    }

    /** Effective disposition of a config: the 1.6.0 layout int, or the
     *  legacy compact boolean when absent (0 detailed / 1 compact / 2 modern). */
    public static int layoutVariant(WidgetPrefs.Config cfg) {
        if (cfg.layout >= 0 && cfg.layout <= 2) return cfg.layout;
        return cfg.compact ? 1 : 0;
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
                .putInt(key(widgetId, "layout"), cfg.layout)
                .putBoolean(key(widgetId, "compact"), cfg.compact)
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
        cfg.layout = p.getInt(key(widgetId, "layout"), -1);
        cfg.compact = p.getBoolean(key(widgetId, "compact"), false);
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
                "stop2", "dir2", "rows", "hdp", "compact", "layout", "style",
                "n_trip", "n_time", "n_lead", "n_line", "n_head",
                "r_on", "r_lead", "r_target", "r_days", "r_fired",
                "rn_trip", "rn_time", "rn_lead", "rn_line", "rn_head"}) {
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

    // ------------------------------------------------------ recurring alerts

    /**
     * v1.3.0: a standing "time to leave" rule on one widget. The widget's
     * own filter (line+direction+stop, or whole stop) defines which
     * departures match; the rule adds a target time, a lead time and the
     * days it applies to. Every live refresh re-aims the alarm at the
     * first departure at/after the target time — a slipping bus shifts
     * the notification instead of making it wrong.
     */
    public static final class Recurring {
        /** Monday..Friday in DAY_OF_WEEK-bit form (bit 1<<(dow-1)). */
        public static final int WEEKDAYS = 0x3E;

        public boolean enabled = true;
        public int leadMin = 5;
        /** Minute of day of the first departure to watch (485 = 08:05). */
        public int targetMin = 8 * 60;
        /** One bit per Calendar.DAY_OF_WEEK: SUN=bit0 ... SAT=bit6. */
        public int daysMask = WEEKDAYS;
    }

    /** True when the day of the given timestamp is enabled in the mask. */
    public static boolean dayEnabled(int mask, long epochMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(epochMs);
        return (mask & (1 << (cal.get(Calendar.DAY_OF_WEEK) - 1))) != 0;
    }

    /** Stores (or replaces) the recurring rule of one widget. */
    public static void setRecurring(Context c, int widgetId, Recurring r) {
        prefs(c).edit()
                .putBoolean(key(widgetId, "r_on"), r.enabled)
                .putInt(key(widgetId, "r_lead"), r.leadMin)
                .putInt(key(widgetId, "r_target"), r.targetMin)
                .putInt(key(widgetId, "r_days"), r.daysMask)
                .apply();
    }

    /** The widget's recurring rule, or null when it has none. */
    public static Recurring getRecurring(Context c, int widgetId) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(key(widgetId, "r_on"), false)) return null;
        Recurring r = new Recurring();
        r.leadMin = p.getInt(key(widgetId, "r_lead"), 5);
        r.targetMin = p.getInt(key(widgetId, "r_target"), 8 * 60);
        r.daysMask = p.getInt(key(widgetId, "r_days"), Recurring.WEEKDAYS);
        return r;
    }

    /** Removes the rule AND its armed state + fired marker. */
    public static void clearRecurring(Context c, int widgetId) {
        prefs(c).edit()
                .remove(key(widgetId, "r_on")).remove(key(widgetId, "r_lead"))
                .remove(key(widgetId, "r_target")).remove(key(widgetId, "r_days"))
                .remove(key(widgetId, "r_fired"))
                .remove(key(widgetId, "rn_trip")).remove(key(widgetId, "rn_time"))
                .remove(key(widgetId, "rn_lead")).remove(key(widgetId, "rn_line"))
                .remove(key(widgetId, "rn_head"))
                .apply();
    }

    /** yyyymmdd the rule last rang on ("" = not today). */
    public static String getRecurFired(Context c, int widgetId) {
        return prefs(c).getString(key(widgetId, "r_fired"), "");
    }

    public static void setRecurFired(Context c, int widgetId, String ymd) {
        prefs(c).edit().putString(key(widgetId, "r_fired"), ymd).apply();
    }

    /** The departure the recurring alarm currently aims at, or null. */
    public static Alert getRecurArm(Context c, int widgetId) {
        SharedPreferences p = prefs(c);
        if (!p.contains(key(widgetId, "rn_time"))) return null;
        return new Alert(
                p.getString(key(widgetId, "rn_trip"), ""),
                p.getLong(key(widgetId, "rn_time"), 0L),
                p.getInt(key(widgetId, "rn_lead"), 5),
                p.getString(key(widgetId, "rn_line"), ""),
                p.getString(key(widgetId, "rn_head"), ""));
    }

    public static void setRecurArm(Context c, int widgetId, Alert a) {
        prefs(c).edit()
                .putString(key(widgetId, "rn_trip"), a.tripId)
                .putLong(key(widgetId, "rn_time"), a.timeSec)
                .putInt(key(widgetId, "rn_lead"), a.leadMin)
                .putString(key(widgetId, "rn_line"), a.line)
                .putString(key(widgetId, "rn_head"), a.headsign)
                .apply();
    }

    public static void clearRecurArm(Context c, int widgetId) {
        prefs(c).edit()
                .remove(key(widgetId, "rn_trip")).remove(key(widgetId, "rn_time"))
                .remove(key(widgetId, "rn_lead")).remove(key(widgetId, "rn_line"))
                .remove(key(widgetId, "rn_head"))
                .apply();
    }

    /** Every widget id with an enabled recurring rule (boot re-arm). */
    public static List<Integer> recurringWidgetIds(Context c) {
        List<Integer> out = new ArrayList<Integer>(4);
        Map<String, ?> all = prefs(c).getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith("w_") || !k.endsWith("_r_on")) continue;
            if (!Boolean.TRUE.equals(e.getValue())) continue;
            try {
                out.add(Integer.valueOf(k.substring(2, k.length() - "_r_on".length())));
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

    /** v1.5.0: whether the catalog-expiry notification already rang once. */
    public static boolean getCatexpNotified(Context c) {
        return diagPrefs(c).getBoolean("catexp_notified", false);
    }

    public static void setCatexpNotified(Context c, boolean done) {
        diagPrefs(c).edit().putBoolean("catexp_notified", done).apply();
    }

    // ------------------------------------------------------ update checker

    /** Last GitHub release check time — throttles the quiet check to one per day. */
    public static long getUpdateLastCheck(Context c) {
        return diagPrefs(c).getLong("upd_check", 0L);
    }

    public static void setUpdateLastCheck(Context c, long ts) {
        diagPrefs(c).edit().putLong("upd_check", ts).apply();
    }

    /**
     * v1.6.1: path of the update APK currently handed to the installer —
     * the fallback handle when a ROM kills the PackageInstaller session
     * before its confirmation screen (UpdateReceiver / ApkProvider).
     */
    public static String getUpdateApkPath(Context c) {
        return diagPrefs(c).getString("upd_apk_path", "");
    }

    public static void setUpdateApkPath(Context c, String path) {
        diagPrefs(c).edit().putString("upd_apk_path", path == null ? "" : path).apply();
    }

    /**
     * v1.6.1: true from the session commit until PENDING_USER_ACTION or a
     * final status arrives. A failure while still true means no
     * confirmation screen was ever shown — the ROM blocked the session,
     * and UpdateReceiver falls back to the system installer.
     */
    public static boolean getUpdateConfirmPending(Context c) {
        return diagPrefs(c).getBoolean("upd_confirm", false);
    }

    public static void setUpdateConfirmPending(Context c, boolean pending) {
        diagPrefs(c).edit().putBoolean("upd_confirm", pending).apply();
    }

    /** Caches a release KNOWN to be newer than the installed version. */
    public static void cacheRelease(Context c, ReleaseCheck.Release rel) {
        diagPrefs(c).edit()
                .putString("upd_tag", rel.tag)
                .putString("upd_name", rel.name)
                .putString("upd_url", rel.pageUrl)
                .putString("upd_apk", rel.apkUrl)
                .putString("upd_apkname", rel.apkName)
                .putLong("upd_size", rel.apkSize)
                .putString("upd_notes",
                        rel.notes.length() > 800 ? rel.notes.substring(0, 800) : rel.notes)
                .putString("upd_ver", rel.versionString)
                .apply();
    }

    /** The cached newer release (banner + one-tap install), or null. */
    public static ReleaseCheck.Release getCachedRelease(Context c) {
        SharedPreferences p = diagPrefs(c);
        String tag = p.getString("upd_tag", null);
        if (tag == null) return null;
        return new ReleaseCheck.Release(
                tag,
                p.getString("upd_name", ""),
                p.getString("upd_url", ""),
                p.getString("upd_apk", ""),
                p.getString("upd_apkname", ""),
                p.getLong("upd_size", 0L),
                p.getString("upd_notes", ""),
                "");
    }

    public static void clearUpdateCache(Context c) {
        diagPrefs(c).edit()
                .remove("upd_tag").remove("upd_name").remove("upd_url")
                .remove("upd_apk").remove("upd_apkname").remove("upd_size")
                .remove("upd_notes").remove("upd_ver")
                .apply();
    }

    /**
     * v1.6.1: true when a release is cached AND still sorts above the
     * installed version. BootReceiver uses this on MY_PACKAGE_REPLACED to
     * drop the cache after any successful update, whatever installed it
     * (the system-installer fallback reports no callback of its own).
     */
    public static boolean cachedReleaseIsNewer(Context c) {
        ReleaseCheck.Release rel = getCachedRelease(c);
        if (rel == null) return false;
        String cur = "";
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            cur = pi.versionName != null ? pi.versionName : "";
        } catch (Throwable t) {
            return false;   // cannot tell — keep the cache, next check clears it
        }
        return rel.isNewerThan(cur);
    }

    private static String key(int id, String k) {
        return "w_" + id + "_" + k;
    }

    private WidgetPrefs() {}
}
