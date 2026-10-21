package com.toemod;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Backup files in Download/TOE3Stormline/backups, where they survive uninstalling the game and can be
 * copied off the device. Android 10+ writes through MediaStore without a permission and lists only the
 * files this install wrote (older ones are restored through the document picker). Android 6-9 needs the
 * storage permission; without it backups go to the app's own external files directory, which Android
 * deletes with the game. Worker thread only.
 */
@SuppressWarnings("deprecation") // getExternalStoragePublicDirectory: the Android 6-9 path only
final class LocalBackups {
    /** Shared folder, relative to the storage root. */
    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/TOE3Stormline/backups";

    private static final String TAG = Menu.TAG;
    private static final String MIME = "application/gzip";
    private static final Comparator<Backup> NEWEST_FIRST = new Comparator<Backup>() {
        @Override
        public int compare(Backup a, Backup b) {
            return b.name.compareTo(a.name);
        }
    };

    /** One backup file the mod wrote. */
    static final class Backup {
        final String name;
        final Uri uri;
        final long size;

        Backup(String name, Uri uri, long size) {
            this.name = name;
            this.uri = uri;
            this.size = size;
        }
    }

    private LocalBackups() {}

    /** True when backups go to the shared Download folder, false for the app-private fallback. */
    static boolean shared(Context c) {
        return Build.VERSION.SDK_INT >= 29
                || c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static Backup write(Context c, String name, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) return writeMediaStore(c, name, data);
        return writeFile(new File(shared(c) ? publicDir() : privateDir(c), name), data);
    }

    /** Every backup the mod can see, newest first. */
    static List<Backup> list(Context c) {
        List<Backup> all = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) listMediaStore(c, all);
        else if (shared(c)) listDir(publicDir(), all);
        listDir(privateDir(c), all);
        Collections.sort(all, NEWEST_FIRST);
        return all;
    }

    /** The bytes of a backup or of a document the player picked, refusing files over the snapshot limit. */
    static byte[] read(Context c, Uri uri) throws IOException, SnapshotException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Android could not open the file");
        try (InputStream src = in) {
            return SnapshotFormat.readAll(src, SnapshotFormat.MAX_GZIP_BYTES);
        }
    }

    /** Deletes the automatic backups beyond {@link BackupPolicy#KEEP_AUTO}; never touches any other file. */
    static void rotate(Context c) {
        List<Backup> all = list(c);
        List<String> names = new ArrayList<>(all.size());
        for (Backup b : all) names.add(b.name);
        List<String> doomed = BackupPolicy.autoToDelete(names, BackupPolicy.KEEP_AUTO);
        for (Backup b : all) {
            if (!doomed.contains(b.name)) continue;
            try {
                boolean gone = "file".equals(b.uri.getScheme()) ? new File(b.uri.getPath()).delete()
                        : c.getContentResolver().delete(b.uri, null, null) > 0;
                Log.i(TAG, "cloud: rotated " + b.name + (gone ? "" : " (not deleted)"));
            } catch (RuntimeException e) {
                Log.w(TAG, "cloud: could not delete " + b.name, e);
            }
        }
    }

    private static Backup writeMediaStore(Context c, String name, byte[] data) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("Android refused to create " + name);
        try {
            OutputStream out = cr.openOutputStream(uri);
            if (out == null) throw new IOException("Android could not open " + name);
            try (OutputStream dst = out) {
                dst.write(data);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (cr.update(uri, done, null, null) != 1) throw new IOException("Android could not finish " + name);
        } catch (IOException | RuntimeException e) {
            try {
                cr.delete(uri, null, null);
            } catch (RuntimeException ignored) {
                // the pending entry expires on its own
            }
            throw e;
        }
        return new Backup(name, uri, data.length);
    }

    /** Android 6-9: writes beside the target, then renames; never replaces an existing backup or leaves a .tmp. */
    private static Backup writeFile(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException("cannot create " + dir);
        if (f.exists()) throw new IOException(f.getName() + " already exists; wait a second and try again");
        File tmp = new File(dir, f.getName() + ".tmp");
        boolean renamed = false;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.getFD().sync();
            }
            renamed = tmp.renameTo(f);
            if (!renamed) throw new IOException("cannot write " + f);
        } finally {
            if (!renamed && tmp.exists() && !tmp.delete()) Log.w(TAG, "cloud: left " + tmp);
        }
        return new Backup(f.getName(), Uri.fromFile(f), data.length);
    }

    private static void listMediaStore(Context c, List<Backup> all) {
        String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE};
        String where = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.IS_PENDING + "=0";
        try (Cursor cur = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols, where,
                new String[] {FOLDER + "/"}, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String name = cur.getString(1);
                if (name == null || BackupPolicy.reason(name) == null) continue;
                all.add(new Backup(name, ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0)),
                        cur.getLong(2)));
            }
        }
    }

    private static void listDir(File dir, List<Backup> all) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && BackupPolicy.reason(f.getName()) != null) all.add(new Backup(f.getName(), Uri.fromFile(f), f.length()));
        }
    }

    private static File publicDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TOE3Stormline/backups");
    }

    private static File privateDir(Context c) {
        File ext = c.getExternalFilesDir("backups");
        return ext != null ? ext : new File(c.getFilesDir(), "backups");
    }
}
