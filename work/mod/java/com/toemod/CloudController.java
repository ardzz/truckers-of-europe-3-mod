package com.toemod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Local backups and restores for the Cloud tab and the game's Save/Load buttons; process-wide like
 * {@link Updater}. State is confined to the main thread and file work runs, in order, on one background
 * thread. Holds only the application context. Independent of "Mods enabled".
 *
 * <p>A backup has to see the game's latest PlayerPrefs, which Unity writes to SharedPreferences only when
 * it saves. The menu's "Back up now" therefore first asks the Unity thread to save
 * ({@link Native#requestFlush}); the game's Save button saves before it calls in, and Unity saves on its
 * own when the activity stops.
 *
 * <p>Restore: validate, back up the current save ("prerestore"), replace every value in one commit, then
 * restart the process so Unity reloads PlayerPrefs from disk.
 */
final class CloudController {
    interface Listener {
        void onCloudChanged();

        /** The answer to {@link #listBackups}, newest first. */
        void onBackupsListed(List<LocalBackups.Backup> backups);

        /** A validated snapshot waiting for {@link #confirmRestore} or {@link #cancelRestore}. */
        void onRestoreReady(String source, SnapshotFormat.Snapshot snapshot, long installedBuild);
    }

    /** Native.onGameCloud actions; CloudAction in cloud.h must match. */
    static final int ACTION_SAVE = 1;
    static final int ACTION_LOAD = 2;
    static final int ACTION_FLUSHED = 3;

    private static final String TAG = Menu.TAG;
    private static final String STATE = "toemod_cloud";
    private static final String K_LAST_AUTO = "last_auto_backup";
    private static final long FLUSH_TIMEOUT_MS = 5000;
    private static final int DEVICE_MAX = 64;

    /** Written on the main thread, read by the Unity thread in {@link #onGameCloud}. */
    private static volatile CloudController sInstance;

    private final Context app;
    private final SharedPreferences state;
    private final String gameVersion;
    private final long installedBuild;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable r) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }
            }, "toemod-cloud");
            t.setDaemon(true);
            return t;
        }
    });
    private final Runnable flushTimeout = new Runnable() {
        @Override
        public void run() {
            if (!awaitingFlush) return;
            awaitingFlush = false;
            show("Backup failed: the game did not save within 5 seconds. Try again outside loading screens.", true);
        }
    };

    // Main thread only.
    private Listener listener;
    private int running;
    private boolean awaitingFlush;
    private boolean restoring;
    private String message = "";
    private boolean error;
    private SnapshotFormat.Snapshot pending;

    @SuppressWarnings("deprecation") // PackageInfo.versionCode before API 28
    private CloudController(Context app) {
        this.app = app;
        this.state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        String name = "";
        long build = 0;
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            name = pi.versionName;
            build = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "cloud: own package not found", e);
        }
        this.gameVersion = name;
        this.installedBuild = build;
    }

    /** Main thread only. */
    static CloudController get(Context c) {
        CloudController i = sInstance;
        if (i == null) {
            i = new CloudController(c.getApplicationContext());
            sInstance = i;
        }
        return i;
    }

    /** Unity thread (libtoemod.so). True when the mod takes the action, so the game's own cloud code is skipped. */
    static boolean onGameCloud(final int action) {
        final CloudController c = sInstance;
        if (c == null || action < ACTION_SAVE || action > ACTION_FLUSHED) return false;
        c.main.post(new Runnable() {
            @Override
            public void run() {
                c.onAction(action);
            }
        });
        return true;
    }

    private void onAction(int action) {
        if (action == ACTION_SAVE) {
            backup(BackupPolicy.Reason.MANUAL, true);
        } else if (action == ACTION_LOAD) {
            if (!Menu.openCloud()) toast("Open the TOE3 Stormline menu's Cloud tab to restore a backup.");
        } else if (awaitingFlush) {
            awaitingFlush = false;
            main.removeCallbacks(flushTimeout);
            backup(BackupPolicy.Reason.MANUAL, false);
        }
    }

    // ------------------------------------------------------------------ state for the UI

    void setListener(Listener l) {
        listener = l;
    }

    void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    boolean busy() {
        return running > 0 || awaitingFlush || restoring;
    }

    String message() {
        return message;
    }

    boolean isError() {
        return error;
    }

    /** Wall-clock time of the last automatic backup, 0 if none. */
    long lastAutoBackup() {
        return state.getLong(K_LAST_AUTO, 0);
    }

    /** False on Android 6-9 without the storage permission: backups then go to an app-private folder. */
    boolean sharedFolder() {
        return LocalBackups.shared(app);
    }

    // ------------------------------------------------------------------ backups

    /** The Cloud tab's "Back up now": the Unity thread saves PlayerPrefs first, then the backup runs. */
    void backupNow() {
        if (busy()) return;
        try {
            Native.requestFlush();
        } catch (UnsatisfiedLinkError e) {
            show("Backup failed: the mod's native library is not loaded, so the game cannot save first.", true);
            return;
        }
        awaitingFlush = true;
        main.postDelayed(flushTimeout, FLUSH_TIMEOUT_MS);
        show("Waiting for the game to save\u2026", false);
    }

    /**
     * UnityPlayerActivity stopped (Unity has saved): back up when the last automatic backup is 10 minutes
     * old. Only a written backup counts, so a failed one is retried at the next stop.
     */
    void onStop() {
        if (busy() || !BackupPolicy.autoDue(System.currentTimeMillis(), lastAutoBackup())) return;
        backup(BackupPolicy.Reason.AUTO, false);
    }

    private void backup(final BackupPolicy.Reason reason, final boolean fromGame) {
        if (restoring) return;
        final long now = System.currentTimeMillis();
        final SnapshotFormat.Meta meta = meta(reason, now);
        submit("Backing up\u2026", new Op<LocalBackups.Backup>() {
            @Override
            LocalBackups.Backup run() throws IOException {
                return write(reason, meta, now, GamePrefs.read(app));
            }

            @Override
            void done(LocalBackups.Backup b) {
                if (reason == BackupPolicy.Reason.AUTO) state.edit().putLong(K_LAST_AUTO, now).apply();
                show((reason == BackupPolicy.Reason.AUTO ? "Automatic backup: " : "Backed up: ") + b.name, false);
                if (fromGame) toast("TOE3 Stormline backed up the game to " + b.name);
            }

            @Override
            void failed(String why) {
                show("Backup failed: " + why, true);
                if (fromGame) toast("TOE3 Stormline backup failed: " + why);
            }
        });
    }

    /** Worker thread. */
    private LocalBackups.Backup write(BackupPolicy.Reason reason, SnapshotFormat.Meta meta, long now, Map<String, Object> prefs)
            throws IOException {
        byte[] gz = SnapshotFormat.gzip(SnapshotFormat.encode(meta, prefs));
        LocalBackups.Backup b = LocalBackups.write(app, BackupPolicy.fileName(now, TimeZone.getDefault(), reason), gz);
        Log.i(TAG, "cloud: " + prefs.size() + " values backed up to " + b.name + " (" + gz.length + " bytes)");
        if (reason == BackupPolicy.Reason.AUTO) LocalBackups.rotate(app);
        return b;
    }

    /** Lists local backups for {@link Listener#onBackupsListed}. */
    void listBackups() {
        submit(null, new Op<List<LocalBackups.Backup>>() {
            @Override
            List<LocalBackups.Backup> run() {
                return LocalBackups.list(app);
            }

            @Override
            void done(List<LocalBackups.Backup> backups) {
                if (listener != null) listener.onBackupsListed(backups);
            }
        });
    }

    // ------------------------------------------------------------------ restore

    /** Opens the system document picker; the result arrives in {@link #onPicked}. */
    void importFile(Activity a) {
        if (restoring) return;
        if (!CloudFragment.pick(a)) show("This device has no file picker.", true);
    }

    /** CloudFragment: the picked document, or null when the player cancelled. */
    void onPicked(Uri uri) {
        if (uri != null) prepareRestore(uri, null);
    }

    /**
     * Reads and validates a backup ({@code name} known) or a picked document ({@code name} null), then
     * asks the listener to confirm. Nothing changes before {@link #confirmRestore}.
     */
    void prepareRestore(final Uri uri, final String name) {
        if (restoring) return;
        pending = null;
        submit("Reading the backup\u2026", new Op<SnapshotFormat.Snapshot>() {
            private String source = name;

            @Override
            SnapshotFormat.Snapshot run() throws IOException, SnapshotException, JSONException {
                if (source == null) source = displayName(uri);
                String json = SnapshotFormat.gunzip(LocalBackups.read(app, uri));
                return SnapshotFormat.decode(JsonMaps.object(json), app.getPackageName(), installedBuild);
            }

            @Override
            void done(SnapshotFormat.Snapshot s) {
                pending = s;
                show("", false);
                if (listener != null) listener.onRestoreReady(source, s, installedBuild);
            }
        });
    }

    void cancelRestore() {
        pending = null;
    }

    /**
     * Backs up the current save on the worker, then on the main thread replaces it with the confirmed
     * snapshot and restarts the game straight away, leaving Unity no time to write its old values back.
     */
    void confirmRestore() {
        final SnapshotFormat.Snapshot s = pending;
        pending = null;
        if (s == null || restoring) return;
        restoring = true;
        final long now = System.currentTimeMillis();
        final SnapshotFormat.Meta meta = meta(BackupPolicy.Reason.PRERESTORE, now);
        submit("Restoring\u2026", new Op<Intent>() {
            private Map<String, Object> current;
            private LocalBackups.Backup safety;

            @Override
            Intent run() throws IOException, SnapshotException {
                Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
                if (launch == null || launch.getComponent() == null) {
                    throw new SnapshotException("Android offers no way to restart the game, so nothing was restored.");
                }
                current = GamePrefs.read(app);
                // A fresh install has nothing worth keeping, and encode() refuses an empty save.
                if (!current.isEmpty()) safety = write(BackupPolicy.Reason.PRERESTORE, meta, now, current);
                return Intent.makeRestartActivityTask(launch.getComponent());
            }

            @Override
            void done(Intent restart) {
                if (!GamePrefs.replace(app, s.prefs)) {
                    boolean kept = GamePrefs.replace(app, current);
                    failed("Android could not write the restored save. " + (kept ? "The current save is unchanged."
                            : safety != null ? "Restore " + safety.name + " to get it back." : "Restart the game."));
                    return;
                }
                Log.i(TAG, "cloud: restored " + s.prefs.size() + " values; restarting");
                app.startActivity(restart);
                Process.killProcess(Process.myPid());
            }

            @Override
            void failed(String why) {
                restoring = false;
                show("Restore failed: " + why, true);
            }
        });
    }

    // ------------------------------------------------------------------ storage permission (Android 6-9)

    void requestStorage(Activity a) {
        CloudFragment.requestStorage(a);
    }

    void onStorageResult(boolean granted) {
        show(granted ? "New backups go to " + LocalBackups.FOLDER + "."
                : "Storage access denied: backups stay in the game's own folder, which Android deletes with the game.", !granted);
    }

    // ------------------------------------------------------------------ plumbing

    /** One background job: {@link #run} on the worker, then {@link #done} or {@link #failed} on the main thread. */
    private abstract class Op<T> {
        abstract T run() throws Exception;

        abstract void done(T result);

        void failed(String why) {
            show(why, true);
        }
    }

    private <T> void submit(String working, final Op<T> op) {
        running++;
        if (working != null) show(working, false);
        else changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                String why = null;
                try {
                    result = op.run();
                } catch (Throwable t) {
                    Log.w(TAG, "cloud: operation failed", t);
                    why = explain(t);
                }
                final T r = result;
                final String w = why;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        running--;
                        if (w == null) op.done(r);
                        else op.failed(w);
                        changed();
                    }
                });
            }
        });
    }

    private static String explain(Throwable t) {
        if (t instanceof SnapshotException) return t.getMessage();
        if (t instanceof JSONException) return "This file is not a TOE3 Stormline backup.";
        if (t instanceof IllegalArgumentException) return t.getMessage() + ".";
        if (t instanceof SecurityException) return "Android denied access to the file.";
        if (t instanceof IOException) return "the file could not be read or written (" + t.getMessage() + ").";
        return t.toString();
    }

    private SnapshotFormat.Meta meta(BackupPolicy.Reason reason, long now) {
        UpdatePolicy.Identity mod = Updater.get(app).installed();
        String device = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        if (device.length() > DEVICE_MAX) device = device.substring(0, DEVICE_MAX);
        return new SnapshotFormat.Meta(app.getPackageName(), gameVersion, installedBuild, mod == null ? "" : mod.label(),
                SnapshotFormat.isoUtc(now), reason.id, device);
    }

    /** Worker thread: the picked document's file name, or a generic label. */
    private String displayName(Uri uri) {
        try (Cursor c = app.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "cloud: no display name for " + uri, e);
        }
        return "the chosen file";
    }

    private void show(String text, boolean isError) {
        message = text;
        error = isError;
        changed();
    }

    private void changed() {
        if (listener != null) listener.onCloudChanged();
    }

    private void toast(String text) {
        Toast.makeText(app, text, Toast.LENGTH_LONG).show();
    }
}
