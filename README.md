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

Current release: **release-1.4.0** — version numbering follows the public
GitHub release line (previous public drop: release-1.3.0). `versionCode`
stays monotonic (now 14). 1.4.0 switches to the private release signing
key (see *Signing* below): installing it over a 1.2.x/1.3.x build requires
a one-time uninstall; from 1.4.0 on, updates install in place again.

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
- **In-app updater (1.2.0)** — checks this repo's GitHub Releases
  (`snassyyyVLR/divia-widget`) once a day (plus a manual button in
  Settings); a home-screen banner offers the new version, one tap downloads
  the APK and hands it to Android's own installer. The repo slug lives in a
  single constant: `REPO` in `src/com/zai/divia/core/ReleaseCheck.java`.
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

1. Grab `DiviaWidget-debug.apk` from the [releases](../../releases) page
   (or build it — see below) and install it (allow "install unknown apps"
   once).
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
  baked in. `stable-ids.txt` pins every resource id (268 as of 1.3.0,
  append-only), and the build fails if anything drifts. Each release's pin
  set is verified resource-by-resource against the previously published
  APK (`aapt2 dump resources` on both), so widgets survive the update
  untouched — including the five-row ids in 1.2.0 and the updater ids in
  1.3.0.

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
AndroidManifest.xml     app manifest (version 1.4.0, API 21-34)
build.sh                raw build-tools build (aapt2 → ecj/javac → d8 → zipalign → apksigner)
run-tests.sh            JVM test harness runner (1025 assertions)
check_widget_layout.py  build-time RemoteViews whitelist guard
stable-ids.txt          pinned resource ids (widget-update safety)
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
`run-tests.sh` exercises (1025 assertions; the live-feed sections vary
slightly with what the network serves): layout whitelist, full
render pipeline against the real catalog and live feed, badge colors,
row counts, commute split, adapted-day chip, RSS parsing, notification
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
- The bundled catalog covers the network until **2027-01-03**; after that
  date, regenerate it and rebuild.
- This project is not affiliated with Divia, Keolis Dijon or
  transport.data.gouv.fr. The app contacts exactly three sources:
  `proxy.transport.data.gouv.fr` (live departures), `information.divia.fr`
  (disruption announcements) and GitHub (`api.github.com` +
  `github.com`/`objects.githubusercontent.com` for releases and the update
  APK).
