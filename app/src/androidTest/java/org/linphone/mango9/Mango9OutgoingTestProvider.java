/* Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later */
package org.linphone.mango9;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic bytes only. Platform-only Java also runs in the independent test-provider process. */
public final class Mango9OutgoingTestProvider extends ContentProvider {
    private static final AtomicInteger queries = new AtomicInteger();
    private static final AtomicInteger stopped = new AtomicInteger();
    private static volatile byte[] fixture;
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        String type = uri.getQueryParameter("mime");
        return type == null ? "application/octet-stream" : type;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return query(uri, projection, selection, args, sort, null);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args,
            String sort, CancellationSignal signal) {
        String mode = uri.getQueryParameter("mode");
        if ("denied".equals(mode)) throw new SecurityException("Synthetic denied provider");
        if ("null".equals(mode)) return null;
        if ("slow".equals(mode)) {
            queries.incrementAndGet();
            try {
                for (int i = 0; i < 1000; i++) {
                    if (signal != null) signal.throwIfCanceled();
                    SystemClock.sleep(10);
                }
            } finally { stopped.incrementAndGet(); }
        }
        String[] columns = projection != null ? projection :
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                String name = uri.getQueryParameter("name");
                row[i] = name == null ? "../../cloud-file.bin" : name;
            }
            if (OpenableColumns.SIZE.equals(columns[i])) {
                String declared = uri.getQueryParameter("declared");
                row[i] = declared == null ? -1L : Long.parseLong(declared);
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return openAssetFile(uri, mode, null);
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException error) { throw new FileNotFoundException("Synthetic pipe unavailable"); }
        String length = uri.getQueryParameter("bytes");
        int count = length == null ? 32777 : Math.max(0, Math.min(1000000, Integer.parseInt(length)));
        final byte[] selected = "fixture".equals(uri.getQueryParameter("mode")) ? fixture : null;
        new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                    new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                if (selected != null) { output.write(selected); return; }
                byte[] bytes = new byte[1024];
                Arrays.fill(bytes, (byte) 7);
                for (int written = 0; written < count;) {
                    int size = Math.min(bytes.length, count - written);
                    output.write(bytes, 0, size);
                    written += size;
                    if ("stream".equals(uri.getQueryParameter("mode"))) SystemClock.sleep(10);
                }
            } catch (IOException ignored) { /* Reader cancellation is expected. */ }
        }, "SyntheticOutgoingProvider").start();
        return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("fixture".equals(method)) {
            byte[] bytes = extras == null ? null : extras.getByteArray("bytes");
            if (bytes != null && bytes.length > 262144) throw new IllegalArgumentException("Fixture too large");
            fixture = bytes == null ? null : bytes.clone();
        }
        Bundle result = new Bundle();
        result.putInt("queries", queries.get());
        result.putInt("stopped", stopped.get());
        return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
