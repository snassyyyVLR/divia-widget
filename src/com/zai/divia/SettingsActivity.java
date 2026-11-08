/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zai.divia.core.Catalog;
import com.zai.divia.core.Departure;
import com.zai.divia.core.DiviaApi;
import com.zai.divia.core.ProtoRt;

import java.util.List;

/**
 * Settings screen (v2.1): everything that used to clutter the old home
 * screen now lives here, as clean label/value cards:
 *
 *  - Diagnostics: version, launcher, bound/configured widgets + ids,
 *    last successful refresh, last error, catalog status. When no widget
 *    is bound at all, a hint row explains what that means.
 *  - Actions: refresh all widgets (repaint + live fetch), test the data
 *    feed (result dialog), repair "Can't load widget" cells.
 *  - About: data source and catalog validity.
 */
public class SettingsActivity extends Activity {

    private LinearLayout diagRows;
    private LinearLayout aboutRows;
    private Button refreshNowBtn;
    private Button testFeedBtn;
    private Button repairBtn;
    private TextView trafficValue;

    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        diagRows = (LinearLayout) findViewById(R.id.diag_rows);
        aboutRows = (LinearLayout) findViewById(R.id.about_rows);
        refreshNowBtn = (Button) findViewById(R.id.btn_refresh_now);
        testFeedBtn = (Button) findViewById(R.id.btn_test_feed);
        repairBtn = (Button) findViewById(R.id.btn_repair_launcher);
        trafficValue = (TextView) findViewById(R.id.traffic_value);

        findViewById(R.id.traffic_row).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleTrafficAlerts();
            }
        });
        updateTrafficValue();

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        refreshNowBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Instant repaint (no network) first, then the live refresh.
                new Thread(new Runnable() {
                    public void run() {
                        RefreshLogic.repaintAll(SettingsActivity.this, null);
                    }
                }, "divia-repaint").start();
                RefreshLogic.kick(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this,
                        R.string.diag_refresh_started, Toast.LENGTH_SHORT).show();
                refreshDiagSoon(2000);
            }
        });

        testFeedBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runFeedTest();
            }
        });

        repairBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showRepairDialog();
            }
        });

        rebuildAbout();
        refreshDiag();
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshDiag(); // values may have changed since we were last visible
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // --------------------------------------------------------- notifications

    /** v1.1.0: info-trafic notification toggle (asks for the Android 13
     *  notification permission exactly when the user switches it on). */
    private void toggleTrafficAlerts() {
        boolean on = !WidgetPrefs.getTrafficAlerts(this);
        WidgetPrefs.setTrafficAlerts(this, on);
        if (on) {
            WidgetPrefs.setTrafficLastCheck(this, 0L);   // poll again soon
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        WidgetPrefs.setNotifAsked(this, true);
                        requestPermissions(
                                new String[]{"android.permission.POST_NOTIFICATIONS"}, 8);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        updateTrafficValue();
    }

    private void updateTrafficValue() {
        trafficValue.setText(WidgetPrefs.getTrafficAlerts(this)
                ? R.string.traffic_on : R.string.traffic_off);
    }

    // ------------------------------------------------------------------ rows

    private void addRow(LinearLayout container, String label, String value) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.row_setting, container, false);
        ((TextView) row.findViewById(R.id.setting_label)).setText(label);
        ((TextView) row.findViewById(R.id.setting_value)).setText(value);
        container.addView(row);
    }

    private void addRowRes(LinearLayout container, int labelRes, String value) {
        addRow(container, getString(labelRes), value);
    }

    /** Full-width note row (small text, no label column). */
    private void addNote(LinearLayout container, String text) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.row_setting, container, false);
        TextView label = (TextView) row.findViewById(R.id.setting_label);
        TextView value = (TextView) row.findViewById(R.id.setting_value);
        label.setVisibility(View.GONE);
        value.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        value.setGravity(android.view.Gravity.START);
        value.setTextSize(12);
        value.setTextColor(getResources().getColor(R.color.txt_sub));
        value.setText(text);
        container.addView(row);
    }

    // ------------------------------------------------------------ diagnostics

    private void refreshDiag() {
        try {
            rebuildDiag();
        } catch (Throwable t) {
            WidgetPrefs.setLastError(this, "settings-diag: " + t);
        }
    }

    /** Re-runs refreshDiag on the UI thread after a delay (post-action). */
    private void refreshDiagSoon(long delayMs) {
        handler.postDelayed(new Runnable() {
            public void run() { refreshDiag(); }
        }, delayMs);
    }

    private void rebuildDiag() {
        diagRows.removeAllViews();

        // Version
        String version = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) {
        }
        addRowRes(diagRows, R.string.setting_version, version);

        // Launcher
        String launcher = detectLauncher();
        addRowRes(diagRows, R.string.setting_launcher,
                launcher != null ? launcher : getString(R.string.value_none));

        // Widgets: bound / configured / to set up
        int[] bound = boundWidgetIds();
        int configured = 0;
        StringBuilder idsSb = new StringBuilder();
        for (int id : bound) {
            if (idsSb.length() > 0) idsSb.append(", ");
            idsSb.append(id);
            WidgetPrefs.Config cfg = WidgetPrefs.load(this, id);
            if (cfg != null && !cfg.stopId.isEmpty()) configured++;
        }
        addRowRes(diagRows, R.string.setting_widgets, getString(R.string.bound_fmt,
                Integer.valueOf(bound.length), Integer.valueOf(configured),
                Integer.valueOf(bound.length - configured)));
        if (bound.length == 0) {
            addNote(diagRows, getString(R.string.diag_bound_none));
        } else {
            addRowRes(diagRows, R.string.setting_widget_ids, idsSb.toString());
        }

        // Last successful refresh
        long lastOk = WidgetPrefs.getLastRefreshOk(this);
        addRowRes(diagRows, R.string.setting_last_refresh, lastOk > 0
                ? RefreshLogic.formatClock(this, lastOk)
                : getString(R.string.value_none));

        // Last error
        Object[] err = WidgetPrefs.getLastError(this);
        if (err != null) {
            String msg = String.valueOf(err[0]);
            if (msg.length() > 120) msg = msg.substring(0, 120) + "…";
            addRowRes(diagRows, R.string.setting_last_error,
                    msg + " — " + RefreshLogic.formatClock(this, ((Long) err[1]).longValue()));
        } else {
            addRowRes(diagRows, R.string.setting_last_error, getString(R.string.value_none));
        }

        // Catalog
        Catalog cat = catalogQuiet();
        if (cat != null) {
            addRowRes(diagRows, R.string.setting_catalog, getString(R.string.catalog_ok_fmt,
                    Integer.valueOf(cat.routes.size()),
                    Integer.valueOf(cat.stopNames.size())));
        } else {
            addRowRes(diagRows, R.string.setting_catalog, getString(R.string.loading));
        }
    }

    private Catalog catalogQuiet() {
        try {
            return RefreshLogic.catalog(this);
        } catch (Throwable t) {
            return null;
        }
    }

    private void rebuildAbout() {
        aboutRows.removeAllViews();
        addRowRes(aboutRows, R.string.about_author, getString(R.string.about_author_value));
        addRowRes(aboutRows, R.string.about_license, getString(R.string.about_license_value));
        addRowRes(aboutRows, R.string.setting_source, getString(R.string.setting_source_value));
        addRowRes(aboutRows, R.string.setting_catalog_valid, getString(R.string.catalog_valid_value));
        // How lateness is shown (answers "is my bus late?" without opening
        // a manual): times are live predictions, the +N' marker + amber
        // countdown flag the deviation from the timetable.
        addNote(aboutRows, getString(R.string.about_late_note));
    }

    // ------------------------------------------------------------ feed test

    private void runFeedTest() {
        final Context ctx = this;
        testFeedBtn.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                String msg;
                long t0 = System.currentTimeMillis();
                try {
                    byte[] feed = DiviaApi.fetch(DiviaApi.FEED_URL, 7000);
                    long ms = System.currentTimeMillis() - t0;
                    int trips = ProtoRt.parse(feed).size();
                    msg = getString(R.string.diag_feed_ok_fmt,
                            Integer.valueOf(feed.length), Long.valueOf(ms),
                            Integer.valueOf(trips));
                    Catalog cat = catalogQuiet();
                    if (cat != null) {
                        WidgetPrefs.Config cfg0 = null;
                        for (int id : boundWidgetIds()) {
                            WidgetPrefs.Config c = WidgetPrefs.load(ctx, id);
                            if (c != null && !c.stopId.isEmpty()) { cfg0 = c; break; }
                        }
                        if (cfg0 != null) {
                            List<Departure> deps = DiviaApi.nextDepartures(feed, cat,
                                    cfg0.stopId,
                                    cfg0.lineMode ? cfg0.routeId : null,
                                    cfg0.lineMode ? cfg0.dir : -1, 3,
                                    System.currentTimeMillis() / 1000L);
                            msg += "\n" + getString(R.string.diag_deps_fmt,
                                    Integer.valueOf(deps.size()));
                        }
                    }
                } catch (Exception e) {
                    msg = getString(R.string.diag_feed_fail_fmt, String.valueOf(e));
                }
                final String m = msg;
                handler.post(new Runnable() {
                    public void run() {
                        testFeedBtn.setEnabled(true);
                        showResultDialog(getString(R.string.test_feed), m);
                        refreshDiag();
                    }
                });
            }
        }, "divia-diag").start();
    }

    private void showResultDialog(String title, String message) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Throwable ignored) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ launcher repair

    /** Package name of the default HOME app, or null. */
    private String detectLauncher() {
        try {
            Intent h = new Intent(Intent.ACTION_MAIN);
            h.addCategory(Intent.CATEGORY_HOME);
            h.addCategory(Intent.CATEGORY_DEFAULT);
            ResolveInfo ri = getPackageManager().resolveActivity(h, 0);
            if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int[] boundWidgetIds() {
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        int[] ids = awm.getAppWidgetIds(new ComponentName(this, DiviaWidgetProvider.class));
        return ids != null ? ids : new int[0];
    }

    /**
     * "Can't load widget" triage. The button repaints every bound widget
     * immediately (the only thing that replaces a launcher's broken cached
     * state); a cell that stays broken after that is dead data inside the
     * launcher itself, which no app can repair — it has to be removed by
     * hand.
     */
    private void showRepairDialog() {
        final String pkg = detectLauncher();
        String body = getString(R.string.repair_body,
                pkg != null ? pkg : getString(R.string.repair_launcher_unknown));
        new AlertDialog.Builder(this)
                .setTitle(R.string.repair_title)
                .setMessage(body)
                .setPositiveButton(R.string.repaint_now, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        new Thread(new Runnable() {
                            public void run() {
                                RefreshLogic.repaintAll(SettingsActivity.this, null);
                                RefreshLogic.refreshAll(SettingsActivity.this, null);
                            }
                        }, "divia-repair").start();
                        Toast.makeText(SettingsActivity.this,
                                R.string.diag_repaint_done, Toast.LENGTH_SHORT).show();
                        refreshDiagSoon(2500);
                    }
                })
                .setNeutralButton(R.string.repair_open, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        try {
                            Intent s;
                            if (pkg != null) {
                                s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + pkg));
                            } else {
                                s = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
                            }
                            startActivity(s);
                        } catch (Throwable t) {
                            try {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
                            } catch (Throwable t2) {
                                // no settings at all: nothing left to try
                            }
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
