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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.zai.divia.core.Catalog;

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
                String sub = cfg.lineMode
                        ? getString(R.string.direction_fmt, catalog.dirLabel(cfg.routeId, cfg.dir))
                        : getString(R.string.all_lines);
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

    // --------------------------------------------------------------- actions

    /** Opens the picker for an existing widget (edit flow). */
    private void editWidget(int widgetId) {
        Intent i = new Intent(this, ConfigActivity.class);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** Long-press menu on a card: edit, or forget it. */
    private void manageWidget(final Row r) {
        final int wid = r.widgetId;
        String label = r.title + (r.subtitle == null || r.subtitle.isEmpty()
                ? "" : " — " + r.subtitle);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.manage_title_fmt, label))
                .setItems(new String[]{
                        getString(R.string.manage_edit),
                        getString(R.string.manage_forget),
                        getString(R.string.cancel)
                }, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            editWidget(wid);
                        } else if (which == 1) {
                            // Forget = clear the stored config. A visible
                            // widget returns to its "tap to set up"
                            // placeholder; a phantom entry (ghost binding
                            // with leftover prefs) simply disappears.
                            WidgetPrefs.delete(HomeActivity.this, wid);
                            synchronized (RefreshLogic.CACHE) {
                                RefreshLogic.CACHE.remove(wid);
                            }
                            try {
                                RefreshLogic.renderPlaceholder(HomeActivity.this,
                                        AppWidgetManager.getInstance(HomeActivity.this), wid);
                            } catch (Throwable ignored) {
                            }
                            rebuild();
                            updateStatus();
                        }
                    }
                })
                .show();
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
