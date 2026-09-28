package ir.meelano.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Minimal read-only file sharer (the project has no AndroidX, so no FileProvider).
 * Serves only files inside {@code cacheDir/share}, so other apps (WhatsApp, Telegram, e-mail)
 * can receive a PDF through a temporary read permission. Nothing else on the phone is reachable.
 */
public class MeelanoShareProvider extends ContentProvider {
    static final String SHARE_DIR = "share";

    static String authority(Context c) { return c.getPackageName() + ".share"; }

    /** Folder where files to be shared must be written. */
    static File shareDir(Context c) {
        File d = new File(c.getCacheDir(), SHARE_DIR);
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    static Uri uriFor(Context c, File f) {
        return new Uri.Builder().scheme("content").authority(authority(c)).appendPath(f.getName()).build();
    }

    /** Opens the system share sheet for a file that lives in {@link #shareDir(Context)}. */
    static void share(Context c, File f, String mime, String title) {
        Uri uri = uriFor(c, f);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, title);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(chooser);
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        if (c == null || uri == null || uri.getLastPathSegment() == null) throw new FileNotFoundException();
        File dir = shareDir(c);
        File f = new File(dir, uri.getLastPathSegment());
        try {
            // Block "../" tricks: the file must really be inside the share folder.
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new FileNotFoundException();
        } catch (IOException e) { throw new FileNotFoundException(); }
        if (!f.isFile()) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String n = uri == null || uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment().toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    /** Display name and size, which chat apps ask for before accepting a file. */
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cur = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        cur.addRow(row);
        return cur;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
