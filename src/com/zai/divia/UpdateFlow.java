/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Toast;

import com.zai.divia.core.DiviaApi;
import com.zai.divia.core.ReleaseCheck;
import com.zai.divia.core.ReleaseCheck.Release;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * In-app updater glue (v1.2.0). The pure logic lives in core/ReleaseCheck;
 * this class owns the Android side:
 *
 *  - a quiet once-a-day check that caches the newest release (HomeActivity
 *    then shows a banner),
 *  - a manual "check now" from Settings,
 *  - the install: download the APK asset to the cache dir, write it into a
 *    framework PackageInstaller session and commit — Android then shows its
 *    own confirmation dialog. No FileProvider, no DownloadManager, no
 *    dependencies: the APK is ~220 KB so it is fetched to memory in one
 *    shot and streamed from there.
 *
 * Signature note: the repo ships one shared debug key, so a release built
 * by build.sh installs right over the previous one.
 */
public final class UpdateFlow {

    /** Quiet check cadence (manual checks are not throttled). */
    private static final long QUIET_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private UpdateFlow() {}

    /** Current versionName, "" when PackageManager hiccups. */
    public static String currentVersionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** True when the cached/pinned release sorts above the installed one. */
    static boolean isNewerThan(Context c, Release rel) {
        return rel != null && rel.isNewerThan(currentVersionName(c));
    }

    // ------------------------------------------------------------ quiet check

    /**
     * Background check, throttled to once a day. Caches the newest release
     * (or clears the cache when we are up to date); failures are recorded in
     * the diagnostics and otherwise silent. Always posts onUiUpdate back to
     * the activity thread afterwards (null → skip).
     */
    public static void maybeCheckQuietly(final Activity activity, final Runnable onUiUpdate) {
        final Context ctx = activity.getApplicationContext();
        if (System.currentTimeMillis() - WidgetPrefs.getUpdateLastCheck(ctx)
                < QUIET_INTERVAL_MS) {
            return;   // checked recently (a previous run also posts nothing)
        }
        WidgetPrefs.setUpdateLastCheck(ctx, System.currentTimeMillis());
        new Thread(new Runnable() {
            public void run() {
                try {
                    Release rel = ReleaseCheck.check(8000);
                    if (isNewerThan(ctx, rel)) WidgetPrefs.cacheRelease(ctx, rel);
                    else WidgetPrefs.clearUpdateCache(ctx);
                } catch (Exception e) {
                    // Offline / no release yet / rate-limited: never nag.
                    WidgetPrefs.setLastError(ctx, "update-check: " + e);
                }
                if (onUiUpdate != null) {
                    new Handler(ctx.getMainLooper()).post(onUiUpdate);
                }
            }
        }, "divia-update-check").start();
    }

    // ------------------------------------------------------------ manual check

    /** "Check for updates" button: spinner → result dialog. */
    public static void checkNow(final Activity activity) {
        final Context ctx = activity;
        final ProgressDialog pd;
        try {
            pd = ProgressDialog.show(activity,
                    activity.getString(R.string.update_check),
                    activity.getString(R.string.update_checking), true, true);
        } catch (Throwable t) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                Release rel = null;
                String err = null;
                try {
                    rel = ReleaseCheck.check(10000);
                } catch (Exception e) {
                    err = String.valueOf(e);
                }
                final Release r = rel;
                final String error = err;
                new Handler(ctx.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (activity.isFinishing()) return;
                        if (error != null) {
                            showCheckFailed(activity, error);
                        } else if (isNewerThan(activity, r)) {
                            WidgetPrefs.cacheRelease(activity, r);
                            showInstallDialog(activity, r);
                        } else {
                            String cur = currentVersionName(activity);
                            new AlertDialog.Builder(activity)
                                    .setTitle(R.string.update_check)
                                    .setMessage(activity.getString(
                                            R.string.update_up_to_date_fmt, cur))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        }
                    }
                });
            }
        }, "divia-update-now").start();
    }

    private static void showCheckFailed(Activity activity, String error) {
        String msg = activity.getString(R.string.update_check_fail_fmt, error);
        if (error.contains("404")) {
            // Repo slug typo / repo private / no release published yet.
            msg += "\n\n" + activity.getString(
                    R.string.update_repo_missing, ReleaseCheck.REPO);
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_check)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Banner tap: install from the cached release, or check when none is cached. */
    public static void bannerTapped(Activity activity) {
        Release cached = WidgetPrefs.getCachedRelease(activity);
        if (cached != null && isNewerThan(activity, cached)) {
            showInstallDialog(activity, cached);
        } else {
            checkNow(activity);
        }
    }

    // ------------------------------------------------------------ the install

    /** "Version X available" dialog: install / later / view on GitHub. */
    public static void showInstallDialog(final Activity activity, final Release rel) {
        String message = rel.notes.isEmpty()
                ? rel.pageUrl.isEmpty() ? rel.tag : rel.pageUrl
                : rel.notes;
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(
                        R.string.update_available_title_fmt, rel.versionString))
                .setMessage(message)
                .setPositiveButton(R.string.update_install,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                downloadAndInstall(activity, rel);
                            }
                        })
                .setNeutralButton(R.string.update_view_github,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                openPage(activity, rel.pageUrl.isEmpty()
                                        ? ReleaseCheck.RELEASES_PAGE : rel.pageUrl);
                            }
                        })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    /**
     * Download the APK asset into the cache dir and hand it to the
     * PackageInstaller. The launcher's own confirmation dialog closes the
     * flow (UpdateReceiver relays it and posts the final result).
     */
    static void downloadAndInstall(final Activity activity, final Release rel) {
        final Context ctx = activity;
        if (rel.apkUrl.isEmpty()) {
            // Release without an .apk asset: fall back to the browser.
            Toast.makeText(activity, R.string.update_no_apk, Toast.LENGTH_LONG).show();
            openPage(activity, rel.pageUrl.isEmpty()
                    ? ReleaseCheck.RELEASES_PAGE : rel.pageUrl);
            return;
        }
        // Android 8+: the per-app "install unknown apps" allowance gates
        // every third-party self-install. Guide the user to it first.
        if (Build.VERSION.SDK_INT >= 26 && !canInstallUnknown(ctx)) {
            explainUnknownSources(activity, rel);
            return;
        }
        final ProgressDialog pd;
        try {
            pd = ProgressDialog.show(activity,
                    activity.getString(R.string.update_check),
                    activity.getString(R.string.update_downloading), true, true);
        } catch (Throwable t) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                File apk = null;
                try {
                    byte[] body = DiviaApi.fetch(rel.apkUrl, 60000);
                    // ZIP magic: a redirected error page must never reach
                    // the installer as an "APK".
                    if (body.length < 50000
                            || body[0] != 'P' || body[1] != 'K') {
                        throw new IOException("not an APK (" + body.length + " bytes)");
                    }
                    apk = writeApk(ctx, rel.tag, body);
                    commitInstall(ctx, apk);
                } catch (Exception e) {
                    error = String.valueOf(e);
                    if (apk != null) try { apk.delete(); } catch (Throwable ignored) { }
                }
                final String err = error;
                new Handler(ctx.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try { pd.dismiss(); } catch (Throwable ignored) { }
                        if (activity.isFinishing()) return;
                        if (err != null) {
                            new AlertDialog.Builder(activity)
                                    .setTitle(R.string.update_check)
                                    .setMessage(activity.getString(
                                            R.string.update_dl_fail_fmt, err))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        } else {
                            Toast.makeText(activity, R.string.update_confirm,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "divia-update-dl").start();
    }

    /** Android 8+ "install unknown apps" gate for this app. */
    private static boolean canInstallUnknown(Context ctx) {
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return true;   // exotic ROM: let the session path try anyway
        }
    }

    /** Explains the one-time allowance and deep-links to its toggle. */
    private static void explainUnknownSources(final Activity activity, final Release rel) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_check)
                .setMessage(R.string.update_unknown_sources)
                .setPositiveButton(R.string.update_open_setting,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                try {
                                    activity.startActivity(new Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:" + activity.getPackageName())));
                                } catch (Throwable t) {
                                    try {
                                        activity.startActivity(new Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:" + activity.getPackageName())));
                                    } catch (Throwable t2) {
                                        try {
                                            activity.startActivity(new Intent(
                                                    Settings.ACTION_APPLICATION_SETTINGS));
                                        } catch (Throwable t3) { }
                                    }
                                }
                            }
                        })
                .setNegativeButton(R.string.update_retry,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                downloadAndInstall(activity, rel);
                            }
                        })
                .show();
    }

    // ------------------------------------------------------------ plumbing

    /** Wipes older update APKs, writes the new one into the cache dir. */
    static File writeApk(Context ctx, String tag, byte[] body) throws IOException {
        File dir = ctx.getCacheDir();
        File[] olds = dir.listFiles();
        if (olds != null) {
            for (File f : olds) {
                String n = f.getName();
                if (n.startsWith("update-") && n.endsWith(".apk")) f.delete();
            }
        }
        File out = new File(dir, "update-"
                + tag.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk");
        FileOutputStream fo = new FileOutputStream(out);
        try {
            fo.write(body);
        } finally {
            fo.close();
        }
        return out;
    }

    /**
     * Streams the APK into a PackageInstaller session and commits it with a
     * PendingIntent back into UpdateReceiver: first callback carries the
     * user-confirmation intent (which the receiver launches), the second
     * carries success/failure.
     */
    static void commitInstall(Context ctx, File apk) throws IOException {
        try {
            PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(ctx.getPackageName());   // update-in-place
            int sid = installer.createSession(sp);
            PackageInstaller.Session session = installer.openSession(sid);
            try {
                OutputStream os = session.openWrite("divia-update", 0, apk.length());
                FileInputStream in = new FileInputStream(apk);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                } finally {
                    in.close();
                    os.close();
                }
                Intent status = new Intent(ctx, UpdateReceiver.class);
                status.setAction(UpdateReceiver.ACTION_STATUS);
                int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23
                                ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
                android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                        ctx, 100000 + sid, status, flags);
                session.commit(pi.getIntentSender());
            } finally {
                session.close();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // PackageManager.NameNotFoundException & friends are checked
            // subclasses of Exception; rethrow as IOException for one handler.
            throw new IOException(String.valueOf(e));
        }
    }

    private static void openPage(Activity activity, String url) {
        try {
            Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(open);
        } catch (Throwable t) {
            Toast.makeText(activity, String.valueOf(t), Toast.LENGTH_LONG).show();
        }
    }
}
