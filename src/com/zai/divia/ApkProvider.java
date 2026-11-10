/*
 * Divia Widget — live bus & tram departures for Dijon (Android home-screen
 * widget, no dependencies, no accounts).
 * Copyright (c) 2026 Snassyyy — MIT License (see LICENSE).
 */
package com.zai.divia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * v1.6.1: hands the downloaded update APK to the system installer over a
 * content:// URI — the zero-dependency stand-in for a FileProvider.
 *
 * Why: some OEM ROMs (notably Xiaomi HyperOS/MIUI with "MIUI optimization"
 * on) kill a third-party app's PackageInstaller session before its
 * confirmation screen appears (first status callback = generic FAILURE,
 * empty message, no user action). Re-launching the install as
 * ACTION_VIEW on this provider's URI routes it through the ROM's own
 * installer UI instead — the classic sideload path every ROM allows.
 *
 * Serves exactly one file: the newest update-*.apk in the app's cache dir,
 * as recorded by UpdateFlow and re-validated on every access. URI shape:
 * content://com.zai.divia.apk/update-<tag>.apk
 *
 * The DISPLAY_NAME / SIZE columns use literal strings on purpose: the
 * android.jar this project compiles against carries wrong constants for
 * parts of the public API (see UpdateReceiver), so every cross-process
 * string used by the updater is spelled out and documented here.
 */
public final class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zai.divia.apk";

    /** MIME type of an Android package — the system installer's filter. */
    public static final String APK_MIME = "application/vnd.android.package-archive";

    /** OpenableColumns.DISPLAY_NAME / SIZE (literal, see class comment). */
    private static final String COL_DISPLAY_NAME = "_display_name";
    private static final String COL_SIZE = "_size";

    /** URI the installer is pointed at for a given cached update APK. */
    public static Uri uriFor(File apk) {
        return Uri.parse("content://" + AUTHORITY + "/" + apk.getName());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return APK_MIME;
    }

    /**
     * One-row answer for the columns installer UIs ask about (name, size).
     * Unknown requested columns come back null; a missing file yields an
     * empty cursor — openFile() then carries the actual failure.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File apk = resolve(uri);
        String[] cols = projection != null && projection.length > 0
                ? projection
                : new String[]{COL_DISPLAY_NAME, COL_SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        if (apk != null) {
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                row[i] = COL_DISPLAY_NAME.equals(cols[i]) ? apk.getName()
                        : COL_SIZE.equals(cols[i]) ? Long.valueOf(apk.length())
                        : null;
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File apk = resolve(uri);
        if (apk == null) {
            throw new FileNotFoundException("no cached update apk for " + uri);
        }
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /**
     * The referenced update-*.apk, when it is precisely a readable file of
     * ours directly inside the cache dir (no traversal, no other names).
     */
    private File resolve(Uri uri) {
        try {
            String name = uri != null ? uri.getLastPathSegment() : null;
            if (name == null || !name.startsWith("update-") || !name.endsWith(".apk")) {
                return null;
            }
            File dir = getContext() != null ? getContext().getCacheDir() : null;
            if (dir == null) return null;
            File apk = new File(dir, name);
            File parent = apk.getParentFile();
            if (parent == null
                    || !parent.getCanonicalPath().equals(dir.getCanonicalPath())) {
                return null;
            }
            return apk.isFile() && apk.canRead() ? apk : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
