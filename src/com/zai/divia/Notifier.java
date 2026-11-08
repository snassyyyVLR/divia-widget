/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

/**
 * All notification posting lives here: two channels (departure alerts,
 * info-trafic alerts), tiny texts, tap actions. Permission handling is NOT
 * here: POST_NOTIFICATIONS (Android 13+) is requested in context by
 * NotifyActivity / HomeActivity / SettingsActivity; when it is missing the
 * system simply drops the notify() call — no crash.
 */
public final class Notifier {

    public static final String CHAN_DEPARTURES = "departures";
    public static final String CHAN_TRAFFIC = "trafic";

    private static final String ACTION_VIEW = "android.intent.action.VIEW";

    private Notifier() {}

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationManager n = nm(c);
                if (n != null) {
                    n.createNotificationChannel(new NotificationChannel(
                            CHAN_DEPARTURES,
                            c.getString(R.string.notif_chan_departures),
                            NotificationManager.IMPORTANCE_HIGH));
                    n.createNotificationChannel(new NotificationChannel(
                            CHAN_TRAFFIC,
                            c.getString(R.string.notif_chan_trafic),
                            NotificationManager.IMPORTANCE_DEFAULT));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** "L5 in 4 min · Université · 20:13 · +3′" heads-up notification. */
    public static void postDepartureAlert(Context c, int widgetId, String line,
                                          String headsign, int minLeft, String clockText,
                                          String lateTag) {
        try {
            ensureChannels(c);
            NotificationManager n = nm(c);
            if (n == null) return;
            String title = c.getString(R.string.notif_depart_fmt, line, Integer.valueOf(minLeft));
            StringBuilder text = new StringBuilder(headsign);
            if (clockText != null && !clockText.isEmpty()) text.append(" · ").append(clockText);
            if (lateTag != null && !lateTag.isEmpty()) text.append(" · ").append(lateTag);
            n.notify(widgetId, build(c, CHAN_DEPARTURES, title, text.toString(),
                    openApp(c, 400000 + widgetId)));
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "notify: " + t);
        }
    }

    /** The pinned departure has passed or vanished (cancelled trip). */
    public static void postGone(Context c, int widgetId, String line, String headsign) {
        try {
            ensureChannels(c);
            NotificationManager n = nm(c);
            if (n == null) return;
            String title = c.getString(R.string.notif_gone_title_fmt, line);
            n.notify(widgetId, build(c, CHAN_DEPARTURES, title, headsign,
                    openApp(c, 400000 + widgetId)));
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "notify-gone: " + t);
        }
    }

    /** One info-trafic post from information.divia.fr; tapping opens it. */
    public static void postTraffic(Context c, String title, String link) {
        try {
            ensureChannels(c);
            NotificationManager n = nm(c);
            if (n == null) return;
            PendingIntent pi = null;
            try {
                if (link != null && link.startsWith("http")) {
                    Intent open = new Intent(ACTION_VIEW);
                    open.setData(Uri.parse(link));
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    pi = PendingIntent.getActivity(c, title.hashCode(), open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                }
            } catch (Throwable ignored) {
            }
            if (pi == null) pi = openApp(c, title.hashCode());
            n.notify(500000 + (title.hashCode() & 0xFFFF), build(c, CHAN_TRAFFIC,
                    c.getString(R.string.notif_traffic_title), title, pi));
        } catch (Throwable t) {
            WidgetPrefs.setLastError(c, "notify-trafic: " + t);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static PendingIntent openApp(Context c, int requestCode) {
        Intent open = new Intent();
        open.setClassName(c, "com.zai.divia.HomeActivity");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification build(Context c, String chan, String title, String text,
                                      PendingIntent pi) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, chan)
                : new Notification.Builder(c);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notify)
                .setAutoCancel(true);
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }
}
