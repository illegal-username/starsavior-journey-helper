package helper.journey.starsavior;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Grants read access only to explicitly shared, short-lived PNG/JSON files. No general file paths. */
public final class ReportAttachmentProvider extends ContentProvider {
    static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    static File directory(Context context) { return new File(context.getCacheDir(), "reports"); }

    static void prune(Context context) {
        File[] files = directory(context).listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() <= cutoff) file.delete();
        }
    }

    static Uri uri(File file) {
        return new Uri.Builder().scheme("content").authority(BuildConfig.APPLICATION_ID + ".reports")
                .appendPath(file.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme())
                || !(BuildConfig.APPLICATION_ID + ".reports").equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPathSegments().size() != 1
                || !uri.getLastPathSegment().matches("[a-f0-9-]{36}\\.(png|json)")) {
            throw new FileNotFoundException("Invalid report attachment");
        }
        File file = new File(directory(getContext()), uri.getLastPathSegment());
        if (!file.isFile()) throw new FileNotFoundException("Report attachment unavailable");
        if (file.lastModified() <= System.currentTimeMillis() - MAX_AGE_MS) {
            file.delete();
            throw new FileNotFoundException("Report attachment expired");
        }
        return file;
    }

    @Override public boolean onCreate() { prune(getContext()); return true; }
    @Override public String getType(Uri uri) {
        return uri.getPath() != null && uri.getPath().endsWith(".json") ? "application/json" : "image/png";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        try {
            File file = resolve(uri);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName().endsWith(".json") ? "diagnostics.json" : "recognition.png";
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values);
        } catch (FileNotFoundException ignored) { }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
