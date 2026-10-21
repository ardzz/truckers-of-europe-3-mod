package com.toemod;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.toemod.MenuModel.Page;

import org.json.JSONException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Floating in-game mod menu. Entry point {@link #start(Activity)} is injected into
 * {@code UnityPlayerActivity.onCreate} right after {@code super.onCreate}.
 *
 * The overlay is a transparent, non-clickable FrameLayout added to the activity's content
 * view (no overlay permission). Only the icon/panel consume touches; everything else falls
 * through to the Unity player.
 *
 * The panel shows one screen at a time between a fixed header and a fixed bottom bar
 * (Home / World / Cloud / Updates / Debug); each Home category opens its own screen. Cloud,
 * Updates and Debug work independently of the Mods switch, and Updates and Debug also of the
 * native hooks, so a broken or disabled mod can still be backed up, inspected and updated.
 */
public final class Menu {
    static final String TAG = "toemod";

    private static final String PREFS = "toemod";
    private static final String K_MASTER = "master";
    private static final String K_POS_X = "pos_x";
    private static final String K_POS_Y = "pos_y";
    private static final long POLL_MS = 500;
    private static final long READY_TIMEOUT_MS = 60_000;

    private static final int WAITING = 0;
    private static final int READY = 1;
    private static final int FAILED = 2;

    // Opaque panel: rain, fog or night behind it never changes text contrast.
    private static final int PANEL_BG = 0xFF111A25;
    private static final int SURFACE = 0xFF1B2735;
    private static final int TEXT = 0xFFF4F6F8;
    private static final int TEXT_DIM = 0xFFB4C0CD;
    private static final int ACCENT = 0xFFFFD884;
    private static final int ERROR = 0xFFFF9B91;
    private static final int LINE = 0x40B4C0CD;
    private static final int PRESSED = 0x40FFD884;
    private static final float DIMMED = 0.7f; // feature controls while the Mods switch is off
    private static final int PANEL_W_DP = 360;
    private static final int PANEL_H_DP = 600;
    private static final int ICON_DP = 46;
    private static final int TOUCH_DP = 44;
    private static final float MAX_H_FRACTION = 0.8f;

    private static final Page[] TABS = {Page.HOME, Page.WORLD, Page.CLOUD, Page.UPDATES, Page.DEBUG};
    private static final String[] TAB_NAMES = {"Home", "World", "Cloud", "Updates", "Debug"};
    private static final int UPDATES_TAB = 3;
    private static final String[] DEBUG_NAMES = {"Runtime", "Weather", "Truck"};
    private static final int DEBUG_RUNTIME = 0;
    private static final int DEBUG_WEATHER = 1;
    private static final int DEBUG_TRUCK = 2;

    /** Display units of numeric controls (presentation only: ids, ranges and defaults come from native). */
    private static final Map<String, String> UNITS = new HashMap<>();

    static {
        UNITS.put("pay_mult", "\u00D7");
        UNITS.put("xp_mult", "\u00D7");
        UNITS.put("engine_mult", "\u00D7");
        UNITS.put("time_hour", "h");
        UNITS.put("traffic", "cars");
    }

    // Process-wide: the native library and hook state outlive a recreated activity.
    private static boolean sLibLoaded;
    private static String sLibError; // set once System.loadLibrary failed in this process
    private static int sState = WAITING;
    private static List<Feature> sFeatures;
    private static Menu sCurrent;
    // Also process-wide, so a recreated activity reopens the screen the user left.
    private static Page sPage = Page.HOME;
    private static String sCategory;
    private static int sDebugSection = DEBUG_RUNTIME;

    private final Activity activity;
    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final Application.ActivityLifecycleCallbacks lifecycle;
    private final Updater updater;
    private final Updater.Listener updaterListener = new Updater.Listener() {
        @Override
        public void onUpdaterChanged() {
            renderUpdates();
        }
    };
    private final CloudController cloud;
    private final CloudController.Listener cloudListener = new CloudController.Listener() {
        @Override
        public void onCloudChanged() {
            renderCloud();
        }

        @Override
        public void onBackupsListed(List<LocalBackups.Backup> backups) {
            showBackups(backups);
        }

        @Override
        public void onRestoreReady(String source, SnapshotFormat.Snapshot snapshot, long installedBuild) {
            askRestore(source, snapshot, installedBuild);
        }
    };

    private boolean master;
    private float posFx;
    private float posFy;
    private long pollStart;
    private String failMsg = "";
    private boolean resumed;

    /** The one reusable diagnostics buffer; NaN until the first native read. */
    private final double[] snapshot = new double[Diagnostics.SIZE];
    private long lastSampleAt; // uptime of the last native read, 0 = never
    private String snapshotError; // set once reading diagnostics failed in this menu
    private boolean polling;

    private Map<String, List<Feature>> groups;
    private final Map<String, ScrollView> categoryPages = new HashMap<>();
    private final Map<String, TextView> categorySummaries = new LinkedHashMap<>();
    private final List<View> featureBoxes = new ArrayList<>();
    private final TextView[] tabs = new TextView[TABS.length];
    private final TextView[] debugTabs = new TextView[DEBUG_NAMES.length];
    private final Table[] debugTables = new Table[DEBUG_NAMES.length];

    private FrameLayout root;
    private TextView icon;
    private PanelLayout panel;
    private TextView back;
    private TextView subtitle;
    private TextView masterBanner;
    private FrameLayout pages;
    private ScrollView homePage;
    private ScrollView worldPage;
    private ScrollView cloudPage;
    private ScrollView updatesPage;
    private ScrollView debugPage;

    private TextView homeState;
    private TextView homeBuild;
    private Switch masterSwitch;
    private TextView masterNote;
    private TextView worldSummary;
    private LinearLayout homeRows;

    private TextView weatherHeadline;
    private TextView weatherDetail;
    private TextView weatherHint;
    private LinearLayout worldControls;

    private TextView upInstalled;
    private TextView upStatus;
    private ProgressBar upProgress;
    private TextView upSaveHint;
    private TextView upCheck;
    private TextView upDownload;
    private TextView upInstall;
    private TextView upCancel;
    private TextView upRelease;
    private TextView upChanges;
    private AlertDialog changesDialog;
    private AlertDialog valueDialog;

    private TextView cloudWhere;
    private TextView cloudStatus;
    private TextView cloudLastAuto;
    private TextView cloudBackup;
    private TextView cloudRestore;
    private TextView cloudImport;
    private TextView cloudStorage;
    private AlertDialog cloudDialog;

    public static void start(Activity a) {
        try {
            if (!sLibLoaded && sLibError == null) {
                try {
                    System.loadLibrary("toemod");
                    sLibLoaded = true;
                } catch (UnsatisfiedLinkError e) {
                    Log.e(TAG, "loadLibrary(toemod) failed", e);
                    sLibError = "native library missing";
                }
            }
            if (sCurrent != null) {
                if (sCurrent.activity == a) return;
                sCurrent.destroy();
            }
            final Menu m = new Menu(a);
            sCurrent = m;
            // Posted: onCreate continues with setContentView(mUnityPlayer), which would drop views added now.
            m.handler.post(new Runnable() {
                @Override
                public void run() {
                    m.ensureAttached();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Menu.start failed", t);
        }
    }

    private Menu(Activity a) {
        activity = a;
        ctx = new ContextThemeWrapper(a, android.R.style.Theme_Material);
        prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        density = a.getResources().getDisplayMetrics().density;
        master = prefs.getBoolean(K_MASTER, true);
        posFx = prefs.getFloat(K_POS_X, 0f);
        posFy = prefs.getFloat(K_POS_Y, 0.3f);
        updater = Updater.get(a);
        cloud = CloudController.get(a);
        Arrays.fill(snapshot, Double.NaN);
        lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity act, Bundle b) {}
            @Override public void onActivityStarted(Activity act) {}
            @Override public void onActivityResumed(Activity act) {
                if (act != activity) return;
                resumed = true;
                updater.onResume();
                ensureAttached();
                updatePolling();
            }
            @Override public void onActivityPaused(Activity act) {
                if (act != activity) return;
                resumed = false;
                updatePolling();
            }
            @Override public void onActivityStopped(Activity act) {
                if (act == activity) cloud.onStop();
            }
            @Override public void onActivitySaveInstanceState(Activity act, Bundle b) {}
            @Override public void onActivityDestroyed(Activity act) {
                if (act == activity) destroy();
            }
        };
        a.getApplication().registerActivityLifecycleCallbacks(lifecycle);
    }

    // ------------------------------------------------------------------ lifecycle

    private void ensureAttached() {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (root == null) {
                attach();
            } else if (root.getParent() == null) {
                activity.addContentView(root, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            root.bringToFront();
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    private void attach() {
        root = new FrameLayout(ctx);
        // Above the UnityPlayer sibling for both drawing and touch dispatch, whatever the child order.
        root.setTranslationZ(dp(32));

        panel = buildPanel();
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(dp(PANEL_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        icon = buildIcon();
        root.addView(icon, new FrameLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)));

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) handler.post(new Runnable() {
                    @Override
                    public void run() {
                        onRootResized();
                    }
                });
            }
        });
        panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                place(panel);
            }
        });

        activity.addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (sState == READY) {
            showFeatures();
        } else if (!sLibLoaded) {
            fail(sLibError);
        } else {
            sState = WAITING;
            pollStart = SystemClock.uptimeMillis();
            handler.post(poll);
        }
        renderMaster();
        refreshHome();
        updater.setListener(updaterListener);
        renderUpdates();
        cloud.setListener(cloudListener);
        renderCloud();
        showPage(sPage, sCategory);
    }

    private void destroy() {
        handler.removeCallbacksAndMessages(null);
        polling = false;
        if (changesDialog != null) changesDialog.dismiss();
        changesDialog = null;
        if (valueDialog != null) valueDialog.dismiss();
        valueDialog = null;
        if (cloudDialog != null) cloudDialog.dismiss();
        cloudDialog = null;
        updater.clearListener(updaterListener);
        cloud.clearListener(cloudListener);
        activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        if (root != null && root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        if (sCurrent == this) sCurrent = null;
    }

    // ------------------------------------------------------------------ native state

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (sState != WAITING) return;
            try {
                if (Native.ready()) {
                    onReady();
                } else if (SystemClock.uptimeMillis() - pollStart >= READY_TIMEOUT_MS) {
                    fail("hooks not installed after 60 s");
                } else {
                    handler.postDelayed(this, POLL_MS);
                }
            } catch (Throwable t) { // never let the menu take the game down
                Log.e(TAG, "init failed", t);
                fail("init error: " + t);
            }
        }
    };

    private void onReady() throws JSONException {
        List<Feature> fs = Feature.parse(Native.features());
        for (Feature f : fs) {
            f.on = prefs.getBoolean(onKey(f), f.defOn);
            f.value = f.clamp(prefs.getFloat(valueKey(f), f.defValue));
        }
        sFeatures = fs;
        sState = READY;
        Log.i(TAG, "hooks ready, " + fs.size() + " features");
        applyAll();
        showFeatures();
        refreshHome();
    }

    private void fail(String msg) {
        sState = FAILED;
        failMsg = msg;
        Log.e(TAG, msg);
        Toast.makeText(activity, "toemod: " + msg + " - mods inactive", Toast.LENGTH_LONG).show();
        homeRows.removeAllViews();
        homeRows.addView(hint("Mod inactive: " + msg + ". Updates and Debug still work."));
        worldControls.removeAllViews();
        worldControls.addView(hint("Mod inactive: " + msg));
        if (sPage == Page.CATEGORY) showPage(Page.HOME, null);
        refreshHome();
    }

    private void send(Feature f) {
        if (sState != READY) return;
        try {
            Native.set(f.id, master && f.on, f.value);
        } catch (Throwable t) {
            Log.e(TAG, "Native.set(" + f.id + ") failed", t);
        }
    }

    private void applyAll() {
        if (sFeatures == null) return;
        for (Feature f : sFeatures) send(f);
    }

    private void save(Feature f) {
        prefs.edit().putBoolean(onKey(f), f.on).putFloat(valueKey(f), f.value).apply();
    }

    private static String onKey(Feature f) {
        return "on." + f.id;
    }

    private static String valueKey(Feature f) {
        return "val." + f.id;
    }

    private void setMaster(boolean on) {
        master = on;
        prefs.edit().putBoolean(K_MASTER, on).apply();
        applyAll();
        renderMaster();
        refreshHome();
    }

    private void renderMaster() {
        if (masterSwitch == null) return;
        if (masterSwitch.isChecked() != master) masterSwitch.setChecked(master);
        setText(masterSwitch, master ? "Mods ON" : "Mods OFF");
        setText(masterNote, master ? "Enabled controls apply in game." : "Controls stay saved but are not applied.");
        for (View b : featureBoxes) b.setAlpha(master ? 1f : DIMMED);
        show(masterBanner, !master && (sPage == Page.CATEGORY || sPage == Page.WORLD));
    }

    private static int countOn(List<Feature> fs) {
        int on = 0;
        for (Feature f : fs) if (f.on) on++;
        return on;
    }

    /** Hook status on Home, the icon ring and the per-category "n of m on" summaries. */
    private void refreshHome() {
        if (icon == null) return;
        int ring;
        switch (sState) {
            case READY:
                setText(homeState, "Ready \u00B7 " + sFeatures.size() + " controls, " + countOn(sFeatures) + " on");
                setColor(homeState, TEXT);
                ring = master ? ACCENT : TEXT_DIM;
                break;
            case FAILED:
                setText(homeState, "Mods inactive: " + failMsg);
                setColor(homeState, ERROR);
                ring = ERROR;
                break;
            default:
                setText(homeState, "Waiting for game hooks\u2026");
                setColor(homeState, TEXT_DIM);
                ring = TEXT_DIM;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(PANEL_BG);
        bg.setStroke(dp(3), ring);
        icon.setBackground(bg);

        String world = "Weather status, time of day, traffic";
        List<Feature> worldFeatures = groups == null ? null : groups.get(MenuModel.WORLD);
        setText(worldSummary, worldFeatures == null ? world : world + " \u00B7 " + summary(worldFeatures));
        for (Map.Entry<String, TextView> e : categorySummaries.entrySet()) {
            setText(e.getValue(), summary(groups.get(e.getKey())));
        }
    }

    private static String summary(List<Feature> fs) {
        return countOn(fs) + " of " + fs.size() + " on";
    }

    // ------------------------------------------------------------------ live diagnostics

    private final Runnable snapshotPoll = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            sample();
            renderLive();
            handler.postDelayed(this, Diagnostics.POLL_MS);
        }
    };

    /** Reads diagnostics only while World or Debug is on screen; renders the visible one right away. */
    private void updatePolling() {
        handler.removeCallbacks(snapshotPoll);
        polling = panel != null && MenuModel.needsSnapshot(sPage, panel.getVisibility() == View.VISIBLE, resumed);
        if (polling) handler.post(snapshotPoll);
    }

    private void sample() {
        if (snapshotError != null) return;
        long now = SystemClock.uptimeMillis();
        if (lastSampleAt != 0 && now - lastSampleAt < Diagnostics.POLL_MS) return; // <= 2 Hz across page switches
        if (!sLibLoaded) {
            snapshotError = sLibError != null ? sLibError : "native library missing";
            return;
        }
        try {
            Native.debugSnapshot(snapshot);
            lastSampleAt = now;
        } catch (Throwable t) { // never let the viewer take the game down
            Log.e(TAG, "debugSnapshot failed", t);
            Arrays.fill(snapshot, Double.NaN);
            snapshotError = t instanceof UnsatisfiedLinkError ? "native library has no diagnostics" : t.toString();
        }
    }

    private void renderLive() {
        if (sPage == Page.WORLD) renderWeatherStatus();
        else if (sPage == Page.DEBUG) renderDebug(sDebugSection);
    }

    private void renderWeatherStatus() {
        String head;
        String detail;
        String note;
        int color;
        if (snapshotError != null) {
            head = "Weather status unavailable";
            detail = snapshotError;
            note = "";
            color = ERROR;
        } else {
            Diagnostics.Weather w = Diagnostics.weather(snapshot);
            head = Diagnostics.headline(w, snapshot);
            detail = Diagnostics.detail(w, snapshot);
            note = Diagnostics.hint(w, snapshot);
            color = w == Diagnostics.Weather.ACTIVE ? ACCENT
                    : w == Diagnostics.Weather.WAITING || w == Diagnostics.Weather.STALE || w == Diagnostics.Weather.OFF
                    ? TEXT_DIM : TEXT;
        }
        setText(weatherHeadline, head);
        setColor(weatherHeadline, color);
        setText(weatherDetail, detail);
        setText(weatherHint, note);
        show(weatherHint, !note.isEmpty());
    }

    private void renderDebug(int section) {
        switch (section) {
            case DEBUG_WEATHER:
                renderWeatherTable(debugTables[DEBUG_WEATHER]);
                break;
            case DEBUG_TRUCK:
                renderTruckTable(debugTables[DEBUG_TRUCK]);
                break;
            default:
                renderRuntimeTable(debugTables[DEBUG_RUNTIME]);
        }
    }

    private String live(int index) {
        if (snapshotError != null) return "unavailable";
        return lastSampleAt == 0 ? "reading\u2026" : Diagnostics.count(snapshot[index]);
    }

    private void renderRuntimeTable(Table t) {
        t.begin();
        t.put("Native library", sLibLoaded ? "loaded" : "missing", sLibLoaded ? TEXT : ERROR);
        t.put("Game hooks", sState == READY ? "ready" : sState == FAILED ? "failed: " + failMsg : "waiting",
                sState == FAILED ? ERROR : TEXT);
        t.put("Hooks installed", live(Diagnostics.HOOKS_INSTALLED), TEXT);
        t.put("Hooks failed", live(Diagnostics.HOOKS_FAILED), problem(Diagnostics.HOOKS_FAILED) ? ERROR : TEXT);
        t.put("Patch steps failed", live(Diagnostics.PATCHES_FAILED), problem(Diagnostics.PATCHES_FAILED) ? ERROR : TEXT);
        t.put("Controls", sState == READY ? sFeatures.size() + " registered, " + countOn(sFeatures) + " on" : "not loaded", TEXT);
        t.put("Mods switch", master ? "ON" : "OFF (controls not applied)", master ? TEXT : ACCENT);
        UpdatePolicy.Identity id = updater.installed();
        t.put("Mod version", id == null ? "unknown" : id.label(), TEXT);
        t.put("Mod build", id == null ? "unknown" : id.modBuild == 0 ? "local build" : Long.toString(id.modBuild), TEXT);
        t.put("Release tag", id == null ? "unknown" : id.tag, TEXT);
        t.put("Source revision", id == null ? "unknown" : id.revision.substring(0, Math.min(12, id.revision.length())), TEXT);
        t.put("Game version", id == null ? "unknown" : id.versionName + " (" + id.versionCode + ")", TEXT);
        t.put("APK ABI", id == null ? "unknown" : id.abi, TEXT);
        t.put("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", TEXT);
        if (snapshotError != null) t.put("Live data", "unavailable: " + snapshotError, ERROR);
        t.end();
    }

    private boolean problem(int index) {
        double v = snapshot[index];
        return snapshotError == null && Diagnostics.known(v) && v > 0;
    }

    private void renderWeatherTable(Table t) {
        double[] s = snapshot;
        Diagnostics.Freshness fresh = Diagnostics.freshness(s[Diagnostics.WEATHER_AGE_MS]);
        int v = fresh == Diagnostics.Freshness.FRESH ? TEXT : TEXT_DIM;
        t.begin();
        t.put("Status", snapshotError != null ? "unavailable: " + snapshotError
                : Diagnostics.headline(Diagnostics.weather(s), s), snapshotError != null ? ERROR : ACCENT);
        t.put("Sky sample", Diagnostics.age(s[Diagnostics.WEATHER_AGE_MS]), fresh == Diagnostics.Freshness.STALE ? ACCENT : v);
        t.put("Override", onOff(s[Diagnostics.WEATHER_ENABLED]), v);
        t.put("Requested", Diagnostics.weatherName(s[Diagnostics.WEATHER_REQUESTED]), v);
        t.put("Current", Diagnostics.weatherName(s[Diagnostics.WEATHER_CURRENT]), v);
        t.put("Next", Diagnostics.weatherName(s[Diagnostics.WEATHER_NEXT]), v);
        t.put("Next change in", Diagnostics.number(s[Diagnostics.WEATHER_TIMER_S], 0, "s"), v);
        t.put("Transition running", Diagnostics.flag(s[Diagnostics.WEATHER_CHANGING]), v);
        t.put("Rain intensity", Diagnostics.number(s[Diagnostics.RAIN], 2, ""), v);
        t.put("Cloud intensity", Diagnostics.number(s[Diagnostics.CLOUD], 2, ""), v);
        t.put("Fog intensity", Diagnostics.number(s[Diagnostics.MIST], 2, ""), v);
        t.put("Target intensity", Diagnostics.number(s[Diagnostics.WEATHER_TARGET], 2, ""), v);
        t.put("No-rain area", Diagnostics.flag(s[Diagnostics.NO_RAIN_AREA]), v);
        t.put("Rain stop zone", Diagnostics.flag(s[Diagnostics.STOP_RAIN_AREA]), v);
        t.put("Game clock", Diagnostics.clock(s[Diagnostics.HOUR]), v);
        t.put("Requests sent / applied", Diagnostics.count(s[Diagnostics.REQUEST_GENERATION]) + " / "
                + Diagnostics.count(s[Diagnostics.APPLIED_GENERATION]), v);
        t.put("Weather changes applied", Diagnostics.count(s[Diagnostics.TRANSITIONS]), v);
        t.end();
    }

    private void renderTruckTable(Table t) {
        double[] s = snapshot;
        Diagnostics.Freshness fresh = Diagnostics.freshness(s[Diagnostics.TRUCK_AGE_MS]);
        int v = fresh == Diagnostics.Freshness.FRESH ? TEXT : TEXT_DIM;
        String status;
        if (snapshotError != null) status = "unavailable: " + snapshotError;
        else if (fresh == Diagnostics.Freshness.MISSING) status = "Waiting for a truck: start driving";
        else if (fresh == Diagnostics.Freshness.STALE) status = "Not live: paused, in a menu or loading";
        else status = "Live";
        t.begin();
        t.put("Status", status, snapshotError != null ? ERROR : fresh == Diagnostics.Freshness.FRESH ? ACCENT : TEXT_DIM);
        t.put("Truck sample", Diagnostics.age(s[Diagnostics.TRUCK_AGE_MS]), fresh == Diagnostics.Freshness.STALE ? ACCENT : v);
        t.put("Speed", Diagnostics.number(s[Diagnostics.SPEED_KPH], 0, "km/h"), v);
        t.put("Fuel", Diagnostics.number(s[Diagnostics.FUEL_L], 1, "L"), v);
        t.put("Fuel level", Diagnostics.percent(s[Diagnostics.FUEL_FRACTION]), v);
        t.put("Truck health", Diagnostics.percent(s[Diagnostics.HEALTH_FRACTION]), v);
        t.put("Dirt", Diagnostics.number(s[Diagnostics.DIRT], 2, ""), v);
        t.put("Dust", Diagnostics.number(s[Diagnostics.DUST], 2, ""), v);
        t.put("Rain on truck", Diagnostics.flag(s[Diagnostics.TRUCK_RAINING]), v);
        t.end();
    }

    private static String onOff(double v) {
        return Diagnostics.known(v) ? (v != 0 ? "on" : "off") : "unknown";
    }

    /** Plain text of all three sections; holds build and game state only, no account data. */
    private void copyDiagnostics() {
        StringBuilder b = new StringBuilder("TOE3 Stormline diagnostics\n");
        for (int i = 0; i < debugTables.length; i++) {
            renderDebug(i);
            b.append('\n').append(DEBUG_NAMES[i]).append('\n');
            debugTables[i].appendTo(b);
        }
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Stormline diagnostics", b.toString()));
        Toast.makeText(activity, "Diagnostics copied", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ geometry

    private void onRootResized() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) return;
        panel.fixedHeight = Math.min((int) (h * MAX_H_FRACTION), dp(PANEL_H_DP));
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.width = Math.min(dp(PANEL_W_DP), w - dp(16));
        panel.setLayoutParams(lp);
        place(icon);
        place(panel);
    }

    /** Position is stored as a fraction of the free travel so icon and panel share it and stay on screen. */
    private void place(View v) {
        int rangeX = root.getWidth() - v.getWidth();
        int rangeY = root.getHeight() - v.getHeight();
        v.setX(Math.max(0, rangeX) * posFx);
        v.setY(Math.max(0, rangeY) * posFy);
    }

    private void moveTo(View v, float x, float y) {
        float rangeX = Math.max(0, root.getWidth() - v.getWidth());
        float rangeY = Math.max(0, root.getHeight() - v.getHeight());
        x = Math.max(0, Math.min(rangeX, x));
        y = Math.max(0, Math.min(rangeY, y));
        if (rangeX > 0) posFx = x / rangeX;
        if (rangeY > 0) posFy = y / rangeY;
        v.setX(x);
        v.setY(y);
    }

    private void savePos() {
        prefs.edit().putFloat(K_POS_X, posFx).putFloat(K_POS_Y, posFy).apply();
    }

    private void expand() {
        root.bringToFront();
        icon.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        place(panel);
        if (sPage == Page.HOME) refreshHome();
        updatePolling();
    }

    private void collapse() {
        panel.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        place(icon);
        updatePolling();
    }

    /** Drag to move {@code target}; a touch that never exceeds touch slop is a click. */
    private final class Drag implements View.OnTouchListener {
        private final View target;
        private final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        private float downX, downY, startX, startY;
        private boolean dragging;

        Drag(View target) {
            this.target = target;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = target.getX();
                    startY = target.getY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true;
                    if (dragging) moveTo(target, startX + dx, startY + dy);
                    break;
                case MotionEvent.ACTION_UP:
                    if (dragging) savePos();
                    else v.performClick();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) savePos();
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ screens

    private void showPage(Page page, String category) {
        if (pages == null) return;
        if (page == Page.CATEGORY && (sState != READY || groups == null || category == null
                || MenuModel.WORLD.equals(category) || !groups.containsKey(category))) {
            page = Page.HOME;
        }
        sPage = page;
        sCategory = page == Page.CATEGORY ? category : null;
        View target;
        switch (page) {
            case CATEGORY:
                target = categoryPage(category);
                break;
            case WORLD:
                target = worldPage;
                break;
            case CLOUD:
                target = cloudPage;
                break;
            case UPDATES:
                target = updatesPage;
                break;
            case DEBUG:
                target = debugPage;
                break;
            default:
                target = homePage;
        }
        for (int i = 0; i < pages.getChildCount(); i++) {
            View c = pages.getChildAt(i);
            show(c, c == target);
        }
        Page tab = MenuModel.tab(page);
        for (int i = 0; i < TABS.length; i++) {
            boolean selected = TABS[i] == tab;
            tabs[i].setSelected(selected);
            tabs[i].setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (TABS[i] == page) setText(subtitle, TAB_NAMES[i]);
        }
        if (page == Page.CATEGORY) setText(subtitle, category);
        show(back, page == Page.CATEGORY);
        renderMaster();
        if (page == Page.HOME) refreshHome();
        if (page == Page.DEBUG) showDebugSection(sDebugSection);
        updatePolling();
    }

    private void showDebugSection(int section) {
        sDebugSection = section;
        for (int i = 0; i < debugTables.length; i++) {
            show(debugTables[i].box, i == section);
            debugTabs[i].setSelected(i == section);
            debugTabs[i].setTypeface(i == section ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        renderDebug(section);
    }

    private void showFeatures() {
        groups = new LinkedHashMap<>();
        for (Feature f : sFeatures) {
            List<Feature> g = groups.get(f.category);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(f.category, g);
            }
            g.add(f);
        }
        for (ScrollView p : categoryPages.values()) pages.removeView(p);
        categoryPages.clear();
        categorySummaries.clear();
        featureBoxes.clear();
        featureBoxes.add(worldControls);

        homeRows.removeAllViews();
        for (final String category : groups.keySet()) {
            if (category.equals(MenuModel.WORLD)) continue;
            TextView summary = label(13, TEXT_DIM);
            categorySummaries.put(category, summary);
            homeRows.addView(navRow(category, summary, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(Page.CATEGORY, category);
                }
            }), rowParams());
        }
        if (groups.isEmpty()) homeRows.addView(hint("No features registered."));

        worldControls.removeAllViews();
        List<Feature> world = groups.get(MenuModel.WORLD);
        if (world == null) worldControls.addView(hint("No world controls registered."));
        else addFeatures(worldControls, world);

        renderMaster();
        refreshHome();
        if (sPage == Page.CATEGORY) showPage(sPage, sCategory); // a restored category screen needs its controls
    }

    private ScrollView categoryPage(String category) {
        ScrollView page = categoryPages.get(category);
        if (page != null) return page;
        LinearLayout content = pageContent();
        LinearLayout box = vertical();
        addFeatures(box, groups.get(category));
        box.setAlpha(master ? 1f : DIMMED);
        featureBoxes.add(box);
        content.addView(box, matchWrap());
        page = scroll(content);
        pages.addView(page, matchMatch());
        categoryPages.put(category, page);
        return page;
    }

    private void addFeatures(LinearLayout box, List<Feature> features) {
        for (int i = 0; i < features.size(); i++) {
            if (i > 0) box.addView(divider());
            box.addView(buildFeature(features.get(i)), matchWrap());
        }
    }

    // ------------------------------------------------------------------ views

    private TextView buildIcon() {
        TextView t = new TextView(ctx);
        t.setText("TOE");
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription("Open Stormline menu");
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expand();
            }
        });
        t.setOnTouchListener(new Drag(t));
        return t;
    }

    private PanelLayout buildPanel() {
        PanelLayout p = new PanelLayout(ctx);
        p.fixedHeight = Math.min((int) (ctx.getResources().getDisplayMetrics().heightPixels * MAX_H_FRACTION), dp(PANEL_H_DP));
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(8), dp(4), dp(8), dp(4));
        p.setBackground(rounded(PANEL_BG, dp(14), 0x66FFD884));
        p.setClickable(true); // touches on the panel's own area must not reach the game

        p.addView(buildHeader(p), matchWrap());

        masterBanner = action("Mods are OFF \u00B7 tap to turn on", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                masterSwitch.setChecked(true);
            }
        });
        masterBanner.setTextColor(ACCENT);
        LinearLayout.LayoutParams bannerLp = matchWrap();
        bannerLp.bottomMargin = dp(4);
        p.addView(masterBanner, bannerLp);

        pages = new FrameLayout(ctx);
        homePage = buildHome();
        worldPage = buildWorld();
        cloudPage = buildCloud();
        updatesPage = buildUpdates();
        debugPage = buildDebug();
        for (View v : new View[] {homePage, worldPage, cloudPage, updatesPage, debugPage}) pages.addView(v, matchMatch());
        // Takes exactly the height between header and tab bar, so the tab bar never moves.
        p.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        p.addView(divider());
        p.addView(buildTabs(), matchWrap());
        return p;
    }

    private View buildHeader(View dragTarget) {
        LinearLayout header = horizontal();
        back = iconButton("\u2190", "Back to Home");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(Page.HOME, null);
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)));

        LinearLayout titleBox = vertical();
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.setPadding(dp(8), 0, dp(8), 0);
        TextView title = label(16, ACCENT);
        title.setText("Stormline");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        titleBox.addView(title);
        subtitle = label(12, TEXT_DIM);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titleBox.addView(subtitle);
        titleBox.setOnTouchListener(new Drag(dragTarget)); // only the title is the drag handle, never a button
        header.addView(titleBox, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView close = iconButton("\u00D7", "Collapse menu");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapse();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)));
        return header;
    }

    private View buildTabs() {
        LinearLayout bar = horizontal();
        bar.setPadding(0, dp(4), 0, 0);
        for (int i = 0; i < TABS.length; i++) {
            final Page page = TABS[i];
            TextView t = choiceButton(TAB_NAMES[i]);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(page, null);
                }
            });
            tabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.leftMargin = lp.rightMargin = dp(2);
            bar.addView(t, lp);
        }
        return bar;
    }

    // ------------------------------------------------------------------ home

    private ScrollView buildHome() {
        LinearLayout content = pageContent();
        LinearLayout card = card();
        homeState = label(15, TEXT);
        homeState.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(homeState, matchWrap());
        homeBuild = label(13, TEXT_DIM);
        homeBuild.setPadding(0, dp(2), 0, 0);
        card.addView(homeBuild, matchWrap());
        card.addView(divider());
        masterSwitch = makeSwitch("Mods ON");
        masterSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        masterSwitch.setChecked(master);
        masterSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                setMaster(checked);
            }
        });
        card.addView(masterSwitch, matchWrap());
        masterNote = label(12, TEXT_DIM);
        card.addView(masterNote, matchWrap());
        content.addView(card, cardParams());

        worldSummary = label(13, TEXT_DIM);
        content.addView(navRow("World", worldSummary, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(Page.WORLD, null);
            }
        }), rowParams());
        homeRows = vertical();
        homeRows.addView(hint("Categories appear once the game hooks are installed."));
        content.addView(homeRows, matchWrap());
        return scroll(content);
    }

    private View navRow(String title, TextView summary, boolean primary, View.OnClickListener click) {
        LinearLayout row = horizontal();
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(14), dp(8), dp(10), dp(8));
        row.setBackground(pressable(SURFACE, dp(10), primary ? 0x80FFD884 : LINE));
        row.setClickable(true);
        row.setOnClickListener(click);

        LinearLayout text = vertical();
        TextView name = label(16, primary ? ACCENT : TEXT);
        name.setText(title);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name, matchWrap());
        text.addView(summary, matchWrap());
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = label(22, TEXT_DIM);
        chevron.setText("\u203A");
        chevron.setPadding(dp(8), 0, 0, 0);
        row.addView(chevron);
        return row;
    }

    // ------------------------------------------------------------------ world

    private ScrollView buildWorld() {
        LinearLayout content = pageContent();
        LinearLayout card = card();
        TextView caption = label(12, TEXT_DIM);
        caption.setText("Weather in game");
        card.addView(caption, matchWrap());
        weatherHeadline = label(17, TEXT);
        weatherHeadline.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(weatherHeadline, matchWrap());
        weatherDetail = label(13, TEXT);
        weatherDetail.setPadding(0, dp(2), 0, 0);
        card.addView(weatherDetail, matchWrap());
        weatherHint = label(12, TEXT_DIM);
        weatherHint.setPadding(0, dp(4), 0, 0);
        card.addView(weatherHint, matchWrap());
        content.addView(card, cardParams());
        renderWeatherStatus();

        worldControls = vertical();
        worldControls.addView(hint("Controls appear once the game hooks are installed."));
        content.addView(worldControls, matchWrap());
        return scroll(content);
    }

    // ------------------------------------------------------------------ feature controls

    private View buildFeature(final Feature f) {
        LinearLayout row = vertical();
        row.setPadding(0, dp(4), 0, dp(8));

        Switch sw = makeSwitch(f.label);
        sw.setChecked(f.on);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                f.on = checked;
                save(f);
                send(f);
            }
        });
        row.addView(sw, matchWrap());

        if (f.hasSlider()) {
            TextView range = label(12, TEXT_DIM);
            range.setText("Range " + withUnit(readable(f, f.min) + "\u2013" + readable(f, f.max), f.id)
                    + " \u00B7 tap the number to type it");
            row.addView(range, matchWrap());
            row.addView(new NumberControl(f).line, matchWrap());
        } else if (f.hasChoices()) {
            row.addView(buildChoices(f), matchWrap());
        }
        return row;
    }

    /** Slider with step buttons and an exact-value field, all within the native range. */
    private final class NumberControl implements SeekBar.OnSeekBarChangeListener {
        final Feature f;
        final LinearLayout line;
        private final SeekBar bar;
        private final TextView value;
        private boolean tracking;

        NumberControl(Feature feature) {
            f = feature;
            bar = new SeekBar(ctx);
            bar.setMax(f.steps());
            bar.setProgress(f.toStep(f.value)); // before the listener: no spurious set()
            ColorStateList accent = ColorStateList.valueOf(ACCENT);
            bar.setProgressTintList(accent);
            bar.setThumbTintList(accent);
            bar.setOnSeekBarChangeListener(this);

            value = button(display(f));
            value.setTextColor(ACCENT);
            value.setMinWidth(dp(72));
            value.setPadding(dp(10), 0, dp(10), 0);
            value.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editValue(NumberControl.this);
                }
            });
            TextView minus = button("\u2212");
            minus.setContentDescription("Decrease " + f.label);
            minus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    step(-1);
                }
            });
            TextView plus = button("+");
            plus.setContentDescription("Increase " + f.label);
            plus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    step(1);
                }
            });

            line = horizontal();
            line.addView(minus, new LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)));
            line.addView(bar, new LinearLayout.LayoutParams(0, dp(TOUCH_DP), 1f));
            line.addView(plus, new LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(TOUCH_DP));
            lp.leftMargin = dp(6);
            line.addView(value, lp);
        }

        void set(float v) {
            f.value = f.clamp(v);
            bar.setProgress(f.toStep(f.value)); // not fromUser: keeps a typed value that lies between steps
            setText(value, display(f));
            save(f);
            send(f);
        }

        private void step(int delta) {
            set(f.fromStep(Math.max(0, Math.min(f.steps(), f.toStep(f.value) + delta))));
        }

        @Override
        public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
            if (!fromUser) return;
            f.value = f.fromStep(p);
            setText(value, display(f));
            send(f);
            if (!tracking) save(f); // keyboard or accessibility adjustment: no stop-tracking event follows
        }

        @Override
        public void onStartTrackingTouch(SeekBar s) {
            tracking = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
            tracking = false;
            save(f);
        }
    }

    /** Bounded numeric dialog: refuses (never clamps) text outside the control's native range. */
    private void editValue(final NumberControl control) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (valueDialog != null) valueDialog.dismiss();
        final Feature f = control.f;
        final boolean integer = f.type == Feature.TOGGLE_INT;
        final String rule = "Enter " + (integer ? "a whole number" : "a number") + " from "
                + withUnit(plain(f, f.min) + " to " + plain(f, f.max), f.id) + ".";
        AlertDialog.Builder b = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
        Context dc = b.getContext();

        final EditText input = new EditText(dc);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | (integer ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL)
                | (f.min < 0 ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setText(plain(f, f.value));
        input.setSelectAllOnFocus(true);
        TextView help = new TextView(dc);
        help.setText(rule);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout box = new LinearLayout(dc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(help, matchWrap());
        box.addView(input, matchWrap());

        final AlertDialog d = b.setTitle(f.label)
                .setView(box)
                .setPositiveButton("Set", null)
                .setNegativeButton("Cancel", null)
                .create();
        final View.OnClickListener apply = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float value = MenuModel.parseValue(input.getText().toString(), f.min, f.max, integer);
                if (Float.isNaN(value)) {
                    input.setError(rule);
                    return;
                }
                control.set(value);
                d.dismiss();
            }
        };
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(apply);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                apply.onClick(v);
                return true;
            }
        });
        Window w = d.getWindow();
        if (w != null) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        valueDialog = d;
        d.show();
        input.requestFocus();
    }

    /** Choices as a two-column grid so long labels wrap instead of scrolling sideways. */
    private View buildChoices(final Feature f) {
        LinearLayout grid = vertical();
        final RadioButton[] buttons = new RadioButton[f.choices.length];
        ColorStateList tint = new ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}}, new int[] {ACCENT, TEXT_DIM});
        LinearLayout line = null;
        for (int i = 0; i < f.choices.length; i++) {
            if (i % 2 == 0) {
                line = horizontal();
                grid.addView(line, matchWrap());
            }
            final int index = i;
            RadioButton rb = new RadioButton(ctx);
            rb.setText(f.choices[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            rb.setButtonTintList(tint);
            rb.setMinHeight(dp(TOUCH_DP));
            rb.setChecked(i == (int) f.value);
            rb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < buttons.length; j++) buttons[j].setChecked(j == index);
                    if ((int) f.value == index) return;
                    f.value = index;
                    save(f);
                    send(f);
                }
            });
            buttons[i] = rb;
            line.addView(rb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (f.choices.length % 2 == 1) line.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        return grid;
    }

    /** Plain number as typed into the exact-value dialog. */
    private static String plain(Feature f, float v) {
        if (f.type == Feature.TOGGLE_INT) return Long.toString(Math.round((double) v));
        return new BigDecimal(Float.toString(v)).stripTrailingZeros().toPlainString();
    }

    /** Whole numbers with thousands separators (money reads 50,000,000), others as plain as the value allows. */
    private static String readable(Feature f, float v) {
        if (f.type == Feature.TOGGLE_INT) return String.format(Locale.US, "%,d", Math.round((double) v));
        return plain(f, v);
    }

    /** Slider readout: the slider's precision, or the exact value when one was typed between two steps. */
    private static String display(Feature f) {
        if (f.type == Feature.TOGGLE_INT) return withUnit(readable(f, f.value), f.id);
        String s = f.format(f.value);
        if (Float.parseFloat(s) != f.value) s = plain(f, f.value);
        return withUnit(s, f.id);
    }

    private static String withUnit(String s, String id) {
        String unit = UNITS.get(id);
        if (unit == null) return s;
        return unit.equals("\u00D7") ? s + unit : s + " " + unit;
    }

    // ------------------------------------------------------------------ updates

    private ScrollView buildUpdates() {
        LinearLayout content = pageContent();
        LinearLayout card = card();
        upInstalled = label(13, TEXT_DIM);
        card.addView(upInstalled, matchWrap());
        upStatus = label(14, TEXT);
        upStatus.setPadding(0, dp(6), 0, 0);
        card.addView(upStatus, matchWrap());
        upProgress = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        upProgress.setMax(1000);
        upProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        card.addView(upProgress, matchWrap());
        upSaveHint = label(13, ACCENT);
        upSaveHint.setText("Save your game before installing: Android closes the game to update it.");
        upSaveHint.setPadding(0, dp(4), 0, 0);
        card.addView(upSaveHint, matchWrap());
        content.addView(card, cardParams());

        upDownload = action("Download", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.download();
            }
        });
        upInstall = action("Install", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.install(activity);
            }
        });
        upCancel = action("Cancel", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.cancel();
            }
        });
        upCheck = action("Check for updates", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.check();
            }
        });
        upRelease = action("View release", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openReleasePage();
            }
        });
        upChanges = action("Changelog", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChangelog();
            }
        });
        // One full-width action per row: every action is reachable without sideways scrolling.
        for (TextView b : new TextView[] {upDownload, upInstall, upCancel, upCheck, upRelease, upChanges}) {
            content.addView(b, rowParams());
        }
        TextView note = label(12, TEXT_DIM);
        note.setText("Updates work even with Mods off or when the game hooks failed.");
        content.addView(note, matchWrap());
        return scroll(content);
    }

    private void renderUpdates() {
        if (upStatus == null) return;
        Updater.Phase p = updater.phase();
        UpdatePolicy.Identity inst = updater.installed();
        upInstalled.setText(inst == null ? "Installed: unknown" : "Installed: " + versionLines(inst));
        setText(homeBuild, inst == null ? "Installed build unknown" : "Installed " + versionLines(inst));

        boolean progress = updater.showsProgress();
        String msg = updater.message();
        if (progress) msg += "\n" + Updater.mb(updater.progressDone()) + " of " + Updater.mb(updater.progressTotal());
        upStatus.setText(msg);
        upStatus.setTextColor(updater.isError() ? ERROR : TEXT);
        show(upStatus, !msg.isEmpty());
        show(upProgress, progress);
        upProgress.setProgress(updater.progressPermille());
        show(upSaveHint, p == Updater.Phase.READY || p == Updater.Phase.INSTALLING);

        upInstall.setText(updater.installPermitted() ? "Install" : "Allow installs");
        show(upDownload, p == Updater.Phase.AVAILABLE);
        show(upInstall, p == Updater.Phase.READY);
        show(upCancel, p == Updater.Phase.DOWNLOADING || p == Updater.Phase.INSTALLING);
        show(upCheck, p == Updater.Phase.IDLE || p == Updater.Phase.AVAILABLE);
        show(upRelease, updater.release() != null);
        show(upChanges, updater.changelog() != null);
        boolean offer = p == Updater.Phase.AVAILABLE || p == Updater.Phase.READY;
        setText(tabs[UPDATES_TAB], offer ? TAB_NAMES[UPDATES_TAB] + " \u2022" : TAB_NAMES[UPDATES_TAB]);
    }

    /** Label and game version, then the numeric build of a "vX.Y.Z" release or the tag of any other build. */
    private static String versionLines(UpdatePolicy.Identity id) {
        return id.label() + " \u00B7 game " + id.versionName + "\n" + (id.modVersion() != null ? "build " + id.modBuild : id.tag);
    }

    private void openReleasePage() {
        String url = updater.releasePage();
        if (url == null) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "toemod: no browser available for " + url, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The checked release's change list from its GitHub release notes, shown in-game as plain
     * selectable text. The notes are unsigned and informational: nothing in them is a link.
     */
    private void showChangelog() {
        Updater.Changelog c = updater.changelog();
        if (c == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (changesDialog != null) changesDialog.dismiss();
        AlertDialog.Builder b = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
        Context dc = b.getContext();

        TextView meta = new TextView(dc);
        meta.setText("Release " + versionLines(c.release)
                + "\nFrom the GitHub release notes (informational, not part of the signed update).");
        meta.setTextColor(0xFFBDBDBD);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextIsSelectable(true);

        TextView body = new TextView(dc);
        body.setText(c.changes != null ? c.changes : "No changelog supplied for this release.");
        body.setTextColor(TEXT);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setPadding(0, dp(10), 0, 0);
        body.setTextIsSelectable(true);

        LinearLayout content = new LinearLayout(dc);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        content.addView(meta, matchWrap());
        content.addView(body, matchWrap());
        ScrollView scroll = new ScrollView(dc);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        changesDialog = b.setTitle("TOE3 Stormline changelog")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------------ cloud

    /** The game's Load button: opens the panel on the Cloud tab's restore list. False when no menu is showing. */
    static boolean openCloud() {
        Menu m = sCurrent;
        if (m == null || m.panel == null || m.activity.isFinishing() || m.activity.isDestroyed()) return false;
        m.ensureAttached();
        m.expand();
        m.showPage(Page.CLOUD, null);
        m.cloud.listBackups();
        return true;
    }

    private ScrollView buildCloud() {
        LinearLayout content = pageContent();
        LinearLayout card = card();
        TextView title = label(15, TEXT);
        title.setText("Backups on this device");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, matchWrap());
        cloudWhere = label(13, TEXT_DIM);
        cloudWhere.setPadding(0, dp(4), 0, 0);
        card.addView(cloudWhere, matchWrap());
        cloudLastAuto = label(13, TEXT_DIM);
        card.addView(cloudLastAuto, matchWrap());
        cloudStatus = label(14, TEXT);
        cloudStatus.setPadding(0, dp(6), 0, 0);
        card.addView(cloudStatus, matchWrap());
        content.addView(card, cardParams());

        cloudBackup = action("Back up now", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cloud.backupNow();
            }
        });
        cloudRestore = action("Restore a backup", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cloud.listBackups();
            }
        });
        cloudImport = action("Restore from a file", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cloud.importFile(activity);
            }
        });
        cloudStorage = action("Allow storage access", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cloud.requestStorage(activity);
            }
        });
        for (TextView b : new TextView[] {cloudBackup, cloudRestore, cloudImport, cloudStorage}) content.addView(b, rowParams());
        TextView note = label(12, TEXT_DIM);
        note.setText("The game's Save button backs up too, and its Load button opens the restore list. Leaving the game"
                + " backs up automatically at most every 10 minutes and keeps the 5 newest automatic backups; manual and"
                + " pre-restore backups are never deleted. Backups work even with Mods off.");
        content.addView(note, matchWrap());
        return scroll(content);
    }

    private void renderCloud() {
        if (cloudStatus == null) return;
        boolean shared = cloud.sharedFolder();
        setText(cloudWhere, shared ? "Saved to " + LocalBackups.FOLDER + "."
                : "Saved in the game's own folder, which Android deletes with the game. Allow storage access to use "
                        + LocalBackups.FOLDER + " instead.");
        setColor(cloudWhere, shared ? TEXT_DIM : ACCENT);
        long last = cloud.lastAutoBackup();
        setText(cloudLastAuto, last > 0 ? "Last automatic backup: "
                + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(last) : "");
        show(cloudLastAuto, last > 0);
        String msg = cloud.message();
        setText(cloudStatus, msg);
        setColor(cloudStatus, cloud.isError() ? ERROR : TEXT);
        show(cloudStatus, !msg.isEmpty());
        boolean busy = cloud.busy();
        for (TextView b : new TextView[] {cloudBackup, cloudRestore, cloudImport}) {
            b.setEnabled(!busy);
            b.setAlpha(busy ? DIMMED : 1f);
        }
        show(cloudStorage, !shared);
    }

    private void showBackups(final List<LocalBackups.Backup> backups) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (cloudDialog != null) cloudDialog.dismiss();
        AlertDialog.Builder b = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Restore a backup")
                .setNegativeButton("Close", null)
                .setNeutralButton("From a file", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cloud.importFile(activity);
                    }
                });
        if (backups.isEmpty()) {
            b.setMessage("No backups on this device yet. Choose \"From a file\" for a backup from another device or"
                    + " an earlier install.");
        } else {
            String[] items = new String[backups.size()];
            for (int i = 0; i < items.length; i++) {
                items[i] = backups.get(i).name + " (" + Math.max(1, (backups.get(i).size + 512) / 1024) + " KB)";
            }
            b.setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    LocalBackups.Backup chosen = backups.get(which);
                    cloud.prepareRestore(chosen.uri, chosen.name);
                }
            });
        }
        cloudDialog = b.show();
    }

    private void askRestore(String source, SnapshotFormat.Snapshot s, long installedBuild) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            cloud.cancelRestore();
            return;
        }
        if (cloudDialog != null) cloudDialog.dismiss();
        SnapshotFormat.Meta m = s.meta;
        StringBuilder text = new StringBuilder(source).append("\n\n");
        text.append("Made ").append(m.createdAt.isEmpty() ? "at an unknown time" : m.createdAt.replace('T', ' ').replace("Z", " UTC"));
        if (!m.reason.isEmpty()) text.append(" (").append(m.reason).append(')');
        if (!m.device.isEmpty()) text.append(" on ").append(m.device);
        text.append("\nGame ").append(m.gameVersion.isEmpty() ? "?" : m.gameVersion).append(" (build ").append(m.gameBuild).append(')');
        if (!m.modVersion.isEmpty()) text.append(", mod ").append(m.modVersion);
        text.append("\n").append(s.prefs.size()).append(" saved values");
        if (s.olderThan(installedBuild)) {
            text.append("\n\nThis backup comes from an older game build than the installed build ").append(installedBuild)
                    .append(". The game may not read all of it.");
        }
        text.append("\n\nThe current save is backed up first, then the game restarts with the restored save.");
        cloudDialog = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Restore this backup?")
                .setMessage(text)
                .setPositiveButton("Restore and restart", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cloud.confirmRestore();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cloud.cancelRestore();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        cloud.cancelRestore();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ debug

    private ScrollView buildDebug() {
        LinearLayout content = pageContent();
        LinearLayout segments = horizontal();
        for (int i = 0; i < DEBUG_NAMES.length; i++) {
            final int section = i;
            TextView t = choiceButton(DEBUG_NAMES[i]);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDebugSection(section);
                }
            });
            debugTabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(TOUCH_DP), 1f);
            lp.leftMargin = lp.rightMargin = dp(2);
            segments.addView(t, lp);
        }
        content.addView(segments, matchWrap());
        for (int i = 0; i < debugTables.length; i++) {
            debugTables[i] = new Table();
            content.addView(debugTables[i].box, matchWrap());
        }
        TextView note = label(12, TEXT_DIM);
        note.setText("Read-only: copied from the game twice a second while this screen is open. Nothing here changes the game.");
        note.setPadding(0, dp(4), 0, dp(8));
        content.addView(note, matchWrap());
        content.addView(action("Copy diagnostics", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyDiagnostics();
            }
        }), rowParams());
        return scroll(content);
    }

    /** Label/value rows created on the first render and only re-texted afterwards (no per-refresh view churn). */
    private final class Table {
        final LinearLayout box = vertical();
        private final List<TextView[]> rows = new ArrayList<>();
        private int next;

        Table() {
            box.setPadding(0, dp(8), 0, dp(4));
        }

        void begin() {
            next = 0;
        }

        void put(String key, String value, int color) {
            TextView[] r;
            if (next < rows.size()) {
                r = rows.get(next);
            } else {
                LinearLayout line = horizontal();
                line.setGravity(Gravity.TOP);
                line.setPadding(0, dp(5), 0, dp(5));
                TextView k = label(13, TEXT_DIM);
                TextView v = label(14, TEXT);
                v.setGravity(Gravity.END);
                line.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
                lp.leftMargin = dp(8);
                line.addView(v, lp);
                box.addView(line, matchWrap());
                r = new TextView[] {k, v};
                rows.add(r);
            }
            next++;
            show((View) r[0].getParent(), true);
            setText(r[0], key);
            setText(r[1], value);
            setColor(r[1], color);
        }

        void end() {
            for (int i = next; i < rows.size(); i++) show((View) rows.get(i)[0].getParent(), false);
        }

        void appendTo(StringBuilder b) {
            for (int i = 0; i < next; i++) {
                TextView[] r = rows.get(i);
                b.append(r[0].getText()).append(": ").append(r[1].getText()).append('\n');
            }
        }
    }

    // ------------------------------------------------------------------ view helpers

    private LinearLayout pageContent() {
        LinearLayout c = vertical();
        c.setPadding(dp(4), dp(8), dp(4), dp(12));
        return c;
    }

    private ScrollView scroll(View content) {
        ScrollView s = new ScrollView(ctx);
        s.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private LinearLayout card() {
        LinearLayout c = vertical();
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        c.setBackground(rounded(SURFACE, dp(12), 0));
        return c;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView action(String text, boolean primary, View.OnClickListener click) {
        TextView t = button(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setMinHeight(dp(48));
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        if (primary) {
            t.setBackground(pressable(ACCENT, dp(8), 0));
            t.setTextColor(PANEL_BG);
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        t.setOnClickListener(click);
        return t;
    }

    /** Tab or segment: gold text on a raised surface while selected. */
    private TextView choiceButton(String text) {
        TextView t = label(13, TEXT_DIM);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_selected}, {}}, new int[] {ACCENT, TEXT_DIM}));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_selected}, rounded(SURFACE, dp(10), LINE));
        states.addState(new int[0], rounded(Color.TRANSPARENT, dp(10), 0));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(PRESSED), states, rounded(Color.WHITE, dp(10), 0)));
        t.setClickable(true);
        return t;
    }

    private TextView iconButton(String glyph, String description) {
        TextView t = button(glyph);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setBackground(pressable(Color.TRANSPARENT, dp(22), 0));
        t.setContentDescription(description);
        return t;
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(ctx);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private static void show(View v, boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != vis) v.setVisibility(vis);
    }

    private static void setText(TextView t, CharSequence s) {
        if (!TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    private static void setColor(TextView t, int color) {
        if (t.getCurrentTextColor() != color) t.setTextColor(color);
    }

    private Switch makeSwitch(String label) {
        Switch s = new Switch(ctx);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setMinHeight(dp(48));
        s.setPadding(0, dp(4), 0, dp(4));
        s.setSwitchPadding(dp(12));
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[] {ACCENT, TEXT_DIM}));
        s.setTrackTintList(new ColorStateList(states, new int[] {0x99FFD884, 0x55B4C0CD}));
        return s;
    }

    private TextView button(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pressable(SURFACE, dp(8), LINE));
        t.setClickable(true);
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeColor != 0) d.setStroke(Math.max(1, dp(1)), strokeColor);
        return d;
    }

    /** Rounded background with the platform ripple as touch feedback. */
    private Drawable pressable(int color, int radius, int strokeColor) {
        return new RippleDrawable(ColorStateList.valueOf(PRESSED), rounded(color, radius, strokeColor),
                rounded(Color.WHITE, radius, 0));
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        return lp;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(12);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Vertical LinearLayout that always takes {@link #fixedHeight} (capped by its parent): the tab bar never jumps. */
    private static final class PanelLayout extends LinearLayout {
        int fixedHeight;

        PanelLayout(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int h = fixedHeight;
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) h = Math.min(h, MeasureSpec.getSize(heightSpec));
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }
}
