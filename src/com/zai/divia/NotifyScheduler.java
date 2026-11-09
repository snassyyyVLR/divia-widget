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

import com.zai.divia.core.Departure;

import java.util.Calendar;
import java.util.List;

/**
 * Arms / cancels / re-arms the "time to leave" alarms of one widget:
 *  - the one-shot alert pinned from a departure row (v1.1.0),
 *  - the recurring rule armed from every live refresh (v1.3.0).
 *
 * Both target NotifyReceiver, which re-checks the trip against the live
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

    /** v1.3.0: distinct action + request code so the recurring alarm and
     *  the one-shot alert of the same widget never collide. */
    private static PendingIntent piRecurring(Context c, int widgetId) {
        Intent i = new Intent(NotifyReceiver.ACTION_RECUR);
        i.setClass(c, NotifyReceiver.class);
        i.putExtra(EXTRA_WIDGET, widgetId);
        return PendingIntent.getBroadcast(c, 400000 + widgetId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** (Re)arms the one-shot alert of one widget from its stored prefs, or no-op. */
    public static void schedule(Context c, int widgetId) {
        try {
            WidgetPrefs.Alert a = WidgetPrefs.getAlert(c, widgetId);
            if (a == null) return;
            setAlertAlarm(c, widgetId, a, false);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "notify-schedule: " + t);
        }
    }

    /** (Re)arms the recurring alarm of one widget from its stored armed state (boot). */
    public static void scheduleRecurring(Context c, int widgetId) {
        try {
            WidgetPrefs.Alert a = WidgetPrefs.getRecurArm(c, widgetId);
            if (a == null) return;
            setAlertAlarm(c, widgetId, a, true);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "recur-schedule: " + t);
        }
    }

    /** v1.3.0: aims the recurring alarm of one widget at the given departure. */
    static void armRecurring(Context c, int widgetId, WidgetPrefs.Alert a) {
        setAlertAlarm(c, widgetId, a, true);
    }

    /** Cancels the recurring alarm and clears the armed state (the rule stays). */
    public static void cancelRecurring(Context c, int widgetId) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(piRecurring(c, widgetId));
        } catch (Throwable ignored) {
        }
        WidgetPrefs.clearRecurArm(c, widgetId);
    }

    private static void setAlertAlarm(Context c, int widgetId, WidgetPrefs.Alert a,
                                      boolean recurring) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = recurring ? piRecurring(c, widgetId) : pi(c, widgetId);
        am.cancel(p);

        long at = (a.timeSec - a.leadMin * 60L) * 1000L;
        long now = System.currentTimeMillis();
        if (at < now - 60000L) return;          // lead time long gone
        if (at < now) at = now + 2000L;         // inside the window: fire now-ish

        if (Build.VERSION.SDK_INT >= 31 && !canScheduleExact(am)) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60000L, p);
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    private static boolean canScheduleExact(AlarmManager am) {
        try {
            return am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cancels the alarm AND clears the stored one-shot alert. */
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

    /** Re-arms every recurring alarm (boot / app update). */
    public static void rescheduleAllRecurring(Context c) {
        for (Integer id : WidgetPrefs.recurringWidgetIds(c)) {
            scheduleRecurring(c, id.intValue());
        }
    }

    // ------------------------------------------------------------ matching

    /** Minute of day (local) of the given epoch-ms timestamp. */
    static int minuteOfDay(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    /**
     * v1.3.0: first upcoming departure at/after the rule's target minute of
     * day. Departures after midnight belong to the previous service day and
     * deliberately do not match an evening target (plain minute-of-day
     * comparison, no wrap-around).
     */
    static Departure firstAfterTarget(List<Departure> deps, int targetMin, long nowSec) {
        for (Departure d : deps) {
            if (d.timeSec <= nowSec) continue;
            if (minuteOfDay(d.timeSec * 1000L) >= targetMin) return d;
        }
        return null;
    }
}
