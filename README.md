# Divia Widget

**Live bus & tram departures for Dijon, right on your home screen.**

by **Snassyyy** — <https://github.com/snassyyyVLR/divia-widget>

![widget preview](docs/widget-preview.png)

A zero-dependency, zero-account Android home-screen widget showing the next
departures for any Divia line or stop — powered by the official French
open-data realtime feed (`transport.data.gouv.fr`). No Gradle, no analytics;
the only permissions are the network, notifications and (for the self-
updater) installing packages: the whole app is a few dozen KB of dependency-
free Java, a purpose-built GTFS-RT protobuf parser and a small JSON scanner.

Current release: **release-1.7.0** — version numbering follows the public
GitHub release line (previous public drop: release-1.4.0, the private-key
migration). `versionCode` stays monotonic (now 21); releases since 1.4.0
are signed with the private release key and install in place.

- **Any line or any stop** — line+direction mode, or whole-stop mode merging
  every quay of a physical stop.
- **Two directions on one widget (1.1.0)** — commute mode shows both legs
  (e.g. Suquet → Université *and* AgroSup → Talant) split across the rows.
- **Live predictions** — times come from the GTFS-RT trip-update feed, so a
  delay is already baked into what you see.
- **Explicit lateness marker** — an amber countdown plus a compact `+4′`
  (or `+2h05`) tag when a vehicle runs ≥ 2 min behind the published
  timetable. Cancelled trips are filtered out.
- **"Horaires adaptés" chip (1.1.0)** — on strike/demo/holiday days, when the
  network runs a special plan, the footer says so (that's the day Google
  Maps showed the wrong "scheduled" times).
- **Resizable, 2/3/5 rows (1.1.0)** — the row count follows the widget size;
  long-press a widget card in the app to override it.
- **Line-colored badge (1.1.0)** — the header pill is painted in the line's
  official GTFS color (L5 pink, T1/T2 tram purple, Liannes blue…).
- **"Time to leave" alerts (1.1.0)** — tap a departure row on the widget,
  pick a 3/5/10-min lead time, get a notification that re-checks the live
  feed before firing (a slipping bus yields an updated countdown).
- **Recurring alerts (1.3.0)** — long-press a widget card → *Alerte
  récurrente*: on the chosen days, the widget watches the live feed and
  notifies N minutes before the first departure at/after your target time
  (e.g. every weekday around 08:05 for the 08:12 T1). The aim re-evaluates
  on every refresh, so a slipping bus shifts the alert; one notification
  per rule per day.
- **Info-trafic notifications (1.1.0)** — official disruption posts from
  information.divia.fr (strikes, demos, replaced trams), never promo.
- **Battery-aware refresh (1.1.0)** — 5-minute cadence by day, 30 minutes
  during the night service gap, self-rescheduling alarms.
- **In-app updater (1.2.0; status constants fixed in 1.4.0, install
  confirmation fixed in 1.5.2, ROM-blocked sessions fixed in 1.6.1)** —
  checks this repo's GitHub
  Releases (`snassyyyVLR/divia-widget`) every time the app opens (at most
  once per 3h; plus a manual button in Settings); a home-screen banner
  offers the new version, one tap downloads the APK and hands it to
  Android's own installer. The repo slug lives in a single constant: `REPO`
  in `src/com/zai/divia/core/ReleaseCheck.java`.
  Until 1.5.2 the installer's confirmation popup never appeared (the
  status callback was an immutable PendingIntent, so Android dropped it
  and every self-update "failed" silently). Since 1.6.1 there is also a
  fallback for ROMs that kill such sessions outright: some builds —
  Xiaomi HyperOS/MIUI with "MIUI optimization" on — abort a third-party
  app's PackageInstaller session BEFORE the confirmation screen, so the
  updater detects exactly that (a failure while no screen was ever
  shown) and re-launches the install through the system's classic
  installer UI: ACTION_VIEW on a content:// URI served by the built-in
  zero-dependency ApkProvider, with a tap-to-install notification as
  the durable handle. Coming from 1.5.2 on such a ROM you install
  1.6.2 manually once — every later update flows in place.
- **Four widget dispositions (1.5.0–1.7.0)** — every widget picks its own
  layout: *Détaillée* (destination + clock), *Compacte* (line badge +
  minutes, denser rows, right-aligned countdown), *Moderne* (soft rounded
  row cards, badge pills, big minutes) or *Grands caractères* (the
  detailed layout with bigger type). The picker shows a LIVE preview of
  each disposition, inflated from the very RemoteViews the launcher would
  receive. Colors stay automatic (launcher day/night) in all four.
- **Polished app screens (1.6.0)** — tinted banners, segmented mode
  control in the picker, per-line colored badge pills on the home list,
  hero empty state, light/dark status bar.
- **Fresh timetables without app updates (1.7.0)** — the bundled GTFS
  catalogue now refreshes ITSELF: once a day the app scans this repo's
  GitHub releases for a newer `catalog.gz` asset, validates it (it must
  parse AND cover at least as far as the current one) and installs it
  atomically. A weekly GitHub Action (`.github/workflows/catalog.yml`)
  regenerates the catalogue from the official GTFS and publishes it on
  the rolling `timetable` release — horaires stay current without
  shipping a single APK.
- **"Stops near me" picker (1.7.0, opt-in)** — the picker has a third
  mode next to line/stop: *Autour de moi*. Location is requested at
  runtime, only when that mode is opened, and used once to list the
  closest stops (the widget itself never touches location).
- **Where the vehicle actually is (1.7.0)** — the Divia vehicle-positions
  feed is fetched in parallel with departures: a departure served by a
  vehicle sitting at the stop just before yours is tagged "à 1 arrêt".
- **Early vehicles (1.7.0)** — a vehicle running ≥ 2 min AHEAD of the
  timetable gets a green countdown and a "-2′" marker, mirroring the
  amber "+4′" late state.
- **Per-line info-trafic (1.7.0)** — disruption posts that name specific
  lines you don't ride no longer ring; network-wide posts still do.
- **"Dernier départ" tag (1.5.0)** — the row of a line's last departure of
  the day carries a `dernier` marker (computed from the bundled timetable,
  so it survives even when the live feed's horizon is short). No more
  waiting at 23:40 for a tram that already went.
- **Widget layout variants (1.5.0)** — long-press a widget card →
  *Disposition du widget*: Détaillée (destination + clock) or Compacte
  (line badge + minutes only, denser 20dp rows). Colors stay automatic —
  the launcher's day/night switching applies to both dispositions (color
  themes were tried and rejected during the 1.5.0 cycle).
- **Adaptive icon (1.5.0)** — the ORIGINAL logo bitmap is back: the legacy
  mipmap PNG below Android 8, and the same white glyph artwork extracted
  from it reused as both the adaptive foreground and the Android 13+
  monochrome layer over a full-bleed Divia red background (the vector
  redesign was dropped).
- **Catalog expiry warning (1.5.0)** — the bundled timetable carries its own
  coverage end date; a home-screen banner + one-time notification appear
  during its last 30 days, and Settings → Diagnostics always shows it.
- **5-minute auto-refresh + tap-to-refresh**, self-healing widgets, offline
  fallback with "last good data" rows.
- **Clean companion app** — home screen listing your widgets, and a Settings
  screen with diagnostics (bound widget ids, launcher, last refresh, last
  error), feed test, traffic-alerts toggle, update check and repair actions.
- **FR/EN, day/night themes.** Android 5.0+ (API 21), target 34.

## Reading the widget

Each row is: destination · countdown · clock time.

- The clock time is the **live prediction** — if the vehicle is late, that
  time already includes the delay.
- **`dernier`** at the end of a destination = this is the last departure of
  that line in that direction for the day — don't count on a next one.
- **Amber countdown + `+4′`** = running ≥ 2 min late vs the timetable
  (`+2h05` format past an hour). On-time or early vehicles show no marker.
- **Tap a row** → set a "time to leave" alert for that departure; **tap
  elsewhere** → instant refresh.
- Footer `Mis à jour à HH:MM` = last successful live refresh; `Hors ligne`
  = showing cached rows (data-freshness signal, not a lateness one);
  `Horaires adaptés` = the network runs a special plan today (strike, demo,
  holiday) — trust the realtime times, not the printed fiche horaire.

## Lateness, and why apps sometimes disagree

The delay figure is audited, not a guess: checking the whole live feed
against the official published timetable (1,600 delay-carrying events),
`predicted time − delay` reproduced the static GTFS **to the exact second
on 99.6% of events** (`tools/check_rt_vs_static.py`).

One caveat discovered in the wild: Divia publishes its GTFS **date by
date** — `calendar_dates.txt` gives every single day its own service
pattern. On 2026-11-07, a downtown demonstration put the network on a
one-off adapted plan (at Suquet, the L5 evening ran 20:10 / 20:58 / 21:44…
instead of the regular Saturday 19:57 / 20:12 / 20:27 / 20:47 / 21:14…).
That evening Google Maps kept displaying the *regular* Saturday timetable —
real times, but suspended for the day — and called the 20:13 bus "+16 min
(was 19:57)", while this widget diffed against the plan actually in force
and marked it `+3′`. Both agreed on the arrival time itself (20:13);
only the baseline differed. On disruption days, trust the app whose
baseline matches the operator's plan *for that date* — the realtime feed.

## Install

1. Grab the APK from the [releases](../../releases) page (`DiviaWidget-release-<version>.apk`)
   — or build it (see below) — and install it (allow "install unknown apps"
   once). Users on a debug-signed 1.2.x/1.3.x build must uninstall once
   first (signing-key change at 1.4.0).
2. Long-press an empty area of your home screen → Widgets → Divia Widget.
3. The widget appears with *Tap to pick your line and stop* — tap it,
   choose line → direction → stop (or switch to whole-stop mode).
4. Tap the widget any time to refresh; it also auto-refreshes every 5 min.

Recommended on Xiaomi/POCO/HyperOS: Settings → Apps → Divia Widget →
**Autostart ON**, Battery → **Unrestricted** (otherwise background refresh
is delayed).

## Build from source

Requirements: an Android SDK with **build-tools 34.0.0** and **platform
android-34**, a JDK 8+, `python3`, `zip`. No Gradle involved:

```bash
export ANDROID_HOME=/path/to/sdk   # or leave at ~/Android/Sdk
./build.sh        # -> DiviaWidget-debug.apk
./run-tests.sh    # JVM harness: layout whitelist + render pipeline
```

The build is intentionally paranoid about two widget-specific traps:

- **RemoteViews whitelist** — the launcher inflates the widget layout *in
  its own process* and rejects any view class outside the `@RemoteView`
  whitelist. A build-time guard (`check_widget_layout.py`) and a JVM test
  (`LayoutWhitelist`) both fail the build if a forbidden class sneaks in
  (that was the v1.x "Can't load widget on every launcher" bug: a plain
  `<View>` divider).
- **Stable resource ids** — after an in-place update, launchers re-apply
  RemoteViews cached from the *previous* APK with the old numeric ids
  baked in. `stable-ids.txt` pins every resource id (268 ids of the
  published line — strictly append-only; 1.5.0's new resources — compact
  layout + per-row badges, layout-variant strings, expiry/update strings —
  are appended after them), and the build fails if anything drifts or
  disappears. Each release's pin set is verified resource-by-resource
  against the previously published APK (`aapt2 dump resources` on both), so
  widgets survive the update untouched — including the five-row ids in
  1.2.0, the updater ids in 1.3.0, the compact-layout ids in 1.5.0, the
  modern-layout ids in 1.6.0, the updater-fallback strings in 1.6.2
  (pinned at 0x7f0600b0-b3 — NOT at the 0x7f0600af-b2 the broken 1.6.1
  assigned them, which collided with `layout_modern` = 0x7f0600af; string
  ids never ride in launcher-cached RemoteViews, so the renumbering is
  invisible to widgets) and the 1.7.0 additions (early color, large
  layout, nearby-picker ids/strings) at 0x7f010012/0x7f020018/0x7f03004a/
  0x7f040009/0x7f0600b4-be. The large layout reuses every detailed-layout
  view id, so widgets switch dispositions with zero id churn.
  The compact layout reuses every detailed-layout view id (only
  `cbadge0..4` are new), so cached RemoteViews keep resolving in both
  dispositions.

`debug.keystore` ships with the repo so every rebuild installs over the
previous one. It is a debug key — fine for personal sideloading, never
for a store release; delete it and `build.sh` generates a fresh one (you
will then need to uninstall the older build first).

## Signing (read before wider distribution)

Android only installs an update over an existing app when it is signed
with the **same certificate** — and the in-app updater (1.2.0+) relies on
exactly that. Consequences:

- **Since 1.4.0 every public release is signed with a PRIVATE release
  key** (kept off-repo). That switch was the one-time break: 1.4.0 must
  be installed after uninstalling the debug-signed 1.2.x/1.3.x builds;
  from 1.4.0 on, updates install in place again — and only the private
  key can sign them, so nobody else can build an APK that installs over
  a user's copy (the public debug key allowed exactly that).
- Releases ≤ 1.3.0 were signed with the shared `debug.keystore` from
  this repo. It stays for local development: a plain `bash build.sh`
  uses it whenever no release key is configured.
- To sign a release build: `DIVIA_KEYSTORE=/path/release.keystore
  DIVIA_KEYSTORE_PASS=… DIVIA_KEYSTORE_ALIAS=… bash build.sh`. A
  `release.keystore` dropped next to `build.sh` also wins automatically —
  careful, that is a silent key switch. `.gitignore` already excludes
  `release.keystore`; never commit it, and **back it up**: a lost key can
  never be recovered and future updates become impossible.
- **Switching keys after users installed = forced uninstall for everyone**
  (happened once, at 1.4.0 — don't repeat it). Key rotation via
  `apksigner rotate` only works on Android 9+ and still needs the old key
  for older devices — with minSdk 21 there is no clean migration.

## Repository layout

```
AndroidManifest.xml     app manifest (version 1.7.0, API 21-34)
build.sh                raw build-tools build (aapt2 → ecj/javac → d8 → zipalign → apksigner)
run-tests.sh            JVM test harness runner (1080 assertions)
check_widget_layout.py  build-time RemoteViews whitelist guard
stable-ids.txt          pinned resource ids (widget-update safety)
.github/workflows/      weekly timetable Action (catalog.gz release)
debug.keystore          shared debug signing key
src/com/zai/divia/      app code (widget provider, refresh engine, activities)
src/com/zai/divia/core/ pure-Java core: catalog, GTFS-RT parser, RSS reader
res/                    layouts, strings (FR/EN), day/night themes, icon
assets/catalog.gz       compact digest of the static GTFS (bundled data)
test/                   JVM harness + android stubs
tools/                  catalog generator + the data-audit kit (python)
docs/                   preview image
```

The core (`core/`) is pure Java with no Android imports, so the whole
parsing/rendering pipeline runs on a desktop JVM — that is what
`run-tests.sh` exercises (1051 assertions; the live-feed sections vary
slightly with what the network serves): layout whitelist, full
render pipeline against the real catalog and live feed, badge colors,
row counts, commute split, adapted-day chip, last-departure ("dernier")
tagging, widget style presets, catalog expiry math, RSS parsing, notification
building, one-shot and recurring alert scheduling (arming, re-aiming,
firing) and the GitHub-release parsing pipeline
(MiniJson + version comparison + notes cleaning).

## How this app was built

**Disclosure: this project was developed with AI coding tools.** Large
parts of the source code, the test harness, the build tooling and this
documentation were produced with AI assistance, then reviewed, built,
installed and tested on real devices by the author (Snassyyy). Nothing
here is telemetry: the app makes no network call beyond the three listed
in *Data & credits*, and ships no analytics.

## Data & credits

- **Code:** © Snassyyy, MIT license (see `LICENSE`).
- **Data:** official open data of the Divia Mobilités network (operated by
  Keolis Dijon), redistributed via
  [transport.data.gouv.fr](https://transport.data.gouv.fr) (dataset
  `gtfs-diviamobilites`). Check the dataset page for the current data
  license terms; `assets/catalog.gz` is a derived digest of that feed and
  can be regenerated with `tools/gen_catalog.py`.
- The bundled catalog covers the network until **2027-01-03**; the app
  knows it (the catalog carries its own end date) and warns 30 days
  ahead — regenerate it with `tools/gen_catalog.py` and rebuild after
  that date.
- This project is not affiliated with Divia, Keolis Dijon or
  transport.data.gouv.fr. The app contacts exactly three sources:
  `proxy.transport.data.gouv.fr` (live departures), `information.divia.fr`
  (disruption announcements) and GitHub (`api.github.com` +
  `github.com`/`objects.githubusercontent.com` for releases and the update
  APK).
