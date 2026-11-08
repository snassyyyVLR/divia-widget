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
import android.os.Build;

/**
 * Arms / cancels / re-arms the "time to leave" alarm of one widget. The
 * alarm targets NotifyReceiver, which re-checks the trip against the live
 * feed before notifying — so a slipping or cancelled bus produces an
 * honest "in 9 min" (or "passed/cancelled") notification instead of the
 * stale number the user pinned.
 *
 * Exactness: setExactAndAllowWhileIdle when the OS allows it (pre-12, or
 * 12+ with the exact-alarm grant), otherwise a one-minute delivery window.
 * Both paths are one-shot and re-armed from prefs at boot.
 */
public final class NotifyScheduler {

    static final String EXTRA_WIDGET = "wid";

    private NotifyScheduler() {}

    private static PendingIntent pi(Context c, int widgetId) {
        Intent i = new Intent(NotifyReceiver.ACTION_ALERT);
        i.setClass(c, NotifyReceiver.class);
        i.putExtra(EXTRA_WIDGET, widgetId);
        return PendingIntent.getBroadcast(c, 300000 + widgetId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** (Re)arms the alert of one widget from its stored prefs, or no-op. */
    public static void schedule(Context c, int widgetId) {
        try {
            WidgetPrefs.Alert a = WidgetPrefs.getAlert(c, widgetId);
            if (a == null) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.cancel(pi(c, widgetId));

            long at = (a.timeSec - a.leadMin * 60L) * 1000L;
            long now = System.currentTimeMillis();
            if (at < now - 60000L) return;          // lead time long gone
            if (at < now) at = now + 2000L;         // inside the window: fire now-ish

            PendingIntent p = pi(c, widgetId);
            if (Build.VERSION.SDK_INT >= 31 && !canScheduleExact(am)) {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60000L, p);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, p);
            }
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "notify-schedule: " + t);
        }
    }

    private static boolean canScheduleExact(AlarmManager am) {
        try {
            return am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cancels the alarm AND clears the stored alert. */
    public static void cancel(Context c, int widgetId) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi(c, widgetId));
        } catch (Throwable ignored) {
        }
        WidgetPrefs.clearAlert(c, widgetId);
    }

    /** Re-arms every pending alert (boot / app update). */
    public static void rescheduleAll(Context c) {
        for (Integer id : WidgetPrefs.alertWidgetIds(c)) {
            schedule(c, id.intValue());
        }
    }
}
