/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Renders the header line badge as a small bitmap: a rounded pill filled
 * with the line's official GTFS color and the line number in a contrasting
 * color. RemoteViews cannot recolor a drawable per-line on old APIs, but it
 * can always set a bitmap — and the bitmap is drawn in OUR process, with
 * OUR density, so it lands pixel-perfect in the launcher.
 */
final class BadgeRender {

    private BadgeRender() {}

    /**
     * @param text     badge label ("L5", "T1", "18", "•"…)
     * @param colorHex GTFS route_color without '#' (may be null/invalid →
     *                 the app's default badge color is used)
     */
    static Bitmap make(Context c, String text, String colorHex) {
        try {
            if (text == null) text = "";
            float density = c.getResources().getDisplayMetrics().density;
            if (density <= 0f) density = 2f;

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(13f * density);
            float textW = p.measureText(text);

            int h = Math.round(22f * density);
            int w = Math.max(Math.round(40f * density),
                    Math.round(textW + 16f * density));

            Bitmap bm = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h),
                    Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);

            int bg = backgroundColor(c, colorHex);
            p.setColor(bg);
            float r = 7f * density;
            cv.drawRoundRect(0f, 0f, (float) w, (float) h, r, r, p);

            // Dark text on light line colors (e.g. yellow), white otherwise.
            int fg = luminance(bg) > 0.6f ? 0xFF1B1D22 : 0xFFFFFFFF;
            p.setColor(fg);
            p.setTextAlign(Paint.Align.CENTER);
            float baseline = h / 2f + (p.descent() - p.ascent()) / 2f;
            cv.drawText(text, w / 2f, baseline, p);
            return bm;
        } catch (Throwable t) {
            return null;   // the caller then simply keeps the view empty
        }
    }

    private static int backgroundColor(Context c, String colorHex) {
        try {
            if (colorHex != null) {
                String h = colorHex.trim();
                if (h.startsWith("#")) h = h.substring(1);
                if (h.length() == 6) {
                    return 0xFF000000 | (int) Long.parseLong(h, 16);
                }
                if (h.length() == 8) {
                    return (int) Long.parseLong(h, 16);
                }
            }
        } catch (Throwable ignored) {
        }
        return c.getResources().getColor(R.color.badge_bg);
    }

    /** Perceived brightness, 0..1 (ITU-R BT.601 weights). */
    static float luminance(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }
}
