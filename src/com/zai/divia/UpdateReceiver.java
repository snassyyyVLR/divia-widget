/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;

/**
 * Update status relay (v1.2.0). UpdateFlow commits the downloaded APK to a
 * PackageInstaller session with a PendingIntent here; the system then talks
 * to us through this receiver.
 *
 * v1.6.1 — the ROM-blocked-session fallback. Some OEM ROMs (notably Xiaomi
 * HyperOS/MIUI with "MIUI optimization" on) kill a third-party app's
 * PackageInstaller session BEFORE the system confirmation screen appears:
 * the first callback already carries the generic STATUS_FAILURE (-1) with
 * an empty message, so the user never sees any install popup and every
 * self-update "fails" instantly. When that happens — a failure while our
 * confirmation screen was never shown — the receiver re-launches the
 * install the classic way: ACTION_VIEW on the downloaded APK exposed
 * through ApkProvider's content:// URI, which the ROM's own installer UI
 * handles (the same path as a manual sideload). A tap-to-install
 * notification is posted alongside as the durable handle, in case the ROM
 * also blocks the activity start from the receiver.
 *
 * The callback PendingIntent itself is created FLAG_MUTABLE (v1.5.2): the
 * status arrives as an intent fill-in, which an immutable PendingIntent
 * silently drops.
 *
 * Extras are read with LITERAL strings and statuses compared against
 * LITERAL ints: the android.jar this project compiles against carries
 * non-canonical STATUS_* values (PENDING=-1, FAILURE=+1…), and
 * EXTRA_LEGACY_STATUS is not part of the public API at all. Canonical
 * platform values: SUCCESS=0, PENDING_USER_ACTION=1, FAILURE family
 * -1..-7 (message may be null; legacy status carries the
 * PackageManager.INSTALL_FAILED_* code when the ROM bothers to set it).
 */
public class UpdateReceiver extends BroadcastReceiver {

    public static final String ACTION_STATUS = "com.zai.divia.UPDATE_STATUS";

    // Literals on purpose — see the class comment.
    private static final String EXTRA_STATUS = "android.content.pm.extra.STATUS";
    private static final String EXTRA_STATUS_MESSAGE =
            "android.content.pm.extra.STATUS_MESSAGE";
    private static final String EXTRA_LEGACY_STATUS =
            "android.content.pm.extra.LEGACY_STATUS";
    private static final String EXTRA_INTENT = "android.intent.extra.INTENT";

    private static final int STATUS_SUCCESS = 0;
    private static final int STATUS_PENDING_USER_ACTION = 1;
    private static final int STATUS_FAILURE_ABORTED = -3;

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(EXTRA_STATUS, -1);

        if (status == STATUS_PENDING_USER_ACTION) {
            // The system confirmation screen exists — from now on, a
            // failure is a real answer from the installer, not a blocked
            // session (see the fallback below).
            WidgetPrefs.setUpdateConfirmPending(context, false);
            try {
                Intent confirm = intent.getParcelableExtra(EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                } else {
                    WidgetPrefs.setLastError(context,
                            "update-confirm: pending status without EXTRA_INTENT");
                }
            } catch (Throwable t) {
                WidgetPrefs.setLastError(context, "update-confirm: " + t);
            }
            return;
        }

        if (status == STATUS_SUCCESS) {
            WidgetPrefs.clearUpdateCache(context);
            Notifier.cancelUpdateTapToInstall(context);
            cleanApk(context);
            Notifier.postUpdateInstalled(context);
            return;
        }

        // Failure family: -1 generic … -7 incompatible.
        String msg = intent.getStringExtra(EXTRA_STATUS_MESSAGE);
        int legacy = intent.getIntExtra(EXTRA_LEGACY_STATUS, Integer.MIN_VALUE);
        boolean confirmShown = !WidgetPrefs.getUpdateConfirmPending(context);
        WidgetPrefs.setUpdateConfirmPending(context, false);

        String fallback = null;
        if (!confirmShown) {
            // Killed before its own popup: the ROM blocked the session.
            fallback = fallbackToSystemInstaller(context);
        }
        String diag = "update-install(status=" + status
                + (legacy != Integer.MIN_VALUE ? ", legacy=" + legacy : "")
                + (msg != null && !msg.isEmpty() ? ", msg=" + msg : "")
                + ", confirmShown=" + confirmShown + ")"
                + (fallback != null ? " -> " + fallback : "");
        WidgetPrefs.setLastError(context, diag);

        // Only nag when there is nothing to tap: the user canceled the
        // system dialog themselves (ABORTED after a shown confirm), or the
        // fallback had no APK to work with.
        if (fallback == null && !(confirmShown && status == STATUS_FAILURE_ABORTED)) {
            Notifier.postUpdateFailed(context, msg != null && !msg.isEmpty()
                    ? msg : "status " + status);
        }
    }

    /**
     * Re-launches the failed install as ACTION_VIEW on the cached APK.
     *
     * @return null when there was no APK to fall back on, else a short
     *         note for the diagnostics ("system installer opened" /
     *         "tap-to-install notification posted").
     */
    private static String fallbackToSystemInstaller(Context context) {
        File apk = cachedApk(context);
        if (apk == null) return null;
        Uri uri = ApkProvider.uriFor(apk);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, ApkProvider.APK_MIME);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        String result = "tap-to-install notification posted";
        try {
            // The app is normally foreground (the user tapped "install"
            // seconds ago), so starting the installer from the receiver
            // is allowed; if the ROM blocks it anyway, the notification
            // below is the durable handle.
            context.startActivity(view);
            result = "system installer opened";
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "update-fallback: " + t);
        }
        try {
            Notifier.postUpdateTapToInstall(context, uri);
        } catch (Throwable t) {
            WidgetPrefs.setLastError(context, "update-fallback-notify: " + t);
        }
        return result;
    }

    /** The cached update APK, when it is still ours and readable. */
    private static File cachedApk(Context context) {
        try {
            String path = WidgetPrefs.getUpdateApkPath(context);
            if (path == null || path.isEmpty()) return null;
            File apk = new File(path);
            String name = apk.getName();
            if (!name.startsWith("update-") || !name.endsWith(".apk")) return null;
            File dir = context.getCacheDir();
            if (apk.getParentFile() == null || !apk.getParentFile().equals(dir)) return null;
            if (!apk.isFile() || !apk.canRead()) return null;
            return apk;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Drops the cached APK and its path once the install is done. */
    private static void cleanApk(Context context) {
        try {
            String path = WidgetPrefs.getUpdateApkPath(context);
            if (path != null && !path.isEmpty()) new File(path).delete();
        } catch (Throwable ignored) {
        }
        WidgetPrefs.setUpdateApkPath(context, "");
    }
}
