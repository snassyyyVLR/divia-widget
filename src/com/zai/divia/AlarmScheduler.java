/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/**
 * Periodic refresh via AlarmManager -> PendingIntent.getBroadcast on
 * RefreshReceiver. Broadcasts are used (not started services) because
 * Android 8+ forbids background apps from calling startService().
 *
 * v1.1.0: the alarm is now self-rescheduling with an ADAPTIVE cadence —
 * every 5 minutes while the network actually moves (05:30–00:30), every
 * 30 minutes during the night service gap, when no bus runs and the feed
 * only ever says "no departures". This keeps the widget honest (it still
 * refreshes, so a frozen "11 min" never survives the night) while cutting
 * ~90 % of the overnight wake-ups. A frozen stale countdown would be a
 * lie; a 30-minute heartbeat is not.
 */
public final class AlarmScheduler {

    public static final long DAY_INTERVAL_MS = 5L * 60L * 1000L;
    public static final long NIGHT_INTERVAL_MS = 30L * 60L * 1000L;

    /** Night service gap, minutes of day (local time): 00:30 → 05:30. */
    static final int NIGHT_START_MIN = 0 * 60 + 30;
    static final int NIGHT_END_MIN = 5 * 60 + 30;

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(RefreshReceiver.ACTION_ALARM);
        i.setClass(c, RefreshReceiver.class);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** v1.1 alarm: broadcast targeting the provider receiver. */
    private static PendingIntent legacyPi11(Context c) {
        Intent i = new Intent("com.zai.divia.ACTION_ALARM");
        i.setClass(c, DiviaWidgetProvider.class);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** v1.0 alarm: started service (crashed on Android 8+). */
    private static PendingIntent legacyPi10(Context c) {
        Intent i = new Intent();
        i.setClassName(c, "com.zai.divia.FetchService");
        return PendingIntent.getService(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Minutes of day (local) of the given epoch-ms timestamp. */
    static int minuteOfDay(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    /** True inside the night service gap (local time). */
    static boolean isNight(long ms) {
        int m = minuteOfDay(ms);
        return m >= NIGHT_START_MIN && m < NIGHT_END_MIN;
    }

    /** Refresh delay that applies at the given time: 5 min day / 30 min night. */
    public static long nextDelayMs(long nowMs) {
        return isNight(nowMs) ? NIGHT_INTERVAL_MS : DAY_INTERVAL_MS;
    }

    /**
     * (Re)arms the one-shot refresh alarm. Called after every refresh (the
     * receiver re-arms itself), at boot, at app update, at bind and whenever
     * the app is opened — each call simply pushes the next wakeup one
     * interval away, so a user present in front of the widget gets fast
     * refreshes, and the quiet hours stay quiet.
     */
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try { am.cancel(legacyPi10(c)); } catch (Throwable ignored) {}
        try { am.cancel(legacyPi11(c)); } catch (Throwable ignored) {}
        long now = System.currentTimeMillis();
        long at = now + nextDelayMs(now);
        try {
            am.cancel(pi(c));
            // 2-minute delivery window: inexact batching without real drift.
            am.setWindow(AlarmManager.RTC, at, 2L * 60L * 1000L, pi(c));
        } catch (Throwable t) {
            try { am.set(AlarmManager.RTC, at, pi(c)); } catch (Throwable ignored) {}
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try { am.cancel(legacyPi10(c)); } catch (Throwable ignored) {}
        try { am.cancel(legacyPi11(c)); } catch (Throwable ignored) {}
        am.cancel(pi(c));
    }

    private AlarmScheduler() {}
}
