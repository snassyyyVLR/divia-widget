/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia.core;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Minimal RSS reader for the DiviaMobilités info-trafic feed
 * (https://information.divia.fr/feed/ — a plain WordPress RSS 2.0 file).
 * Pure Java, hand-scanned (no XML parser dependency), so it runs both on
 * Android and in the JVM test harness.
 */
public final class TrafficFeed {

    public static final String FEED_URL = "https://information.divia.fr/feed/";

    /** One RSS item: only the fields we actually use. */
    public static final class Item {
        public final String title;
        public final String link;
        public final String guid;
        public final String category;
        public final long pubDateMs;      // 0 when unparsable

        Item(String title, String link, String guid, String category, long pubDateMs) {
            this.title = title;
            this.link = link;
            this.guid = guid;
            this.category = category;
            this.pubDateMs = pubDateMs;
        }

        public String toString() {
            return "Item[" + title + " @ " + new Date(pubDateMs) + "]";
        }
    }

    private TrafficFeed() {}

    /** Fetches and parses the feed; throws IOException on network problems. */
    public static List<Item> fetch(long timeoutMs) throws IOException {
        byte[] xml = DiviaApi.fetch(FEED_URL, (int) Math.min(timeoutMs, 60000));
        return parse(xml);
    }

    /** Parses RSS bytes; never throws, returns what it could scan out. */
    public static List<Item> parse(byte[] xml) {
        List<Item> out = new ArrayList<Item>(16);
        if (xml == null || xml.length == 0) return out;
        String s = new String(xml, java.nio.charset.StandardCharsets.UTF_8);
        int pos = 0;
        while (true) {
            int start = s.indexOf("<item>", pos);
            if (start < 0) break;
            int end = s.indexOf("</item>", start);
            if (end < 0) break;
            String block = s.substring(start + 6, end);
            pos = end + 7;
            out.add(new Item(
                    decode(tag(block, "title")),
                    decode(tag(block, "link")),
                    decode(tag(block, "guid")),
                    decode(tag(block, "category")),
                    parseDate(tag(block, "pubDate"))));
        }
        return out;
    }

    /**
     * Heuristic telling a service-disruption post from a promo/com post.
     * The feed mixes both; only the former should ring a notification.
     */
    public static boolean isDisruption(Item it) {
        if (it == null) return false;
        String s = ((it.title == null ? "" : it.title) + " "
                + (it.category == null ? "" : it.category)).toLowerCase(Locale.ROOT);
        final String[] keys = {
                "grève", "greve", "manifestation", "perturbation", "travaux",
                "remplacé", "remplace", "dévié", "devie", "modifié", "modifie",
                "adapté", "adapte", "annulé", "annule", "interrompu", "suspendu",
                "circulation", "info trafic", "info grève",
        };
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    // ---------------------------------------------------------------- helpers

    /** Content of the first <name>…</name> (CDATA stripped), or "". */
    static String tag(String block, String name) {
        int a = block.indexOf("<" + name);
        if (a < 0) return "";
        a = block.indexOf(">", a);
        if (a < 0) return "";
        a++;
        int b = block.indexOf("</" + name + ">", a);
        if (b < 0) return "";
        String v = block.substring(a, b).trim();
        // WordPress wraps values in CDATA more often than not.
        if (v.startsWith("<![CDATA[")) {
            v = v.substring(9);
            int c = v.indexOf("]]>");
            if (c >= 0) v = v.substring(0, c);
        }
        return v.trim();
    }

    /** RFC-822 date ("Thu, 06 Nov 2026 15:46:00 +0000") → epoch ms, or 0. */
    static long parseDate(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "EEE, dd MMM yyyy HH:mm:ss z",
                "EEE, dd MMM yyyy HH:mm Z",
        };
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ENGLISH);
                f.setLenient(true);
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** Decodes the handful of XML entities WordPress actually emits. */
    static String decode(String s) {
        if (s == null || s.isEmpty() || s.indexOf('&') < 0) return s == null ? "" : s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                continue;
            }
            int sc = s.indexOf(';', i);
            if (sc < 0 || sc - i > 10) {
                out.append(c);
                continue;
            }
            String ent = s.substring(i + 1, sc);
            if ("amp".equals(ent)) out.append('&');
            else if ("lt".equals(ent)) out.append('<');
            else if ("gt".equals(ent)) out.append('>');
            else if ("quot".equals(ent)) out.append('"');
            else if ("apos".equals(ent)) out.append('\'');
            else if (ent.length() > 1 && ent.charAt(0) == '#') {
                try {
                    int code = ent.charAt(1) == 'x' || ent.charAt(1) == 'X'
                            ? Integer.parseInt(ent.substring(2), 16)
                            : Integer.parseInt(ent.substring(1));
                    out.append((char) code);
                } catch (NumberFormatException e) {
                    out.append(c);
                    continue;
                }
            } else {
                out.append(c);
                continue;
            }
            i = sc;
        }
        return out.toString();
    }
}
