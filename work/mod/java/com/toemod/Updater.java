package com.toemod;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.SSLException;

/**
 * In-app updater for the GitHub Releases channel named in the APK's signed build identity.
 * Every step is user-initiated (Check, Download, Install) and Android always asks the user to
 * confirm the install. State is confined to the main thread; network and disk work runs on one
 * background thread. Holds only the application context, never an Activity.
 */
final class Updater {
    enum Phase { LOADING, UNAVAILABLE, IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, INSTALLING, DONE }

    interface Listener {
        void onUpdaterChanged();
    }

    /** The release found by the latest successful check and its "Changes in this build" text (null if none). */
    static final class Changelog {
        final UpdatePolicy.Identity release;
        final String changes;

        Changelog(UpdatePolicy.Identity release, String changes) {
            this.release = release;
            this.changes = changes;
        }
    }

    static final String ACTION_INSTALL_STATUS = "com.toemod.action.INSTALL_STATUS";

    private static final String TAG = Menu.TAG;
    private static final String CACHE_DIR = "toemod-ota";
    private static final String USER_AGENT = "toemod-updater";
    private static final String ACCEPT_GITHUB_JSON = "application/vnd.github+json";
    private static final String ACCEPT_BINARY = "application/octet-stream";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long PROGRESS_INTERVAL_MS = 200;
    private static final long SPACE_MARGIN = 64L << 20;

    private static Updater sInstance;

    private final Context app;
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
            }, "toemod-updater");
            t.setDaemon(true);
            return t;
        }
    });

    // Main thread only.
    private Listener listener;
    private Phase phase = Phase.LOADING;
    private String message = "Reading installed build\u2026";
    private boolean error;
    private UpdatePolicy.Identity installed;
    private String installedCert;
    private UpdatePolicy.Release release;
    private List<UpdatePolicy.Download> plan;
    private Changelog changelog;
    private long progressDone;
    private long progressTotal;
    private int sessionId = -1;
    private boolean installPermitted = true;

    /** Set on the main thread, polled by the worker between 64 KiB chunks. */
    private volatile boolean cancelRequested;

    private Updater(Context app) {
        this.app = app;
    }

    /** Main thread only. */
    static Updater get(Context c) {
        if (sInstance == null) {
            sInstance = new Updater(c.getApplicationContext());
            sInstance.load();
        }
        return sInstance;
    }

    // ------------------------------------------------------------------ state for the UI

    Phase phase() {
        return phase;
    }

    String message() {
        return message;
    }

    boolean isError() {
        return error;
    }

    UpdatePolicy.Identity installed() {
        return installed;
    }

    /** The release being offered, downloaded or installed; null otherwise. */
    UpdatePolicy.Release release() {
        return release;
    }

    /** Notes of the latest successfully checked release; null before a check succeeds. */
    Changelog changelog() {
        return changelog;
    }

    boolean showsProgress() {
        return phase == Phase.DOWNLOADING || (phase == Phase.INSTALLING && sessionId == -1);
    }

    long progressDone() {
        return progressDone;
    }

    long progressTotal() {
        return progressTotal;
    }

    int progressPermille() {
        return progressTotal <= 0 ? 0 : (int) Math.min(1000, progressDone * 1000 / progressTotal);
    }

    /** False when Android 8+ still needs the user to allow installs from this app. */
    boolean installPermitted() {
        return installPermitted;
    }

    String releasePage() {
        return release == null ? null : UpdatePolicy.releasePage(release.identity.repository, release.identity.tag).toString();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    // ------------------------------------------------------------------ user actions (main thread)

    /** The user may have changed "Install unknown apps" in Settings meanwhile. */
    void onResume() {
        if (phase != Phase.READY) return;
        boolean before = installPermitted;
        if (refreshInstallPermission() != before) changed();
    }

    void check() {
        if (installed == null || (phase != Phase.IDLE && phase != Phase.AVAILABLE)) return;
        final UpdatePolicy.Identity inst = installed;
        final String cert = installedCert;
        release = null;
        plan = null;
        changelog = null;
        enter(Phase.CHECKING, "Checking GitHub for the latest release\u2026", false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final CheckResult r = fetchLatest(inst, cert);
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            onChecked(r);
                        }
                    });
                } catch (Throwable t) {
                    final String msg = explain(t, "Update check failed");
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            enter(Phase.IDLE, msg, true);
                        }
                    });
                }
            }
        });
    }

    void download() {
        if (phase != Phase.AVAILABLE || release == null) return;
        final UpdatePolicy.Release rel = release;
        final List<UpdatePolicy.Download> downloads = plan;
        final String cert = installedCert;
        cancelRequested = false;
        progressDone = 0;
        progressTotal = rel.totalSize;
        enter(Phase.DOWNLOADING, "Preparing download\u2026", false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    fetchAll(rel, downloads, cert);
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            refreshInstallPermission();
                            enter(Phase.READY, "Update " + describe(rel.identity) + " is downloaded and verified.", false);
                        }
                    });
                } catch (Throwable t) {
                    final boolean cancelled = cancelRequested;
                    final String msg = cancelled ? "Download cancelled." : explain(t, "Download failed");
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            enter(Phase.AVAILABLE, msg, !cancelled);
                        }
                    });
                }
            }
        });
    }

    /**
     * Opens the "Install unknown apps" setting if Android 8+ still needs it, otherwise stages
     * the verified APK set in one PackageInstaller session and commits it; Android then asks
     * the user to confirm. {@code activity} is used only synchronously, to open Settings.
     */
    void install(Activity activity) {
        if (phase != Phase.READY || release == null) return;
        if (!refreshInstallPermission()) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + app.getPackageName())));
                enter(Phase.READY, "Allow \"Install unknown apps\" for this app, then come back and tap Install.", false);
            } catch (ActivityNotFoundException e) {
                enter(Phase.READY, "Allow this app under Settings > Apps > Special app access > Install unknown apps, then tap Install.", true);
            }
            return;
        }
        final UpdatePolicy.Release rel = release;
        final String cert = installedCert;
        cancelRequested = false;
        sessionId = -1;
        progressDone = 0;
        progressTotal = rel.totalSize;
        enter(Phase.INSTALLING, "Verifying files for the Android installer\u2026", false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final int id = stage(rel, cert);
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            if (phase != Phase.INSTALLING || sessionId != -1) return;
                            sessionId = id;
                            enter(Phase.INSTALLING, "Confirm the update in the Android installer prompt.", false);
                        }
                    });
                } catch (final CorruptCache e) {
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            enter(Phase.AVAILABLE, "Downloaded files failed re-verification (" + e.getMessage() + "). Download again.", true);
                        }
                    });
                } catch (Throwable t) {
                    final boolean cancelled = cancelRequested;
                    final String msg = cancelled ? "Install cancelled." : explain(t, "Install failed");
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            enter(Phase.READY, msg, !cancelled);
                        }
                    });
                }
            }
        });
    }

    /** Stops a download or the install preparation, or abandons a committed install session. */
    void cancel() {
        if (phase == Phase.DOWNLOADING || (phase == Phase.INSTALLING && sessionId == -1)) {
            cancelRequested = true;
            enter(phase, "Cancelling\u2026", false);
        } else if (phase == Phase.INSTALLING) {
            abandonSession(sessionId);
            sessionId = -1;
            enter(Phase.READY, "Install cancelled.", false);
        }
    }

    // ------------------------------------------------------------------ installer callbacks (UpdateInstallActivity)

    void abandonSession(final int id) {
        if (id < 0) return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                abandon(app.getPackageManager().getPackageInstaller(), null, id);
            }
        });
    }

    void onInstallStatus(int id, int status, String detail) {
        if (phase != Phase.INSTALLING || (sessionId != -1 && sessionId != id)) return;
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            sessionId = id;
            enter(Phase.INSTALLING, "Confirm the update in the Android installer prompt.", false);
            return;
        }
        sessionId = -1;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            release = null;
            plan = null;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        pruneCache(null);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "cannot clear the update cache", e);
                    }
                }
            });
            enter(Phase.DONE, statusText(status, detail), false); // this process still runs the old build
        } else {
            enter(Phase.READY, statusText(status, detail), status != PackageInstaller.STATUS_FAILURE_ABORTED);
        }
    }

    static String statusText(int status, String detail) {
        String s;
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                return "Update installed. Start the game again to use it.";
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "Install cancelled.";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                s = "Install blocked by Android";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                s = "Install conflicts with the installed app";
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                s = "Update is incompatible with this device";
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                s = "Android rejected the APK set as invalid";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                s = "Not enough storage to install the update";
                break;
            case PackageInstaller.STATUS_FAILURE_TIMEOUT:
                s = "Android installer timed out";
                break;
            default:
                s = "Install failed";
                break;
        }
        return detail == null || detail.isEmpty() ? s + "." : s + ": " + detail;
    }

    static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** The label with the game version, plus the numeric build when the label is a "vX.Y.Z" tag. */
    private static String describe(UpdatePolicy.Identity id) {
        return id.label() + " (" + (id.modVersion() != null ? "build " + id.modBuild + " \u00B7 " : "") + "game "
                + id.versionName + ")";
    }

    // ------------------------------------------------------------------ main-thread plumbing

    private void ui(Runnable r) {
        main.post(r);
    }

    private void enter(Phase p, String msg, boolean isError) {
        phase = p;
        message = msg;
        error = isError;
        changed();
    }

    private void changed() {
        if (listener == null) return;
        try {
            listener.onUpdaterChanged();
        } catch (RuntimeException e) {
            Log.e(TAG, "updater UI refresh failed", e);
        }
    }

    private boolean refreshInstallPermission() {
        installPermitted = Build.VERSION.SDK_INT < 26 || app.getPackageManager().canRequestPackageInstalls();
        return installPermitted;
    }

    private void onChecked(CheckResult r) {
        UpdatePolicy.Identity n = r.release.identity;
        changelog = new Changelog(n, r.changes);
        switch (r.verdict) {
            case UPDATE:
                release = r.release;
                plan = r.plan;
                enter(Phase.AVAILABLE, "Update " + describe(n) + " is available: " + mb(r.release.totalSize) + " download.",
                        false);
                break;
            case NOT_NEWER:
                enter(Phase.IDLE, "No newer build: latest release " + n.tag + " is build " + n.modBuild + ".", false);
                break;
            default:
                enter(Phase.IDLE, "Up to date: " + n.tag + " is the latest release.", false);
                break;
        }
    }

    // ------------------------------------------------------------------ worker thread

    private static final class CheckResult {
        final UpdatePolicy.Verdict verdict;
        final UpdatePolicy.Release release;
        final List<UpdatePolicy.Download> plan;
        final String changes;

        CheckResult(UpdatePolicy.Verdict verdict, UpdatePolicy.Release release, List<UpdatePolicy.Download> plan,
                String changes) {
            this.verdict = verdict;
            this.release = release;
            this.plan = plan;
            this.changes = changes;
        }
    }

    /** A cached download no longer matches update.json; it was deleted and must be fetched again. */
    private static final class CorruptCache extends Exception {
        private static final long serialVersionUID = 1L;

        CorruptCache(String message) {
            super(message);
        }
    }

    /** Publishes byte progress to the main thread at most every {@link #PROGRESS_INTERVAL_MS}; aborts on cancel. */
    private final class Meter implements UpdatePolicy.Progress {
        private final Phase stage;
        private long done;
        private long lastPost;
        private String label = "";

        Meter(Phase stage) {
            this.stage = stage;
        }

        void label(String text) {
            label = text;
            publish();
        }

        @Override
        public void advance(long bytes) throws IOException {
            if (cancelRequested) throw new InterruptedIOException("cancelled");
            done += bytes;
            if (SystemClock.uptimeMillis() - lastPost >= PROGRESS_INTERVAL_MS) publish();
        }

        private void publish() {
            lastPost = SystemClock.uptimeMillis();
            final long d = done;
            final String text = label;
            ui(new Runnable() {
                @Override
                public void run() {
                    if (phase != stage) return;
                    progressDone = d;
                    enter(stage, text, false);
                }
            });
        }
    }

    private void load() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PackageInfo pi = installedPackage();
                    final UpdatePolicy.Identity id = readInstalledIdentity(pi);
                    final String cert = signerSha256(pi);
                    deleteTree(releaseDir(id.tag)); // already installed: its download is no longer needed
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            installed = id;
                            installedCert = cert;
                            enter(Phase.IDLE, "", false);
                        }
                    });
                } catch (Throwable t) {
                    final String msg = explain(t, "In-app updates unavailable");
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            enter(Phase.UNAVAILABLE, msg, true);
                        }
                    });
                }
            }
        });
    }

    @SuppressWarnings("deprecation") // GET_SIGNATURES is the only signer query before API 28
    private PackageInfo installedPackage() throws PackageManager.NameNotFoundException {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return app.getPackageManager().getPackageInfo(app.getPackageName(), flags);
    }

    private UpdatePolicy.Identity readInstalledIdentity(PackageInfo pi) throws IOException, UpdateException {
        byte[] raw;
        try (InputStream in = app.getAssets().open(UpdatePolicy.BUILD_ASSET)) {
            raw = UpdatePolicy.readBounded(in, UpdatePolicy.MAX_BUILD_JSON, "build.json");
        } catch (FileNotFoundException e) {
            throw new UpdateException("this APK has no signed build identity (assets/" + UpdatePolicy.BUILD_ASSET + ")");
        }
        UpdatePolicy.Identity id = UpdatePolicy.identity(json(raw, "build.json"), false);
        if (!id.packageName.equals(pi.packageName) || id.versionCode != versionCode(pi)) {
            throw new UpdateException("build.json describes " + id.packageName + " version code " + id.versionCode
                    + ", but the installed app is " + pi.packageName + " version code " + versionCode(pi));
        }
        return id;
    }

    private CheckResult fetchLatest(UpdatePolicy.Identity inst, String cert) throws IOException, UpdateException {
        byte[] body = fetch(UpdatePolicy.latestReleaseUrl(inst.repository), ACCEPT_GITHUB_JSON,
                UpdatePolicy.MAX_RELEASE_JSON, "GitHub release");
        UpdatePolicy.GithubRelease gh = UpdatePolicy.githubRelease(json(body, "GitHub release"), inst.repository);
        if (!gh.has(UpdatePolicy.MANIFEST_NAME)) {
            throw new UpdateException("latest release " + gh.tag + " has no " + UpdatePolicy.MANIFEST_NAME
                    + " (it predates in-app updates); install it manually");
        }
        UpdatePolicy.Asset meta = gh.asset(UpdatePolicy.MANIFEST_NAME);
        if (meta.size > UpdatePolicy.MAX_MANIFEST) {
            throw new UpdateException(UpdatePolicy.MANIFEST_NAME + " is larger than " + UpdatePolicy.MAX_MANIFEST + " bytes");
        }
        byte[] raw = fetch(meta.url, ACCEPT_BINARY, UpdatePolicy.MAX_MANIFEST, UpdatePolicy.MANIFEST_NAME);
        UpdatePolicy.Release rel = UpdatePolicy.release(json(raw, UpdatePolicy.MANIFEST_NAME));
        List<UpdatePolicy.Download> downloads = UpdatePolicy.plan(rel, gh);
        UpdatePolicy.Verdict verdict = UpdatePolicy.decide(inst, cert, rel);
        pruneCache(verdict == UpdatePolicy.Verdict.UPDATE ? rel.identity.tag : null);
        return new CheckResult(verdict, rel, downloads, gh.changes);
    }

    /**
     * Downloads (or re-verifies already cached) APKs, base first so a wrong package or signer
     * fails before the large splits are fetched. Each APK must match update.json and carry the
     * same signed identity.
     */
    private void fetchAll(UpdatePolicy.Release rel, List<UpdatePolicy.Download> downloads, String cert)
            throws IOException, UpdateException {
        pruneCache(rel.identity.tag);
        File dir = releaseDir(rel.identity.tag);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        long missing = 0;
        for (UpdatePolicy.Download d : downloads) {
            File f = new File(dir, d.apk.name);
            if (!f.isFile() || f.length() != d.apk.size) missing += d.apk.size;
        }
        long free = dir.getUsableSpace();
        if (free < missing + SPACE_MARGIN) {
            throw new UpdateException("not enough free storage: " + mb(missing + SPACE_MARGIN) + " needed, " + mb(free) + " free");
        }
        Meter meter = new Meter(Phase.DOWNLOADING);
        int i = 0;
        for (UpdatePolicy.Download d : downloads) {
            i++;
            String label = d.apk.name + " (" + i + "/" + downloads.size() + ")";
            File f = new File(dir, d.apk.name);
            boolean cached = false;
            if (f.isFile() && f.length() == d.apk.size) {
                meter.label("Verifying cached " + label);
                long mark = meter.done;
                try {
                    UpdatePolicy.verifyFile(f, d.apk.size, d.apk.sha256, meter, d.apk.name);
                    cached = true;
                } catch (UpdateException e) {
                    Log.w(TAG, "discarding cached " + d.apk.name + ": " + e.getMessage());
                    meter.done = mark;
                }
            }
            if (!cached) {
                meter.label("Downloading " + label);
                downloadOne(d, f, meter);
            }
            verifyApk(f, d.apk.name, rel, cert);
        }
    }

    private void downloadOne(UpdatePolicy.Download d, File dest, Meter meter) throws IOException, UpdateException {
        File part = new File(dest.getPath() + ".part");
        delete(dest);
        delete(part);
        HttpURLConnection c = open(d.url, ACCEPT_BINARY);
        try {
            String announced = c.getHeaderField("Content-Length");
            if (announced != null && !announced.trim().equals(Long.toString(d.apk.size))) {
                throw new UpdateException(d.apk.name + ": server announced " + announced.trim() + " bytes, expected " + d.apk.size);
            }
            String sha;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                sha = UpdatePolicy.copyExact(in, out, d.apk.size, meter, d.apk.name);
                out.getFD().sync();
            }
            if (!sha.equals(d.apk.sha256)) throw new UpdateException(d.apk.name + " failed its SHA-256 check (corrupted download)");
            if (!part.renameTo(dest)) throw new IOException("cannot store " + dest);
        } finally {
            c.disconnect();
            delete(part);
        }
    }

    /**
     * The base APK must be this package and game version, signed with the installed certificate;
     * every APK must carry the release's signed build identity (so an older APK of the same
     * game version cannot be mixed in). Deletes the file when it fails.
     */
    private void verifyApk(File f, String name, UpdatePolicy.Release rel, String cert) throws IOException, UpdateException {
        try {
            if (name.equals(rel.baseApk)) verifyBasePackage(f, rel, cert);
            byte[] raw = UpdatePolicy.readZipEntry(f, UpdatePolicy.BUILD_ENTRY, UpdatePolicy.MAX_BUILD_JSON, name);
            UpdatePolicy.requireEmbedded(UpdatePolicy.identity(json(raw, name + " build.json"), true), rel, name);
        } catch (IOException | UpdateException e) {
            delete(f);
            throw e;
        }
    }

    @SuppressWarnings("deprecation") // GET_SIGNATURES: archive signers before API 28, and on API 28-29 the flag that collects them
    private void verifyBasePackage(File apk, UpdatePolicy.Release rel, String cert) throws UpdateException {
        int flags = PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(apk.getPath(), flags);
        if (pi == null) throw new UpdateException("Android cannot read the downloaded " + rel.baseApk);
        UpdatePolicy.Identity id = rel.identity;
        if (!id.packageName.equals(pi.packageName)) {
            throw new UpdateException(rel.baseApk + " is package " + pi.packageName + ", expected " + id.packageName);
        }
        if (versionCode(pi) != id.versionCode || !id.versionName.equals(pi.versionName)) {
            throw new UpdateException(rel.baseApk + " is version " + pi.versionName + " (" + versionCode(pi) + "), expected "
                    + id.versionName + " (" + id.versionCode + ")");
        }
        if (!cert.equals(signerSha256(pi))) {
            throw new UpdateException(rel.baseApk + " is not signed with this installation's certificate");
        }
    }

    /**
     * Re-verifies the cache and streams every APK into ONE full-install session (hashing exactly
     * the bytes that are written), then commits it. Any failure before the commit abandons the
     * session, so nothing is ever partially installed.
     */
    private int stage(UpdatePolicy.Release rel, String cert) throws IOException, UpdateException, CorruptCache {
        File dir = releaseDir(rel.identity.tag);
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo s : installer.getMySessions()) abandon(installer, null, s.getSessionId());

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(rel.totalSize);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);

        Meter meter = new Meter(Phase.INSTALLING);
        int id = installer.createSession(params);
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            session = installer.openSession(id);
            for (UpdatePolicy.Apk apk : rel.apks) {
                File f = new File(dir, apk.name);
                meter.label("Preparing " + apk.name + " for the Android installer");
                try {
                    if (!f.isFile() || f.length() != apk.size) throw new UpdateException(apk.name + " is missing or has the wrong size");
                    verifyApk(f, apk.name, rel, cert);
                } catch (IOException | UpdateException e) {
                    delete(f);
                    throw new CorruptCache(e.getMessage());
                }
                String sha;
                try (InputStream in = new FileInputStream(f); OutputStream out = session.openWrite(apk.name, 0, apk.size)) {
                    sha = UpdatePolicy.copyExact(in, out, apk.size, meter, apk.name);
                    session.fsync(out);
                } catch (UpdateException e) { // the cached file changed size since it was verified
                    delete(f);
                    throw new CorruptCache(e.getMessage());
                }
                if (!sha.equals(apk.sha256)) {
                    delete(f);
                    throw new CorruptCache(apk.name + " changed after it was downloaded");
                }
            }
            session.commit(installCallback(id).getIntentSender());
            committed = true;
            return id;
        } finally {
            if (!committed) abandon(installer, session, id);
            if (session != null) session.close();
        }
    }

    /** Explicit, non-exported callback; the installer fills in the status extras, so it must stay mutable. */
    private PendingIntent installCallback(int id) {
        Intent intent = new Intent(app, UpdateInstallActivity.class).setAction(ACTION_INSTALL_STATUS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getActivity(app, id, intent, flags, callbackOptions());
    }

    /**
     * The installer sends the callback without its own launch privileges, and since API 34 a
     * targetSdk 35 creator must opt in before the system starts the callback activity on its
     * behalf (it still has to be visible, i.e. the game is in front).
     */
    @SuppressWarnings("deprecation") // MODE_BACKGROUND_ACTIVITY_START_ALLOWED: API 36 renamed it ALLOW_IF_VISIBLE
    private static Bundle callbackOptions() {
        if (Build.VERSION.SDK_INT < 34) return null;
        return ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle();
    }

    private static void abandon(PackageInstaller installer, PackageInstaller.Session session, int id) {
        try {
            if (session != null) session.abandon();
            else installer.abandonSession(id);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot abandon install session " + id, e);
        }
    }

    private static HttpURLConnection open(URI start, String accept) throws IOException, UpdateException {
        URI uri = UpdatePolicy.transport(start);
        for (int hop = 0; ; hop++) {
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            boolean keep = false;
            try {
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", accept);
                c.setRequestProperty("Accept-Encoding", "identity");
                if (ACCEPT_GITHUB_JSON.equals(accept)) c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (hop >= UpdatePolicy.MAX_REDIRECTS) throw new UpdateException("too many redirects from " + start.getHost());
                    uri = UpdatePolicy.redirect(uri, c.getHeaderField("Location"));
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) throw new UpdateException(httpError(c, code, uri));
                keep = true;
                return c;
            } finally {
                if (!keep) c.disconnect();
            }
        }
    }

    private static String httpError(HttpURLConnection c, int code, URI uri) {
        if ((code == 403 || code == 429) && "0".equals(c.getHeaderField("X-RateLimit-Remaining"))) {
            return "GitHub API rate limit reached; try again later";
        }
        if (code == 404 && "api.github.com".equals(uri.getHost())) return "no published release found (HTTP 404)";
        return "HTTP " + code + " from " + uri.getHost();
    }

    private static byte[] fetch(URI uri, String accept, int max, String what) throws IOException, UpdateException {
        HttpURLConnection c = open(uri, accept);
        try (InputStream in = c.getInputStream()) {
            return UpdatePolicy.readBounded(in, max, what);
        } finally {
            c.disconnect();
        }
    }

    private static Map<String, Object> json(byte[] raw, String what) throws UpdateException {
        try {
            JSONTokener t = new JSONTokener(new String(raw, StandardCharsets.UTF_8));
            Object v = t.nextValue();
            if (!(v instanceof JSONObject) || t.nextClean() != 0) throw new UpdateException(what + " is not a single JSON object");
            return toMap((JSONObject) v);
        } catch (JSONException e) {
            throw new UpdateException(what + " is not valid JSON (" + e.getMessage() + ")");
        }
    }

    private static Map<String, Object> toMap(JSONObject o) throws JSONException {
        Map<String, Object> m = new LinkedHashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            m.put(k, plain(o.get(k)));
        }
        return m;
    }

    private static Object plain(Object v) throws JSONException {
        if (v instanceof JSONObject) return toMap((JSONObject) v);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> l = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) l.add(plain(a.get(i)));
            return l;
        }
        return v == JSONObject.NULL ? null : v;
    }

    @SuppressWarnings("deprecation") // versionCode before API 28
    private static long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    /** SHA-256 of the single current signing certificate (apksigner's "certificate SHA-256 digest"). */
    @SuppressWarnings("deprecation") // PackageInfo.signatures before API 28
    private static String signerSha256(PackageInfo pi) throws UpdateException {
        Signature[] signers = pi.signatures;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            if (pi.signingInfo.hasMultipleSigners()) throw new UpdateException(pi.packageName + " has multiple signers");
            signers = pi.signingInfo.getApkContentsSigners();
        }
        if (signers == null || signers.length != 1) throw new UpdateException("cannot read the signing certificate of " + pi.packageName);
        return UpdatePolicy.sha256Hex(signers[0].toByteArray());
    }

    private static String explain(Throwable t, String prefix) {
        String why;
        if (t instanceof UpdateException) {
            why = t.getMessage();
        } else if (t instanceof UnknownHostException) {
            why = "no network connection";
        } else if (t instanceof SocketTimeoutException) {
            why = "the network timed out";
        } else if (t instanceof SSLException) {
            why = "secure connection failed (" + t.getMessage() + ")";
        } else if (t instanceof IOException) {
            why = "I/O error (" + t.getMessage() + ")";
        } else {
            why = t.toString();
        }
        Log.w(TAG, prefix + ": " + why, t);
        return prefix + ": " + why;
    }

    private File releaseDir(String tag) {
        return new File(new File(app.getCacheDir(), CACHE_DIR), tag);
    }

    /** Deletes every cached release except {@code keepTag} (null: all of them). */
    private void pruneCache(String keepTag) {
        File[] dirs = new File(app.getCacheDir(), CACHE_DIR).listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            if (keepTag == null || !d.getName().equals(keepTag)) deleteTree(d);
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteTree(c);
        }
        delete(f);
    }

    private static void delete(File f) {
        if (f.exists() && !f.delete()) Log.w(TAG, "cannot delete " + f);
    }
}
