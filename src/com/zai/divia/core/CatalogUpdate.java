/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v1.7.0: runtime timetable refresh — the pure halves of the flow
 * (GitHub-API scan + candidate validation). The app-side glue (throttle,
 * file cache, repaint) lives in RefreshLogic; this class stays
 * dependency-free so the JVM harness tests the exact logic the device
 * runs.
 *
 * Where the catalog comes from: ANY release of this repo may attach a
 * `catalog.gz` asset (the weekly "timetable" release does it through the
 * GitHub Action; a hand-cut release works too). The newest such asset —
 * by the asset's updated_at stamp — wins.
 */
public final class CatalogUpdate {

    /** One catalog asset found on a release. */
    public static final class Candidate {
        public final String url;      // browser_download_url
        public final String stamp;    // asset updated_at, ISO-8601 (lexicographically ordered)
        public final long size;       // bytes

        Candidate(String url, String stamp, long size) {
            this.url = url == null ? "" : url;
            this.stamp = stamp == null ? "" : stamp;
            this.size = size;
        }
    }

    private CatalogUpdate() {}

    /**
     * Scans a /releases list JSON body for the newest catalog.gz asset.
     * Returns null when no release carries one. Never throws — a bad body
     * just yields "nothing found".
     */
    public static Candidate findCatalog(byte[] json) {
        try {
            Object root = MiniJson.parse(new String(json, java.nio.charset.StandardCharsets.UTF_8));
            if (!(root instanceof List)) return null;
            Candidate best = null;
            for (Object o : (List<?>) root) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> rel = (Map<?, ?>) o;
                Object assets = rel.get("assets");
                if (!(assets instanceof List)) continue;
                for (Object a : (List<?>) assets) {
                    if (!(a instanceof Map)) continue;
                    Map<?, ?> am = (Map<?, ?>) a;
                    String name = str(am, "name");
                    if (name == null || !name.toLowerCase(Locale.ROOT).equals("catalog.gz")) continue;
                    String stamp = str(am, "updated_at");
                    Candidate c = new Candidate(str(am, "browser_download_url"),
                            stamp, num(am, "size"));
                    if (best == null || c.stamp.compareTo(best.stamp) > 0) best = c;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Is a downloaded catalog.gz usable? It must parse AND cover at least
     * as far as the catalogue it would replace (no downgrade: a stale
     * asset must never shorten the expiry banner). Never throws — an
     * unparsable file is simply rejected.
     */
    public static boolean validate(byte[] gz, String minValidUntil) {
        if (gz == null || gz.length < 64) return false;
        try {
            Catalog cat = new Catalog(new ByteArrayInputStream(gz));
            String until = cat.validUntil == null ? "" : cat.validUntil;
            if (until.length() != 8) return false;
            if (minValidUntil != null && minValidUntil.length() == 8
                    && until.compareTo(minValidUntil) < 0) {
                return false;
            }
            return !cat.stopNames.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static long num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** Unused on device; kept for the harness to build error paths. */
    static IOException boom() {
        return new IOException("catalog-update boom");
    }
}
