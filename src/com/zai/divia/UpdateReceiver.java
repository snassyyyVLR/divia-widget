/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Update status relay (v1.2.0). UpdateFlow commits the downloaded APK to a
 * PackageInstaller session with a PendingIntent here; the system then talks
 * to us through this receiver:
 *
 *  - STATUS_PENDING_USER_ACTION: the EXTRA_INTENT we get IS the installer's
 *    own confirmation screen — launching it is the whole point (from a
 *    receiver context it needs FLAG_ACTIVITY_NEW_TASK);
 *  - STATUS_SUCCESS: the new version is in; clear the cached release so the
 *    home banner disappears, and confirm via a notification;
 *  - anything else: report the failure (notification + diagnostics).
 */
public class UpdateReceiver extends BroadcastReceiver {

    public static final String ACTION_STATUS = "com.zai.divia.UPDATE_STATUS";

    // PackageInstaller status codes as documented (and as every real device
    // sends them): SUCCESS=0, PENDING_USER_ACTION=1, FAILURE family=-1..-7.
    // MUST stay literals: the android.jar we compile against carries
    // non-canonical values for these fields (PENDING=-1, FAILURE=1...), so
    // referencing PackageInstaller.STATUS_* bakes the wrong numbers into
    // the dex and the updater misreads every status broadcast — the exact
    // "update-install(1): null" + "Failed to install" bug of v1.2.0/v1.3.0.
    private static final int STATUS_SUCCESS = 0;
    private static final int STATUS_PENDING_USER_ACTION = 1;

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (status == STATUS_PENDING_USER_ACTION) {
            try {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } catch (Throwable t) {
                WidgetPrefs.setLastError(context, "update-confirm: " + t);
            }
            return;
        }
        if (status == STATUS_SUCCESS) {
            WidgetPrefs.clearUpdateCache(context);
            Notifier.postUpdateInstalled(context);
            return;
        }
        // Failure family: blocked / aborted / invalid / conflict / storage /
        // incompatible — the message says which.
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        WidgetPrefs.setLastError(context, "update-install(" + status + "): " + msg);
        Notifier.postUpdateFailed(context, msg);
    }
}
