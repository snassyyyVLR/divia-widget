/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Time to leave" sheet, opened by tapping a departure row on the widget.
 * A minimal dialog-themed activity (programmatic UI, no layout file): it
 * shows the pinned departure and offers a lead time (3 / 5 / 10 minutes).
 *
 * The alert is stored per widget (setting a new one replaces the old) and
 * fired by NotifyReceiver, which re-checks the trip against the live feed
 * before notifying — so a slipping bus yields an updated countdown, and a
 * cancelled one an honest "passed / cancelled" notice.
 *
 * The Android 13 POST_NOTIFICATIONS permission is requested in-context,
 * exactly when the user asks for an alert.
 */
public class NotifyActivity extends Activity {

    public static final String EXTRA_TRIP = "trip";
    public static final String EXTRA_TIME = "time";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_HEAD = "head";

    private static final int[] LEADS = {3, 5, 10};
    private static final int REQ_PERM = 41;

    private int widgetId = -1;
    private String tripId = "";
    private long timeSec = 0;
    private String line = "";
    private String headsign = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        widgetId = getIntent() != null
                ? getIntent().getIntExtra(NotifyScheduler.EXTRA_WIDGET, -1) : -1;
        tripId = getIntent() != null ? getIntent().getStringExtra(EXTRA_TRIP) : "";
        timeSec = getIntent() != null ? getIntent().getLongExtra(EXTRA_TIME, 0L) : 0L;
        line = getIntent() != null ? getIntent().getStringExtra(EXTRA_LINE) : "";
        headsign = getIntent() != null ? getIntent().getStringExtra(EXTRA_HEAD) : "";
        if (tripId == null) tripId = "";
        if (line == null) line = "";
        if (headsign == null) headsign = "";

        if (widgetId < 0 || timeSec <= 0) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.notify_title);
        title.setTextSize(18);
        title.setPaintFlags(android.graphics.Paint.FAKE_BOLD_TEXT_FLAG);
        title.setTextColor(0xFF1B1D22);
        root.addView(title);

        TextView summary = new TextView(this);
        String clock = RefreshLogic.formatClock(this, timeSec * 1000L);
        summary.setText(line + " · " + headsign + " · " + clock);
        summary.setTextSize(14);
        summary.setPadding(0, pad / 2, 0, pad);
        summary.setTextColor(0xFF5C6470);
        root.addView(summary);

        TextView leadLabel = new TextView(this);
        leadLabel.setText(R.string.notify_lead_label);
        leadLabel.setTextSize(12);
        leadLabel.setTextColor(0xFF8A929C);
        root.addView(leadLabel);

        LinearLayout leads = new LinearLayout(this);
        leads.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        for (final int lead : LEADS) {
            Button b = new Button(this);
            b.setText(getString(R.string.notify_lead_fmt, lead));
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    arm(lead);
                }
            });
            leads.addView(b);
        }
        root.addView(leads);

        WidgetPrefs.Alert existing = WidgetPrefs.getAlert(this, widgetId);
        if (existing != null) {
            Button cancel = new Button(this);
            cancel.setText(R.string.notify_cancel);
            cancel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    NotifyScheduler.cancel(NotifyActivity.this, widgetId);
                    Toast.makeText(NotifyActivity.this, R.string.notify_cancel_toast,
                            Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
            root.addView(cancel, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        Button close = new Button(this);
        close.setText(R.string.notify_close);
        close.setBackgroundColor(0x00000000);
        close.setPadding(0, pad / 2, 0, 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(close, cp);

        setContentView(root);
    }

    private void arm(int leadMin) {
        // Android 13+: ask for POST_NOTIFICATIONS exactly when the user
        // asks for an alert. Result (granted or not) is handled inline.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                    WidgetPrefs.setNotifAsked(this, true);
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                            REQ_PERM);
                    // The alert is armed in onRequestPermissionsResult either way.
                    pendingLead = leadMin;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        armNow(leadMin);
    }

    private int pendingLead = -1;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode != REQ_PERM || pendingLead < 0) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        int lead = pendingLead;
        pendingLead = -1;
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        armNow(lead);
        if (!granted) {
            Toast.makeText(this, R.string.notify_perm_hint, Toast.LENGTH_LONG).show();
        }
    }

    private void armNow(int leadMin) {
        WidgetPrefs.setAlert(this, widgetId,
                new WidgetPrefs.Alert(tripId, timeSec, leadMin, line, headsign));
        NotifyScheduler.schedule(this, widgetId);
        Toast.makeText(this, getString(R.string.notify_set_toast_fmt, leadMin),
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
