/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.zai.divia.core.Catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Line / direction / stop picker — v2.1 slimmed down to a pure
 * configuration flow (the widget manager is HomeActivity, diagnostics and
 * maintenance buttons are SettingsActivity).
 *
 * Opened by tapping a freshly added widget (the provider deliberately does
 * not declare android:configure, because MIUI / HyperOS launchers
 * frequently break that flow and unbind the widget), or by tapping a card
 * on the home list (edit flow).
 */
public class ConfigActivity extends Activity {

    private static final int STATE_LINES = 0;
    private static final int STATE_DIRS = 1;
    private static final int STATE_LINE_STOPS = 2;
    private static final int STATE_ALL_STOPS = 3;

    private int appWidgetId = -1;          // widget being configured (or edited)

    private Catalog catalog;
    private int state = STATE_LINES;
    private boolean lineMode = true;
    private Catalog.Route selectedRoute;
    private int selectedDir = 0;

    private TextView title;
    private TextView subtitle;
    private RadioGroup modeGroup;
    private RadioButton modeLineBtn;
    private RadioButton modeStopBtn;
    private EditText search;
    private Button backBtn;
    private ListView list;

    private final Handler handler = new Handler();

    private final List<Entry> entries = new ArrayList<Entry>();
    private final EntryAdapter adapter = new EntryAdapter();

    private static final class Entry {
        String badge = "";
        String title = "";
        String subtitle = "";
        String routeId;       // LINES / DIRS / LINE_STOPS
        int dir;
        String stopId;        // LINE_STOPS / ALL_STOPS
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        title = (TextView) findViewById(R.id.config_title);
        subtitle = (TextView) findViewById(R.id.config_subtitle);
        modeGroup = (RadioGroup) findViewById(R.id.mode_group);
        modeLineBtn = (RadioButton) findViewById(R.id.mode_line_btn);
        modeStopBtn = (RadioButton) findViewById(R.id.mode_stop_btn);
        search = (EditText) findViewById(R.id.search);
        backBtn = (Button) findViewById(R.id.back_btn);
        list = (ListView) findViewById(R.id.list);

        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onEntryPicked(entries.get(position));
            }
        });

        backBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                goBack();
            }
        });

        modeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                lineMode = checkedId == R.id.mode_line_btn;
                state = lineMode ? STATE_LINES : STATE_ALL_STOPS;
                selectedRoute = null;
                search.setText("");
                applyState();
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                rebuild();
            }
        });

        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
            appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Nothing to configure: back to the manager.
            Intent home = new Intent(this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            finish();
            return;
        }

        // Preselect the current mode of the widget being edited.
        WidgetPrefs.Config cfg = WidgetPrefs.load(this, appWidgetId);
        if (cfg != null && !cfg.stopId.isEmpty()) {
            lineMode = cfg.lineMode;
            modeLineBtn.setChecked(lineMode);
            modeStopBtn.setChecked(!lineMode);
            state = lineMode ? STATE_LINES : STATE_ALL_STOPS;
        }

        // Load the catalog off the main thread.
        new Thread(new Runnable() {
            public void run() {
                try {
                    catalog = RefreshLogic.catalog(ConfigActivity.this);
                } catch (Exception e) {
                    catalog = null;
                }
                handler.post(new Runnable() {
                    public void run() {
                        applyState();
                    }
                });
            }
        }, "divia-catalog").start();

        applyState();
    }

    @Override
    public void onBackPressed() {
        if (goBack()) return;
        super.onBackPressed();
    }

    /** @return true when the back action was consumed. */
    private boolean goBack() {
        if (state == STATE_LINE_STOPS) {
            state = STATE_DIRS;
            applyState();
            return true;
        }
        if (state == STATE_DIRS) {
            state = STATE_LINES;
            applyState();
            return true;
        }
        return false;
    }

    private void onEntryPicked(Entry e) {
        switch (state) {
            case STATE_LINES: {
                selectedRoute = catalog != null ? catalog.routes.get(e.routeId) : null;
                if (selectedRoute != null) {
                    state = STATE_DIRS;
                    applyState();
                }
                break;
            }
            case STATE_DIRS: {
                selectedDir = e.dir;
                state = STATE_LINE_STOPS;
                applyState();
                break;
            }
            case STATE_LINE_STOPS: {
                saveWidget(e.routeId, e.dir, e.stopId, true);
                break;
            }
            case STATE_ALL_STOPS: {
                saveWidget(null, 0, e.stopId, false);
                break;
            }
        }
    }

    private void saveWidget(String routeId, int dir, String stopId, boolean isLineMode) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        WidgetPrefs.Config cfg = new WidgetPrefs.Config();
        cfg.lineMode = isLineMode;
        cfg.routeId = routeId != null ? routeId : "";
        cfg.dir = dir;
        cfg.stopId = stopId;
        WidgetPrefs.save(this, appWidgetId, cfg);

        // immediate refresh (arms the alarm too)
        RefreshLogic.kick(this);

        // Result contract only matters when the launcher launched us for
        // configuration; harmless otherwise.
        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    // ---------------------------------------------------------------- state

    private void applyState() {
        search.setVisibility(state != STATE_DIRS ? View.VISIBLE : View.GONE);
        backBtn.setVisibility(state == STATE_LINE_STOPS || state == STATE_DIRS
                ? View.VISIBLE : View.GONE);
        list.setVisibility(View.VISIBLE);

        switch (state) {
            case STATE_LINES:
                subtitle.setText(R.string.step_lines);
                break;
            case STATE_DIRS:
                if (selectedRoute != null) {
                    subtitle.setText(getString(R.string.step_directions_fmt,
                            selectedRoute.shortName));
                }
                break;
            case STATE_LINE_STOPS:
                if (selectedRoute != null && catalog != null) {
                    subtitle.setText(getString(R.string.step_stops_fmt,
                            selectedRoute.shortName + " → " + catalog.dirLabel(
                                    selectedRoute.id, selectedDir)));
                }
                break;
            case STATE_ALL_STOPS:
                subtitle.setText(R.string.step_all_stops);
                break;
        }
        rebuild();
    }

    private void rebuild() {
        entries.clear();
        String q = search.getText().toString().trim().toLowerCase();

        if (catalog == null) {
            Entry e = new Entry();
            e.title = getString(R.string.loading);
            entries.add(e);
            adapter.notifyDataSetChanged();
            return;
        }

        switch (state) {
            case STATE_LINES: {
                for (Catalog.Route r : catalog.sortedRoutes()) {
                    Entry e = new Entry();
                    e.badge = r.shortName;
                    e.title = r.shortName;
                    e.subtitle = r.longName;
                    e.routeId = r.id;
                    if (matches(e, q)) entries.add(e);
                }
                break;
            }
            case STATE_DIRS: {
                if (selectedRoute != null) {
                    for (int d = 0; d <= 1; d++) {
                        Entry e = new Entry();
                        e.badge = d == 0 ? "→" : "←";
                        e.title = getString(R.string.direction_fmt,
                                catalog.dirLabel(selectedRoute.id, d));
                        String[] stops = catalog.lineStopIds(selectedRoute.id, d);
                        e.subtitle = getString(R.string.stop_count_fmt,
                                stops != null ? stops.length : 0);
                        e.routeId = selectedRoute.id;
                        e.dir = d;
                        if (matches(e, q)) entries.add(e);
                    }
                }
                break;
            }
            case STATE_LINE_STOPS: {
                if (selectedRoute != null) {
                    String[] stops = catalog.lineStopIds(selectedRoute.id, selectedDir);
                    if (stops != null) {
                        for (String stopId : stops) {
                            Entry e = new Entry();
                            e.badge = selectedRoute.shortName;
                            e.title = catalog.stopName(stopId);
                            e.subtitle = "";
                            e.routeId = selectedRoute.id;
                            e.dir = selectedDir;
                            e.stopId = stopId;
                            if (matches(e, q)) entries.add(e);
                        }
                    }
                }
                break;
            }
            case STATE_ALL_STOPS: {
                // One entry per physical stop: quays that share a name are
                // grouped (the sibling ids are merged at query time).
                ArrayList<String> seen = new ArrayList<String>();
                ArrayList<String> ids = new ArrayList<String>(catalog.stopNames.keySet());
                Collections.sort(ids, String.CASE_INSENSITIVE_ORDER);
                for (String stopId : ids) {
                    String name = catalog.stopNames.get(stopId);
                    if (seen.contains(name)) continue;
                    seen.add(name);
                    String lines = catalog.stopLinesLabelAny(stopId);
                    Entry e = new Entry();
                    String[] ll = lines.isEmpty() ? new String[0] : lines.split(", ");
                    e.badge = ll.length > 0 ? ll[0] : "•";
                    e.title = name;
                    e.subtitle = lines.isEmpty() ? "" : getString(R.string.lines_fmt, lines);
                    e.stopId = stopId;
                    if (matches(e, q)) entries.add(e);
                }
                break;
            }
        }
        adapter.notifyDataSetChanged();
    }

    private static boolean matches(Entry e, String q) {
        if (q.isEmpty()) return true;
        return e.title.toLowerCase().contains(q) || e.subtitle.toLowerCase().contains(q)
                || e.badge.toLowerCase().contains(q);
    }

    // -------------------------------------------------------------- adapter

    private final class EntryAdapter extends BaseAdapter {

        public int getCount() {
            return entries.size();
        }

        public Object getItem(int position) {
            return entries.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(ConfigActivity.this)
                        .inflate(R.layout.item_entry, parent, false);
            }
            Entry e = entries.get(position);
            TextView badge = (TextView) v.findViewById(R.id.item_badge);
            TextView title = (TextView) v.findViewById(R.id.item_title);
            TextView subtitle = (TextView) v.findViewById(R.id.item_subtitle);
            badge.setText(e.badge);
            title.setText(e.title);
            if (e.subtitle == null || e.subtitle.isEmpty()) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setText(e.subtitle);
            }
            return v;
        }
    }
}
