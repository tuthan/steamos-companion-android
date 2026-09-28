package io.github.tuthan.steamoscompanion.android.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Test APK provider runs in its own process, without the target app's Kotlin runtime. */
public final class DiagnosticTestProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.tuthan.steamoscompanion.test.diagnostics";
    public static final String FILE_NAME = "phase10-diagnostic-export.json";

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return new File(getContext().getCacheDir(), FILE_NAME).delete() ? 1 : 0;
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { return 0; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/export".equals(uri.getPath())) throw new FileNotFoundException("Unknown test document");
        File output = new File(getContext().getCacheDir(), FILE_NAME);
        int flags = "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY :
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY |
                        ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(output, flags);
    }
}
