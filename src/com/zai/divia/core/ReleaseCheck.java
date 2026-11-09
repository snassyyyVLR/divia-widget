/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia.core;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-app update source (v1.2.0): the GitHub Releases of this very project.
 *
 * Endpoint: {@code https://api.github.com/repos/<REPO>/releases/latest}
 * (unauthenticated, 60 checks/hour per IP — we do at most one quiet check
 * per day plus manual ones). Pure Java, so the JVM harness tests the exact
 * payload parsing used on device.
 *
 * REPO below is the single place to edit if the repository slug ever
 * moves; update_check_fail_fmt / update_repo_missing tell the user where
 * it lives.
 */
public final class ReleaseCheck {

    /** GitHub "owner/repo" of this project — edit here if it ever moves. */
    public static final String REPO = "snassyyyVLR/divia-widget";

    public static final String API_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/" + REPO + "/releases/latest";

    /** One GitHub release, reduced to what the updater actually needs. */
    public static final class Release {
        public final String tag;          // "release-1.2.0"
        public final String name;         // release title, "" when absent
        public final String pageUrl;      // html_url — browser fallback
        public final String apkUrl;       // browser_download_url of the .apk asset, "" when none
        public final String apkName;      // asset file name, "" when none
        public final long apkSize;        // asset size in bytes
        public final String notes;        // release body, markdown stripped
        public final String publishedAt;  // ISO-8601, "" when absent
        public final int[] version;       // parsed from the tag; null when unparsable
        public final String versionString;// "1.2.0" or the raw tag when unparsable

        public Release(String tag, String name, String pageUrl, String apkUrl,
                       String apkName, long apkSize, String notes, String publishedAt) {
            this.tag = tag == null ? "" : tag;
            this.name = name == null ? "" : name;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.apkName = apkName == null ? "" : apkName;
            this.apkSize = apkSize;
            this.notes = notes == null ? "" : notes;
            this.publishedAt = publishedAt == null ? "" : publishedAt;
            this.version = parseVersion(this.tag);
            this.versionString = this.version != null
                    ? this.version[0] + "." + this.version[1] + "." + this.version[2]
                    : this.tag;
        }

        /** True when this release sorts strictly above the installed a.b.c. */
        public boolean isNewerThan(String installedVersionName) {
            int[] cur = parseVersion(installedVersionName);
            return cur != null && version != null && isNewer(version, cur);
        }
    }

    private ReleaseCheck() {}

    /** Checks this project's repo; IOException on network / HTTP problems. */
    public static Release check(long timeoutMs) throws IOException {
        return check(REPO, timeoutMs);
    }

    /** Checks any repo (probe/harness use); IOException incl. HTTP 404 when the repo has no releases. */
    public static Release check(String repo, long timeoutMs) throws IOException {
        byte[] json = DiviaApi.fetch(
                "https://api.github.com/repos/" + repo + "/releases/latest",
                (int) Math.min(timeoutMs, 60000));
        return parse(json);
    }

    /** Parses a releases/latest JSON body; IllegalArgumentException when the shape is wrong. */
    public static Release parse(byte[] json) {
        if (json == null || json.length == 0) {
            throw new IllegalArgumentException("empty json");
        }
        Object root = MiniJson.parse(new String(json, java.nio.charset.StandardCharsets.UTF_8));
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("release json is not an object");
        }
        Map<?, ?> m = (Map<?, ?>) root;
        String tag = str(m, "tag_name");
        if (tag.isEmpty()) {
            throw new IllegalArgumentException("no tag_name (rate-limited or not a release)");
        }

        // First .apk asset wins (source zips are ignored on purpose).
        String apkUrl = "", apkName = "";
        long apkSize = 0;
        Object assets = m.get("assets");
        if (assets instanceof List) {
            for (Object a : (List<?>) assets) {
                if (!(a instanceof Map)) continue;
                Map<?, ?> am = (Map<?, ?>) a;
                String an = str(am, "name");
                if (an.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    apkUrl = str(am, "browser_download_url");
                    apkName = an;
                    apkSize = num(am, "size");
                    break;
                }
            }
        }

        return new Release(tag, str(m, "name"), str(m, "html_url"), apkUrl, apkName,
                apkSize, cleanNotes(str(m, "body"), 700), str(m, "published_at"));
    }

    /**
     * Tag → version triple: accepts "release-1.2.0", "v1.2", "1.2.3.4",
     * "release1.2.0"; missing components count as 0. Returns null when no
     * leading number survives (e.g. "beta") — such tags never auto-offer.
     */
    public static int[] parseVersion(String tag) {
        if (tag == null) return null;
        String t = tag.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("release-")) t = t.substring(8);
        else if (t.startsWith("release")) t = t.substring(7);
        if (t.startsWith("v")) t = t.substring(1);

        int[] out = {0, 0, 0};
        int found = 0;
        for (String part : t.split("[^0-9]+")) {
            if (part.isEmpty()) continue;
            if (part.length() > 9) return null;   // not a version, e.g. a date-ish blob
            out[found] = Integer.parseInt(part);
            if (++found == 3) break;
        }
        return found > 0 ? out : null;
    }

    /** Strictly-greater comparison, component by component. */
    public static boolean isNewer(int[] a, int[] b) {
        if (a == null || b == null) return false;
        for (int k = 0; k < 3; k++) {
            if (a[k] != b[k]) return a[k] > b[k];
        }
        return false;
    }

    /**
     * Markdown → dialog-friendly plain text: images and link targets go away
     * (link text stays), heading/bullet/quote markers, emphasis and code
     * fences are dropped, 3+ blank lines collapse. Capped at maxChars.
     */
    public static String cleanNotes(String md, int maxChars) {
        if (md == null || md.isEmpty()) return "";
        String s = md;
        s = s.replaceAll("(?s)```.*?```", " ");                       // fenced code blocks
        s = s.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");        // images → alt
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");         // links → text
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");                      // headings
        s = s.replaceAll("(?m)^\\s*([-*+])\\s+", "- ");               // bullets → dash
        s = s.replaceAll("(?m)^>\\s?", "");                           // quotes
        s = s.replace("**", "").replace("__", "")
             .replace("*", "").replace("`", "");                      // emphasis / inline code
        s = s.replaceAll("(?m)^\\s*[-*_]{3,}\\s*$", "");              // hr lines
        s = s.replaceAll("\n{3,}", "\n\n").trim();
        if (s.length() > maxChars) {
            int cut = s.lastIndexOf('\n', maxChars);
            if (cut < maxChars / 2) cut = s.lastIndexOf(' ', maxChars);
            if (cut < maxChars / 2) cut = maxChars;
            s = s.substring(0, cut).trim() + "…";
        }
        return s;
    }

    // ---------------------------------------------------------------- helpers

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : "";
    }

    private static long num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }
}
