/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the explicit broadcasts used for refreshing:
 *  - ACTION_ALARM, sent by the AlarmManager every 5 minutes,
 *  - ACTION_REFRESH, sent when the user taps a configured widget, and
 *    by the provider at bind time (provider callbacks stay synchronous
 *    and local-only; the network work happens here instead).
 *
 * A goAsync() receiver is used instead of a started service because
 * Android 8+ forbids background apps from calling startService(). The
 * network fetch is capped well below the ~10s goAsync window.
 */
public class RefreshReceiver extends BroadcastReceiver {

    public static final String ACTION_REFRESH = "com.zai.divia.ACTION_REFRESH";
    public static final String ACTION_ALARM = "com.zai.divia.ACTION_ALARM";

    /**
     * Fire-and-forget request for a live refresh: an explicit broadcast
     * to this receiver. Safe to call from the main thread (e.g. from the
     * provider's onUpdate) — the work happens in this receiver's own
     * goAsync() window, never in the caller.
     */
    static void requestRefresh(Context context, int[] widgetIds) {
        try {
            Intent i = new Intent(ACTION_REFRESH);
            i.setClass(context, RefreshReceiver.class);
            if (widgetIds != null && widgetIds.length == 1) {
                i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetIds[0]);
            }
            // widgetIds == null (or more than one) -> the receiver
            // refreshes every bound widget, which is always a superset
            // of the requested ids.
            context.sendBroadcast(i);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "requestRefresh: " + t);
        }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final int[] ids;
        try {
            int extraId = intent != null
                    ? intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                            AppWidgetManager.INVALID_APPWIDGET_ID)
                    : AppWidgetManager.INVALID_APPWIDGET_ID;
            ids = extraId != AppWidgetManager.INVALID_APPWIDGET_ID
                    ? new int[]{extraId} : null;
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "onReceive: " + t);
            return; // never let a malformed intent crash the process
        }

        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    RefreshLogic.refreshAll(context, ids);
                } catch (Throwable t) {
                    // never crash the process from a background refresh
                    WidgetPrefs.setLastError(context, "refresh: " + t);
                } finally {
                    // Self-rescheduling alarm (v1.1.0): the next wakeup lands one
                    // adaptive interval away — 5 min by day, 30 min at night.
                    try {
                        AlarmScheduler.schedule(context);
                    } catch (Throwable ignored) {
                    }
                    result.finish();
                }
            }
        }, "divia-refresh").start();
    }
}
