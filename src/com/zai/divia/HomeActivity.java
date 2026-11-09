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
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.zai.divia.core.Catalog;
import com.zai.divia.core.ReleaseCheck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App home screen (v2.1): a clean manager for the user's widgets.
 *
 *  - header with the app title and a settings button,
 *  - one card per configured widget (tap = edit, long-press = manage),
 *  - a status strip with the last refresh time (tap = refresh now),
 *  - an empty-state card explaining how to add a widget.
 *
 * All diagnostics data and the maintenance buttons (refresh / test feed /
 * repair) moved to SettingsActivity, per the v2.1 UI revamp.
 */
public class HomeActivity extends Activity {

    private Catalog catalog;

    private ListView list;
    private LinearLayout emptyCard;
    private TextView statusText;
    private TextView updateBanner;
    private final Handler handler = new Handler();
    private final List<Row> rows = new ArrayList<Row>();
    private final RowAdapter adapter = new RowAdapter();

    private static final class Row {
        int widgetId;
        String badge = "";
        String title = "";
        String subtitle = "";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        list = (ListView) findViewById(R.id.list);
        emptyCard = (LinearLayout) findViewById(R.id.empty_card);
        statusText = (TextView) findViewById(R.id.status_text);
        updateBanner = (TextView) findViewById(R.id.update_banner);

        updateBanner.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                UpdateFlow.bannerTapped(HomeActivity.this);
            }
        });

        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                editWidget(rows.get(position).widgetId);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                manageWidget(rows.get(position));
                return true;
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(HomeActivity.this, String.valueOf(t), Toast.LENGTH_LONG).show();
                }
            }
        });

        findViewById(R.id.status_strip).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                RefreshLogic.kick(HomeActivity.this);
                statusText.setText(R.string.status_refreshing);
                handler.postDelayed(new Runnable() {
                    public void run() { updateStatus(); }
                }, 2500);
            }
        });

        // Drop configs of widget ids the launcher no longer reports as
        // bound (MIUI often forgets to notify us when a widget is
        // removed), then heal every widget and arm the periodic alarm.
        cleanupOrphans();
        RefreshLogic.kick(this);
        maybeAskNotifPermission();

        // v1.2.0: quiet once-a-day check of the GitHub releases; when a
        // newer version is cached, the banner appears above the list.
        UpdateFlow.maybeCheckQuietly(this, new Runnable() {
            public void run() { updateBannerUi(); }
        });

        // Load the catalog off the main thread.
        new Thread(new Runnable() {
            public void run() {
                try {
                    catalog = RefreshLogic.catalog(HomeActivity.this);
                } catch (Exception e) {
                    catalog = null;
                }
                handler.post(new Runnable() {
                    public void run() { rebuild(); }
                });
            }
        }, "divia-catalog").start();

        rebuild();
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateStatus(); // covers coming back from Settings / picker
        updateBannerUi(); // covers coming back from the unknown-sources toggle
        rebuild();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private int[] boundWidgetIds() {
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        int[] ids = awm.getAppWidgetIds(new ComponentName(this, DiviaWidgetProvider.class));
        return ids != null ? ids : new int[0];
    }

    private void rebuild() {
        rows.clear();
        if (catalog != null) {
            // Only widgets that are BOTH bound and configured are listed.
            // Bound-but-unconfigured ids are ghost bindings (or a freshly
            // added widget waiting for setup): they are counted in the
            // Settings > Diagnostics card instead of flooding this list.
            for (int id : boundWidgetIds()) {
                WidgetPrefs.Config cfg = WidgetPrefs.load(this, id);
                if (cfg == null || cfg.stopId.isEmpty()) continue;
                Row r = new Row();
                r.widgetId = id;
                Catalog.Route route = catalog.routes.get(cfg.routeId);
                r.badge = cfg.lineMode && route != null ? route.shortName : "•";
                r.title = catalog.stopName(cfg.stopId);
                String sub;
                if (cfg.lineMode) {
                    sub = getString(R.string.direction_fmt, catalog.dirLabel(cfg.routeId, cfg.dir));
                    boolean commute = cfg.stopId2 != null && !cfg.stopId2.isEmpty();
                    if (commute) {
                        sub = sub + " / " + catalog.stopName(cfg.stopId2);
                    }
                } else {
                    sub = getString(R.string.all_lines);
                }
                r.subtitle = sub + getString(R.string.widget_id_fmt, id);
                rows.add(r);
            }
        }
        adapter.notifyDataSetChanged();
        boolean showEmpty = catalog != null && rows.isEmpty();
        emptyCard.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        list.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
    }

    private void updateStatus() {
        try {
            long lastOk = WidgetPrefs.getLastRefreshOk(this);
            if (lastOk > 0) {
                statusText.setText(getString(R.string.updated_fmt,
                        RefreshLogic.formatClock(this, lastOk)));
            } else {
                statusText.setText(R.string.offline);
            }
        } catch (Throwable ignored) {
        }
    }

    /** v1.2.0: shows the update banner only when a cached release is newer
     *  than the installed version (quiet check / manual check caches it). */
    private void updateBannerUi() {
        try {
            ReleaseCheck.Release cached = WidgetPrefs.getCachedRelease(this);
            boolean show = cached != null && UpdateFlow.isNewerThan(this, cached);
            updateBanner.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                updateBanner.setText(getString(R.string.update_banner_fmt,
                        cached.versionString));
            }
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- actions

    /** Opens the picker for an existing widget (edit flow). */
    private void editWidget(int widgetId) {
        Intent i = new Intent(this, ConfigActivity.class);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** Long-press menu on a card: edit, rows, return direction, or forget. */
    private void manageWidget(final Row r) {
        final int wid = r.widgetId;
        String label = r.title + (r.subtitle == null || r.subtitle.isEmpty()
                ? "" : " — " + r.subtitle);
        final WidgetPrefs.Config cfg = WidgetPrefs.load(this, wid);
        final boolean commute = cfg != null && cfg.lineMode
                && cfg.stopId2 != null && !cfg.stopId2.isEmpty();

        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<Integer> actions = new ArrayList<Integer>();
        labels.add(getString(R.string.manage_edit));            actions.add(A_EDIT);
        if (cfg != null && cfg.lineMode) {
            labels.add(getString(commute ? R.string.commute_edit : R.string.commute_add));
            actions.add(A_RETURN);
            if (commute) {
                labels.add(getString(R.string.commute_remove));
                actions.add(A_RETURN_OFF);
            }
        }
        if (cfg != null) {
            labels.add(getString(R.string.manage_rows));       actions.add(A_ROWS);
            labels.add(getString(R.string.recur_menu));       actions.add(A_RECUR);
        }
        labels.add(getString(R.string.manage_forget));         actions.add(A_FORGET);
        labels.add(getString(R.string.cancel));                 actions.add(A_CANCEL);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.manage_title_fmt, label))
                .setItems(labels.toArray(new String[labels.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                int action = actions.get(which);
                                if (action == A_EDIT) {
                                    editWidget(wid);
                                } else if (action == A_RETURN) {
                                    pickReturnStop(wid);
                                } else if (action == A_RETURN_OFF) {
                                    cfg.stopId2 = "";
                                    WidgetPrefs.save(HomeActivity.this, wid, cfg);
                                    RefreshLogic.kick(HomeActivity.this);
                                    rebuild();
                                } else if (action == A_ROWS) {
                                    chooseRows(wid, cfg);
                                } else if (action == A_RECUR) {
                                    showRecurDialog(wid);
                                } else if (action == A_FORGET) {
                                    forgetWidget(wid);
                                }
                            }
                        })
                .show();
    }

    private static final int A_EDIT = 0, A_RETURN = 1, A_RETURN_OFF = 2,
            A_ROWS = 3, A_RECUR = 4, A_FORGET = 5, A_CANCEL = 6;

    /** Opens the return-direction stop picker for an existing line widget. */
    private void pickReturnStop(int widgetId) {
        Intent i = new Intent(this, ConfigActivity.class);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        i.putExtra(ConfigActivity.EXTRA_PICK_RETURN, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** Row-count chooser: auto (from widget size) or a fixed 2/3/5. */
    private void chooseRows(final int wid, final WidgetPrefs.Config cfg) {
        final int[] values = {0, 2, 3, 5};
        String[] labels = {
                getString(R.string.rows_auto),
                getString(R.string.rows_two),
                getString(R.string.rows_three),
                getString(R.string.rows_five),
        };
        int checked = 3;
        for (int i = 0; i < values.length; i++) if (values[i] == cfg.rows) checked = i;
        final int[] sel = {checked};
        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_rows)
                .setSingleChoiceItems(labels, checked,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                sel[0] = which;
                            }
                        })
                .setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                cfg.rows = values[sel[0]];
                                WidgetPrefs.save(HomeActivity.this, wid, cfg);
                                RefreshLogic.kick(HomeActivity.this);
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------- recurring alerts

    /**
     * v1.3.0: recurring "time to leave" rule editor. The rule reuses the
     * widget's own filter (its line+direction+stop, or its whole stop), so
     * there is nothing to pick here beyond the target time, the lead time
     * and the days it applies to.
     */
    private void showRecurDialog(final int wid) {
        final WidgetPrefs.Recurring existing = WidgetPrefs.getRecurring(this, wid);

        View v = LayoutInflater.from(this).inflate(R.layout.dialog_recur, null);

        final NumberPicker npH = (NumberPicker) v.findViewById(R.id.np_hour);
        final NumberPicker npM = (NumberPicker) v.findViewById(R.id.np_min);
        npH.setMinValue(0);
        npH.setMaxValue(23);
        npM.setMinValue(0);
        npM.setMaxValue(11);
        npM.setDisplayedValues(new String[]{
                "00", "05", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55"});
        int target = existing != null ? existing.targetMin : 8 * 60;
        npH.setValue(target / 60);
        npM.setValue((target % 60) / 5);

        final RadioGroup leadGroup = (RadioGroup) v.findViewById(R.id.rg_lead);
        int lead = existing != null ? existing.leadMin : 5;
        ((RadioButton) v.findViewById(
                lead == 3 ? R.id.rb_lead_3 : lead == 10 ? R.id.rb_lead_10 : R.id.rb_lead_5))
                .setChecked(true);

        // UI order Mon..Sun -> Calendar.DAY_OF_WEEK {2,3,4,5,6,7,1}.
        final CheckBox[] dayBoxes = {
                (CheckBox) v.findViewById(R.id.chk_mon),
                (CheckBox) v.findViewById(R.id.chk_tue),
                (CheckBox) v.findViewById(R.id.chk_wed),
                (CheckBox) v.findViewById(R.id.chk_thu),
                (CheckBox) v.findViewById(R.id.chk_fri),
                (CheckBox) v.findViewById(R.id.chk_sat),
                (CheckBox) v.findViewById(R.id.chk_sun)};
        final int[] dayDows = {2, 3, 4, 5, 6, 7, 1};
        int mask = existing != null ? existing.daysMask : WidgetPrefs.Recurring.WEEKDAYS;
        for (int i = 0; i < 7; i++) {
            dayBoxes[i].setChecked((mask & (1 << (dayDows[i] - 1))) != 0);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.recur_title)
                .setView(v)
                .setPositiveButton(R.string.recur_save,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                int leadSel = 5;
                                if (leadGroup.getCheckedRadioButtonId() == R.id.rb_lead_3) leadSel = 3;
                                else if (leadGroup.getCheckedRadioButtonId() == R.id.rb_lead_10) leadSel = 10;
                                int m = 0;
                                for (int i = 0; i < 7; i++) {
                                    if (dayBoxes[i].isChecked()) m |= 1 << (dayDows[i] - 1);
                                }
                                saveRecurring(wid, npH.getValue() * 60 + npM.getValue() * 5,
                                        leadSel, m);
                            }
                        })
                .setNegativeButton(R.string.cancel, null);
        if (existing != null) {
            b.setNeutralButton(R.string.recur_remove,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            NotifyScheduler.cancelRecurring(HomeActivity.this, wid);
                            WidgetPrefs.clearRecurring(HomeActivity.this, wid);
                            RefreshLogic.kick(HomeActivity.this);
                            Toast.makeText(HomeActivity.this,
                                    R.string.recur_removed_toast, Toast.LENGTH_SHORT).show();
                        }
                    });
        }
        b.show();
    }

    private void saveRecurring(int wid, int targetMin, int leadMin, int daysMask) {
        if (daysMask == 0) {
            Toast.makeText(this, R.string.recur_need_day, Toast.LENGTH_SHORT).show();
            return;
        }
        WidgetPrefs.Recurring r = new WidgetPrefs.Recurring();
        r.leadMin = leadMin;
        r.targetMin = targetMin;
        r.daysMask = daysMask;
        r.enabled = true;
        WidgetPrefs.setRecurring(this, wid, r);
        // Reset the day state so an edited rule can still ring later today.
        WidgetPrefs.setRecurFired(this, wid, "");
        NotifyScheduler.cancelRecurring(this, wid);
        RefreshLogic.kick(this);   // arms from the next live refresh
        Toast.makeText(this, R.string.recur_saved_toast, Toast.LENGTH_SHORT).show();
        askRecurNotifPermission();
    }

    /** Android 13+: ask for POST_NOTIFICATIONS once, in context, when the
     *  user first sets a recurring alert. */
    private void askRecurNotifPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && !WidgetPrefs.getNotifAsked(this)
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                WidgetPrefs.setNotifAsked(this, true);
                requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Forget = clear the stored config; visible widgets reset to setup. */
    private void forgetWidget(final int wid) {
        // Forget = clear the stored config. A visible
        // widget returns to its "tap to set up"
        // placeholder; a phantom entry (ghost binding
        // with leftover prefs) simply disappears.
        NotifyScheduler.cancel(this, wid);
        NotifyScheduler.cancelRecurring(this, wid);
        WidgetPrefs.delete(this, wid);
        synchronized (RefreshLogic.CACHE) {
            RefreshLogic.CACHE.remove(wid);
        }
        try {
            RefreshLogic.renderPlaceholder(this,
                    AppWidgetManager.getInstance(this), wid);
        } catch (Throwable ignored) {
        }
        rebuild();
        updateStatus();
    }

    /** Deletes stored configs of widget ids that are no longer bound. */
    private void cleanupOrphans() {
        try {
            android.content.SharedPreferences p = getSharedPreferences("divia_widgets", MODE_PRIVATE);
            Set<Integer> bound = new HashSet<Integer>();
            for (int id : boundWidgetIds()) bound.add(Integer.valueOf(id));
            android.content.SharedPreferences.Editor ed = null;
            for (String k : p.getAll().keySet()) {
                if (k == null || !k.startsWith("w_")) continue;
                int id;
                try {
                    id = Integer.parseInt(k.substring(2, k.indexOf('_', 2)));
                } catch (Exception ex) {
                    continue;
                }
                if (!bound.contains(Integer.valueOf(id))) {
                    if (ed == null) ed = p.edit();
                    ed.remove(k);
                }
            }
            if (ed != null) ed.apply();
        } catch (Throwable ignored) {
        }
    }

    /** Android 13+: ask once, in context, for the notification permission
     *  (departure alerts + info trafic) when the feature is enabled. */
    private void maybeAskNotifPermission() {
        try {
            if (!WidgetPrefs.getTrafficAlerts(this)) return;
            if (WidgetPrefs.getNotifAsked(this)) return;
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                WidgetPrefs.setNotifAsked(this, true);
                requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            }
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- adapter

    private final class RowAdapter extends BaseAdapter {

        public int getCount() {
            return rows.size();
        }

        public Object getItem(int position) {
            return rows.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(HomeActivity.this)
                        .inflate(R.layout.item_entry, parent, false);
            }
            Row r = rows.get(position);
            TextView badge = (TextView) v.findViewById(R.id.item_badge);
            TextView title = (TextView) v.findViewById(R.id.item_title);
            TextView subtitle = (TextView) v.findViewById(R.id.item_subtitle);
            badge.setText(r.badge);
            title.setText(r.title);
            if (r.subtitle == null || r.subtitle.isEmpty()) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setText(r.subtitle);
            }
            return v;
        }
    }
}
