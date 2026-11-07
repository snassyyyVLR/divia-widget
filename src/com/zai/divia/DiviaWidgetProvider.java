/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/**
 * The home screen widget provider — v2.0 clean rewrite.
 *
 * Design rules, distilled from every failure mode seen since v1.0:
 *
 *  1. NEVER let this component throw. A crash here gets the app flagged
 *     by the system and leaves half-bound widgets behind.
 *
 *  2. NEVER do slow work here. onUpdate / onAppWidgetOptionsChanged run
 *     on the main thread of our process while the launcher is watching.
 *     Everything done synchronously is local and sub-millisecond
 *     (SharedPreferences read + RemoteViews build + binder push);
 *     all network work is delegated to RefreshReceiver, which owns its
 *     own goAsync() window.
 *
 *  3. ALWAYS push a RemoteViews for every id we are told about. The
 *     launcher's initial state (and any "Can't load widget" state after
 *     a reboot or an app update) is only replaced when the app pushes
 *     fresh views — it never heals by itself.
 *
 *  4. No android:configure on the provider info (MIUI/HyperOS breaks
 *     that flow for sideloaded apps and unbinds the widget). A freshly
 *     added widget shows a "tap to set up" placeholder; tapping it
 *     opens ConfigActivity directly.
 */
public class DiviaWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager awm, int[] appWidgetIds) {
        try {
            AlarmScheduler.schedule(context);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "schedule: " + t);
        }
        try {
            // Synchronous, network-free paint for every bound id: the
            // launcher's initial "…" view is replaced immediately.
            RefreshLogic.repaintAll(context, appWidgetIds);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "onUpdate: " + t);
        }
        // Live data in the background: explicit broadcast -> the one
        // component that owns a goAsync() window for network work.
        RefreshReceiver.requestRefresh(context, appWidgetIds);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager awm,
                                          int appWidgetId, Bundle newOptions) {
        // Fires on resize AND right after the launcher binds the widget:
        // repaint immediately (local only) so the very first paint never
        // depends on the feed being reachable.
        try {
            RefreshLogic.repaintAll(context, new int[]{appWidgetId});
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "options: " + t);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            try {
                WidgetPrefs.delete(context, id);
                synchronized (RefreshLogic.CACHE) {
                    RefreshLogic.CACHE.remove(id);
                }
            } catch (Throwable t) {
                WidgetPrefs.setLastError(context, "delete: " + t);
            }
        }
    }

    @Override
    public void onEnabled(Context context) {
        try {
            AlarmScheduler.schedule(context);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "enable: " + t);
        }
    }

    @Override
    public void onDisabled(Context context) {
        try {
            AlarmScheduler.cancel(context);
        } catch (Throwable ignored) {
        }
    }
}
