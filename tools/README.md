# tools/

Python utilities that build and audit the app's data. All are plain
stdlib, no dependencies.

## gen_catalog.py — rebuild the bundled catalog

The app ships `assets/catalog.gz`, a compact line-based digest of the
official static GTFS (lines, stops, trips, line patterns, direction
labels, adapted-day flags). It expires when the operator publishes a new
fare/schedule edition; the app's About screen shows the current validity
date.

The `X|yyyymmdd|route_id,…` records encode **adapted-timetable days**:
this GTFS defines every service date explicitly (a Saturday can run a
special plan for a demonstration, a Wednesday the Sunday plan for
Armistice…). The generator compares each date's per-route trip count with
the modal count of that weekday — flagging only rare outliers, so the
normal school-term/holiday alternation is not mistaken for a disruption.
The app shows an "horaires adaptés" chip in the widget footer on those
days.

```bash
# 1. download the current GTFS zip from transport.data.gouv.fr
#    (dataset "gtfs-diviamobilites", resource gtfs-diviamobilites-current.zip)
# 2. unzip it somewhere, then:
python3 tools/gen_catalog.py /path/to/gtfs            # writes assets/catalog.gz
python3 tools/gen_catalog.py /path/to/gtfs out.gz     # or to a custom file
# 3. rebuild the APK: ./build.sh
```

## The data-audit kit

These scripts answer "is the feed telling the truth?", end to end. They
were used to audit the 2026-11-07 disruption day (see the README section
*Lateness, and why apps disagree*). Default input paths are
`/tmp/gtfs` (extracted static GTFS) and `/tmp/divia/rt.pb` (a saved
feed snapshot) — edit the constants at the top or pass the feed path as
an argument where supported.

| script | what it does |
| --- | --- |
| `probe_feed_time.py` | fetches the live feed, checks event times vs the clock (skew / timezone sanity) |
| `probe_delay.py` | stats over the `delay` field: how many events carry it, min/median/max |
| `dump_rt.py [rt.pb] [stop_id...]` | dumps feed events with trip/route/stop/predicted time/delay (times shown in your local timezone) |
| `check_schedule.py` | prints the official scheduled board of L5 at Suquet for 2026-11-07, from the static GTFS |
| `check_rt_vs_static.py` | **the auditor**: for every RT event, checks `predicted − delay` against the published timetable (2026-11-07 snapshot: 99.6% exact to the second) |
| `check_patterns.py` | prints every service pattern's evening board at Suquet — how date-by-date plans (strikes, demonstrations) differ from the regular Saturday |
| `check_feed_trips.py` | lists the vehicles actually present in a feed snapshot, cross-referenced with their service pattern |
| `parse_feed.py` | exploratory decoder used while designing the Java parser (`core/ProtoRt.java` mirrors it) |

Typical session:

```bash
mkdir -p /tmp/divia /tmp/gtfs
curl -sS -H 'Accept-Encoding: gzip' -o /tmp/divia/rt.pb \
  https://proxy.transport.data.gouv.fr/resource/divia-dijon-gtfs-rt-trip-update
# unzip the static GTFS into /tmp/gtfs, then:
python3 tools/check_rt_vs_static.py
```

Note: `rt.pb` snapshots are gzip-compressed protobuf — all scripts handle
the decompression themselves.
