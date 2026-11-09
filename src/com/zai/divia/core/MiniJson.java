/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained recursive-descent JSON parser (v1.2.0) — the GitHub Releases
 * API answers JSON, and this app takes no dependencies. Same philosophy as
 * ProtoRt: a few dozen lines of scanner that produce
 * LinkedHashMap / ArrayList / String / Long / Double / Boolean / null.
 *
 * Pure Java, so the JVM test harness exercises the exact parsing the app
 * performs against the real GitHub payloads.
 */
public final class MiniJson {

    /** Defensive cap: nested structures deeper than this are rejected. */
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;
    private int depth;

    private MiniJson(String s) {
        this.s = s;
    }

    /** Parses one complete JSON document; throws IllegalArgumentException on malformed input. */
    public static Object parse(String s) {
        if (s == null) throw new IllegalArgumentException("null json");
        MiniJson p = new MiniJson(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) {
            throw new IllegalArgumentException("trailing data at offset " + p.i);
        }
        return v;
    }

    // ---------------------------------------------------------------- scanner

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true");  return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null");  return null;
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        if (++depth > MAX_DEPTH) throw new IllegalArgumentException("too deep");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        expect('{');
        ws();
        if (peek() == '}') { i++; depth--; return out; }
        while (true) {
            ws();
            if (peek() != '"') throw new IllegalArgumentException("key expected at " + i);
            String key = string();
            ws();
            expect(':');
            ws();
            out.put(key, value());
            ws();
            char d = next();
            if (d == ',') continue;
            if (d == '}') { depth--; return out; }
            throw new IllegalArgumentException("',' or '}' expected at " + (i - 1));
        }
    }

    private List<Object> array() {
        if (++depth > MAX_DEPTH) throw new IllegalArgumentException("too deep");
        List<Object> out = new ArrayList<Object>();
        expect('[');
        ws();
        if (peek() == ']') { i++; depth--; return out; }
        while (true) {
            ws();
            out.add(value());
            ws();
            char d = next();
            if (d == ',') continue;
            if (d == ']') { depth--; return out; }
            throw new IllegalArgumentException("',' or ']' expected at " + (i - 1));
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                if (c < 0x20) throw new IllegalArgumentException("raw control char in string");
                out.append(c);
                continue;
            }
            if (i >= s.length()) throw new IllegalArgumentException("dangling escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"':  out.append('"');  break;
                case '\\': out.append('\\'); break;
                case '/':  out.append('/');  break;
                case 'b':  out.append('\b'); break;
                case 'f':  out.append('\f'); break;
                case 'n':  out.append('\n'); break;
                case 'r':  out.append('\r'); break;
                case 't':  out.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("short \\u escape");
                    try {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException("bad \\u escape");
                    }
                    i += 4;
                    break;
                default:
                    throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        boolean fp = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { i++; }
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                // '+'/'-' only valid inside the exponent of a well-formed doc;
                // we accept them while scanning and let Long/Double reject junk.
                if (c == '.' || c == 'e' || c == 'E') fp = true;
                if ((c == '+' || c == '-') && i > start
                        && s.charAt(i - 1) != 'e' && s.charAt(i - 1) != 'E') {
                    break;
                }
                i++;
            } else break;
        }
        if (i == start || (i == start + 1 && s.charAt(start) == '-')) {
            throw new IllegalArgumentException("value expected at " + start);
        }
        String tok = s.substring(start, i);
        try {
            return fp ? (Object) Double.parseDouble(tok) : (Object) Long.parseLong(tok);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("bad number '" + tok + "'");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) {
            throw new IllegalArgumentException("literal expected at " + i);
        }
        i += word.length();
    }

    // ---------------------------------------------------------------- chars

    private char peek() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end of input");
        return s.charAt(i);
    }

    private char next() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end of input");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalArgumentException("'" + c + "' expected at " + (i - 1));
        }
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }
}
