/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.zai.divia.core.Catalog;
import com.zai.divia.core.DiviaApi;
import com.zai.divia.core.ProtoRt;

import java.util.List;

/**
 * Fires when a departure's lead time elapses:
 *  - ACTION_ALERT (v1.1.0): the one-shot alert pinned from a departure row;
 *  - ACTION_RECUR (v1.3.0): the recurring rule's alarm, armed by the last
 *    live refresh at the first departure at/after the rule's target time.
 *
 * Before notifying it re-checks the trip against the LIVE feed, so the
 * notification reflects the vehicle's actual state: still coming (updated
 * countdown + lateness marker), already gone, or vanished from the feed
 * (cancelled).
 *
 * The one-shot alert is always cleared afterwards — one alert, one
 * notification. The recurring arm is cleared too, but the RULE stays and
 * the next refresh re-arms it for the following enabled day (one
 * notification per rule per day).
 */
public class NotifyReceiver extends BroadcastReceiver {

    public static final String ACTION_ALERT = "com.zai.divia.ACTION_ALERT";
    public static final String ACTION_RECUR = "com.zai.divia.ACTION_RECUR_ALERT";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        int wid = intent != null
                ? intent.getIntExtra(NotifyScheduler.EXTRA_WIDGET, -1) : -1;
        if (wid < 0) return;
        final int widgetId = wid;
        final boolean recurring = intent != null
                && ACTION_RECUR.equals(intent.getAction());

        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (recurring) fireRecurring(context, widgetId);
                    else fire(context, widgetId);
                } catch (Throwable t) {
                    WidgetPrefs.setLastError(context, "alert: " + t);
                } finally {
                    try {
                        result.finish();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "divia-alert").start();
    }

    private void fire(Context context, int widgetId) {
        WidgetPrefs.Alert a = WidgetPrefs.getAlert(context, widgetId);
        if (a == null) return;
        WidgetPrefs.clearAlert(context, widgetId);   // one-shot
        fireAndNotify(context, widgetId, a);
    }

    private void fireRecurring(Context context, int widgetId) {
        WidgetPrefs.Alert a = WidgetPrefs.getRecurArm(context, widgetId);
        if (a == null) return;
        // One notification per rule per day: clear the arm, stamp the day,
        // keep the rule — the next refresh re-arms for the next enabled day.
        WidgetPrefs.clearRecurArm(context, widgetId);
        WidgetPrefs.setRecurFired(context, widgetId,
                RefreshLogic.ymdOf(System.currentTimeMillis()));
        fireAndNotify(context, widgetId, a);
    }

    private void fireAndNotify(Context context, int widgetId, WidgetPrefs.Alert a) {
        long nowSec = System.currentTimeMillis() / 1000L;

        // Live re-check: find the pinned trip in the current feed.
        ProtoRt.Event match = null;
        String lateTag = null;
        try {
            byte[] feed = RefreshLogic.sTestFeed != null
                    ? RefreshLogic.sTestFeed
                    : DiviaApi.fetch(DiviaApi.FEED_URL, 6000);
            Catalog cat = null;
            try {
                cat = RefreshLogic.catalog(context);
            } catch (Throwable ignored) {
            }
            WidgetPrefs.Config cfg = WidgetPrefs.load(context, widgetId);
            List<ProtoRt.Event> events = ProtoRt.parse(feed);
            ProtoRt.Event fallback = null;
            for (ProtoRt.Event e : events) {
                boolean sameTrip = a.tripId != null && a.tripId.length() > 0
                        && a.tripId.equals(e.tripId);
                boolean sameStopAndTime = cfg != null && cfg.stopId.equals(e.stopId)
                        && Math.abs(e.timeSec - a.timeSec) < 180L;
                if (sameTrip || sameStopAndTime) {
                    // Prefer the event at the widget's own stop (commute: either leg).
                    boolean atWidgetStop = cfg != null
                            && (e.stopId.equals(cfg.stopId) || e.stopId.equals(cfg.stopId2));
                    if (atWidgetStop) {
                        match = e;
                        break;
                    }
                    if (fallback == null) fallback = e;
                }
            }
            if (match == null) match = fallback;
            if (match != null && match.delaySec >= RefreshLogic.LATE_MIN_SEC) {
                lateTag = RefreshLogic.lateMarker(context, match.delaySec);
            }
        } catch (Throwable t) {
            // Offline: fall through with the last known time; the alert still
            // fires rather than vanishing silently.
            WidgetPrefs.setLastError(context, "alert-feed: " + t);
        }

        long timeSec = match != null ? match.timeSec : a.timeSec;
        long remain = timeSec - nowSec;

        if (remain > 30L) {
            int minLeft = (int) ((remain + 59L) / 60L);
            Notifier.postDepartureAlert(context, widgetId, a.line, a.headsign,
                    minLeft, RefreshLogic.formatClock(context, timeSec * 1000L), lateTag);
        } else {
            Notifier.postGone(context, widgetId, a.line, a.headsign);
        }
    }
}
