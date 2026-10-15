package com.toemod;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Floating in-game mod menu. Entry point {@link #start(Activity)} is injected into
 * {@code UnityPlayerActivity.onCreate} right after {@code super.onCreate}.
 *
 * The overlay is a transparent, non-clickable FrameLayout added to the activity's content
 * view (no overlay permission). Only the icon/panel consume touches; everything else falls
 * through to the Unity player. The Updates section works independently of the native hooks,
 * so a broken or disabled mod can still be updated.
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

    private static final int ACCENT = 0xFFFF9800;
    private static final int ERROR = 0xFFE53935;
    private static final int TEXT = 0xFFEEEEEE;
    private static final int TEXT_DIM = 0xFF9E9E9E;
    private static final int PANEL_BG = 0xF0181818;
    private static final int PANEL_W_DP = 320;
    private static final int ICON_DP = 46;
    private static final float MAX_H_FRACTION = 0.8f;

    // Process-wide: the native library and hook state outlive a recreated activity.
    private static boolean sLibLoaded;
    private static String sLibError; // set once System.loadLibrary failed in this process
    private static int sState = WAITING;
    private static List<Feature> sFeatures;
    private static Menu sCurrent;

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

    private boolean master;
    private float posFx;
    private float posFy;
    private long pollStart;
    private String failMsg = "";

    private FrameLayout root;
    private TextView icon;
    private MaxHeightLayout panel;
    private TextView status;
    private LinearLayout list;
    private TextView upInstalled;
    private TextView upStatus;
    private ProgressBar upProgress;
    private TextView upSaveHint;
    private TextView upCheck;
    private TextView upDownload;
    private TextView upInstall;
    private TextView upCancel;
    private TextView upRelease;

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
        lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity act, Bundle b) {}
            @Override public void onActivityStarted(Activity act) {}
            @Override public void onActivityResumed(Activity act) {
                if (act != activity) return;
                updater.onResume();
                ensureAttached();
            }
            @Override public void onActivityPaused(Activity act) {}
            @Override public void onActivityStopped(Activity act) {}
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
        updateStatus();
        updater.setListener(updaterListener);
        renderUpdates();
    }

    private void destroy() {
        handler.removeCallbacksAndMessages(null);
        updater.clearListener(updaterListener);
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
        updateStatus();
    }

    private void fail(String msg) {
        sState = FAILED;
        failMsg = msg;
        Log.e(TAG, msg);
        Toast.makeText(activity, "toemod: " + msg + " - mods inactive", Toast.LENGTH_LONG).show();
        list.removeAllViews();
        list.addView(hint("Mod inactive: " + msg));
        updateStatus();
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
        list.setAlpha(on ? 1f : 0.5f);
        updateStatus();
    }

    private void updateStatus() {
        if (status == null) return;
        int ring;
        switch (sState) {
            case READY:
                int n = sFeatures.size();
                status.setText(n + " features" + (master ? "" : " - mods OFF"));
                status.setTextColor(TEXT_DIM);
                ring = master ? ACCENT : TEXT_DIM;
                break;
            case FAILED:
                status.setText(failMsg);
                status.setTextColor(ERROR);
                ring = ERROR;
                break;
            default:
                status.setText("waiting for game hooks...");
                status.setTextColor(TEXT_DIM);
                ring = TEXT_DIM;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xE0202020);
        bg.setStroke(dp(3), ring);
        icon.setBackground(bg);
    }

    // ------------------------------------------------------------------ geometry

    private void onRootResized() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) return;
        panel.maxHeight = (int) (h * MAX_H_FRACTION);
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
    }

    private void collapse() {
        panel.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        place(icon);
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

    // ------------------------------------------------------------------ views

    private TextView buildIcon() {
        TextView t = new TextView(ctx);
        t.setText("TOE");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription("Mod menu");
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expand();
            }
        });
        t.setOnTouchListener(new Drag(t));
        return t;
    }

    private MaxHeightLayout buildPanel() {
        MaxHeightLayout p = new MaxHeightLayout(ctx);
        p.maxHeight = (int) (ctx.getResources().getDisplayMetrics().heightPixels * MAX_H_FRACTION);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(12), dp(8), dp(12), dp(10));
        p.setBackground(rounded(PANEL_BG, dp(12), 0x66FF9800));
        p.setClickable(true); // touches on the panel's own area must not reach the game

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(ctx);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("TOE3 Mod Menu");
        title.setTextColor(ACCENT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title);
        status = new TextView(ctx);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        titleBox.addView(status);
        titleBox.setOnTouchListener(new Drag(p)); // header is the drag handle
        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = button("\u00D7");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        close.setContentDescription("Collapse");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapse();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        p.addView(header);

        Switch masterSwitch = makeSwitch("Mods enabled");
        masterSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        masterSwitch.setChecked(master);
        masterSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                setMaster(checked);
            }
        });
        p.addView(masterSwitch, matchWrap());
        p.addView(divider());

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setAlpha(master ? 1f : 0.5f);
        list.addView(hint("Waiting for hooks to install..."));
        content.addView(list, matchWrap());
        // Outside the dimmed feature list: updating must work with mods off or the hooks failed.
        content.addView(divider());
        content.addView(buildUpdates(), matchWrap());
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Last child: LinearLayout hands it only the height left under maxHeight, so it scrolls.
        p.addView(scroll, matchWrap());
        return p;
    }

    // ------------------------------------------------------------------ updates

    private View buildUpdates() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(4));

        TextView head = label(13, ACCENT);
        head.setText("Updates");
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(0, dp(10), 0, dp(4));
        box.addView(head, matchWrap());

        upInstalled = label(12, TEXT_DIM);
        box.addView(upInstalled, matchWrap());
        upStatus = label(13, TEXT);
        upStatus.setPadding(0, dp(4), 0, 0);
        box.addView(upStatus, matchWrap());
        upProgress = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        upProgress.setMax(1000);
        upProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        box.addView(upProgress, matchWrap());
        upSaveHint = label(12, ACCENT);
        upSaveHint.setText("Save your game before installing: Android closes the game to update it.");
        upSaveHint.setPadding(0, dp(4), 0, 0);
        box.addView(upSaveHint, matchWrap());

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
        upCheck = action("Check", false, new View.OnClickListener() {
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
        HorizontalScrollView actions = new HorizontalScrollView(ctx);
        actions.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        for (TextView b : new TextView[] {upDownload, upInstall, upCancel, upCheck, upRelease}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lp.rightMargin = dp(6);
            row.addView(b, lp);
        }
        actions.addView(row);
        box.addView(actions, matchWrap());
        return box;
    }

    private void renderUpdates() {
        if (upStatus == null) return;
        Updater.Phase p = updater.phase();
        UpdatePolicy.Identity inst = updater.installed();
        upInstalled.setText(inst == null ? "Installed: unknown" : "Installed: "
                + (inst.modBuild == 0 ? "local build" : "build " + inst.modBuild) + " \u00B7 game " + inst.versionName
                + "\n" + inst.tag);

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

    private TextView action(String text, boolean primary, View.OnClickListener click) {
        TextView t = button(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(12), 0, dp(12), 0);
        if (primary) t.setBackground(rounded(0x66FF9800, dp(6), 0));
        t.setOnClickListener(click);
        return t;
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(ctx);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showFeatures() {
        list.removeAllViews();
        Map<String, List<Feature>> groups = new LinkedHashMap<>();
        for (Feature f : sFeatures) {
            List<Feature> g = groups.get(f.category);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(f.category, g);
            }
            g.add(f);
        }
        if (groups.isEmpty()) list.addView(hint("No features registered."));
        for (Map.Entry<String, List<Feature>> e : groups.entrySet()) {
            final String name = e.getKey() + " (" + e.getValue().size() + ")";
            final LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            for (Feature f : e.getValue()) body.addView(buildRow(f), matchWrap());

            final TextView head = new TextView(ctx);
            head.setText("\u25BC " + name);
            head.setTextColor(ACCENT);
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setPadding(0, dp(10), 0, dp(4));
            head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean show = body.getVisibility() != View.VISIBLE;
                    body.setVisibility(show ? View.VISIBLE : View.GONE);
                    head.setText((show ? "\u25BC " : "\u25BA ") + name);
                }
            });
            list.addView(head, matchWrap());
            list.addView(body, matchWrap());
        }
    }

    private View buildRow(final Feature f) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(2), 0, dp(4));

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

        if (f.hasSlider()) row.addView(buildSlider(f), matchWrap());
        else if (f.hasChoices()) row.addView(buildChoices(f), matchWrap());
        return row;
    }

    private View buildSlider(final Feature f) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        final int steps = f.steps();
        final TextView val = new TextView(ctx);
        val.setText(f.format(f.value));
        val.setTextColor(ACCENT);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        val.setMinWidth(dp(56));

        final SeekBar sb = new SeekBar(ctx);
        sb.setMax(steps);
        sb.setProgress(f.toStep(f.value)); // before the listener: no spurious set()
        ColorStateList accent = ColorStateList.valueOf(ACCENT);
        sb.setProgressTintList(accent);
        sb.setThumbTintList(accent);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                f.value = f.fromStep(p);
                val.setText(f.format(f.value));
                send(f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save(f);
            }
        });

        TextView minus = button("\u2212");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sb.setProgress(Math.max(0, sb.getProgress() - 1));
                save(f);
            }
        });
        TextView plus = button("+");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sb.setProgress(Math.min(steps, sb.getProgress() + 1));
                save(f);
            }
        });

        line.addView(minus, new LinearLayout.LayoutParams(dp(32), dp(32)));
        line.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(plus, new LinearLayout.LayoutParams(dp(32), dp(32)));
        line.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    private View buildChoices(final Feature f) {
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        RadioGroup group = new RadioGroup(ctx);
        group.setOrientation(LinearLayout.HORIZONTAL);
        ColorStateList tint = new ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}}, new int[] {ACCENT, TEXT_DIM});
        int selected = (int) f.value;
        int checkedId = View.NO_ID;
        for (int i = 0; i < f.choices.length; i++) {
            RadioButton rb = new RadioButton(ctx);
            rb.setId(View.generateViewId());
            rb.setTag(i);
            rb.setText(f.choices[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            rb.setButtonTintList(tint);
            rb.setPadding(0, 0, dp(10), 0);
            group.addView(rb);
            if (i == selected) checkedId = rb.getId();
        }
        if (checkedId != View.NO_ID) group.check(checkedId); // before the listener
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                View rb = g.findViewById(id);
                if (rb == null) return;
                f.value = (Integer) rb.getTag();
                save(f);
                send(f);
            }
        });
        hs.addView(group);
        return hs;
    }

    private Switch makeSwitch(String label) {
        Switch s = new Switch(ctx);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        s.setPadding(0, dp(4), 0, dp(4));
        s.setSwitchPadding(dp(8));
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[] {ACCENT, 0xFFBDBDBD}));
        s.setTrackTintList(new ColorStateList(states, new int[] {0x99FF9800, 0x55FFFFFF}));
        return s;
    }

    private TextView button(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(0x33FFFFFF, dp(6), 0));
        t.setClickable(true);
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(2);
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

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Vertical LinearLayout whose height is capped (panel = max 80% of the screen). */
    private static final class MaxHeightLayout extends LinearLayout {
        int maxHeight;

        MaxHeightLayout(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(heightSpec);
            if (mode == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(heightSpec) > maxHeight) {
                heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
