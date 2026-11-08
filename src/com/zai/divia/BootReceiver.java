/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores the periodic refresh after a reboot, and re-paints every widget
 * right after the app has been updated (launchers often keep a stale or
 * "Can't load widget" state until the app pushes a fresh update).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        AlarmScheduler.schedule(context);
        try {
            // Re-arm every pending "time to leave" alert after the reboot.
            NotifyScheduler.rescheduleAll(context);
        } catch (Throwable ignored) {
        }
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Repaint FIRST, without the network: after an app
                    // update the launchers re-apply the RemoteViews they
                    // cached from the previous version, which crashes in
                    // their process ("Can't load widget") until we push
                    // fresh views for every bound id.
                    RefreshLogic.repaintAll(context, null);
                    RefreshLogic.refreshAll(context, null);
                } catch (Throwable t) {
                    // ignore
                } finally {
                    result.finish();
                }
            }
        }, "divia-boot").start();
    }
}
