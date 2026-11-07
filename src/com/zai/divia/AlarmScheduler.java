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

/**
 * Periodic 5-minute refresh via AlarmManager -> PendingIntent.getBroadcast
 * on RefreshReceiver. Broadcasts are used (not started services) because
 * Android 8+ forbids background apps from calling startService().
 */
public final class AlarmScheduler {

    public static final long INTERVAL_MS = 5L * 60L * 1000L;

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

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try { am.cancel(legacyPi10(c)); } catch (Throwable ignored) {}
        try { am.cancel(legacyPi11(c)); } catch (Throwable ignored) {}
        am.setRepeating(AlarmManager.RTC,
                System.currentTimeMillis() + INTERVAL_MS, INTERVAL_MS, pi(c));
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
