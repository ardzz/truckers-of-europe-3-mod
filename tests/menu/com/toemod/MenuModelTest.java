package com.toemod;

import java.util.Arrays;

/**
 * Host checks of the menu rules that need no Android: diagnostics honesty (a missing or stale
 * sample never reads as live, NaN never reads as zero), the observed forced-weather state, when the
 * native snapshot may be read, and exact value entry within a control's native range.
 * Run by ci/test_menu_model.py.
 */
public final class MenuModelTest {
    private static int passed;
    private static int failed;

    interface Body {
        void run();
    }

    public static void main(String[] args) {
        freshnessChecks();
        weatherChecks();
        formattingChecks();
        pollingChecks();
        valueEntryChecks();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0 || passed == 0) System.exit(1);
    }

    // ------------------------------------------------------------------ sample age

    private static void freshnessChecks() {
        check("never sampled is missing", () -> eq(Diagnostics.Freshness.MISSING, Diagnostics.freshness(Double.NaN)));
        check("infinite age is missing", () -> eq(Diagnostics.Freshness.MISSING, Diagnostics.freshness(Double.POSITIVE_INFINITY)));
        check("negative age is missing", () -> eq(Diagnostics.Freshness.MISSING, Diagnostics.freshness(-1)));
        check("just sampled is fresh", () -> eq(Diagnostics.Freshness.FRESH, Diagnostics.freshness(0)));
        check("exactly 5 s is still fresh", () -> eq(Diagnostics.Freshness.FRESH, Diagnostics.freshness(5000)));
        check("beyond 5 s is stale", () -> eq(Diagnostics.Freshness.STALE, Diagnostics.freshness(5000.5)));
    }

    // ------------------------------------------------------------------ forced weather

    private static final int CLEAR = Diagnostics.CLEAR;
    private static final int RAINY = Diagnostics.RAINY;
    private static final int CLOUDY = Diagnostics.CLOUDY;
    private static final int FOGGY = Diagnostics.FOGGY;
    private static final int AFTER_RAIN = Diagnostics.AFTER_RAIN;

    /** A fresh sky sample with the override on and every request applied. */
    private static double[] sky(int requested, int current, int next, boolean changing) {
        double[] s = new double[Diagnostics.SIZE];
        Arrays.fill(s, Double.NaN);
        s[Diagnostics.WEATHER_AGE_MS] = 200;
        s[Diagnostics.WEATHER_ENABLED] = 1;
        s[Diagnostics.WEATHER_REQUESTED] = requested;
        s[Diagnostics.WEATHER_CURRENT] = current;
        s[Diagnostics.WEATHER_NEXT] = next;
        s[Diagnostics.WEATHER_CHANGING] = changing ? 1 : 0;
        s[Diagnostics.NO_RAIN_AREA] = 0;
        s[Diagnostics.STOP_RAIN_AREA] = 0;
        s[Diagnostics.REQUEST_GENERATION] = 3;
        s[Diagnostics.APPLIED_GENERATION] = 3;
        return s;
    }

    private static double[] with(double[] s, int index, double v) {
        s[index] = v;
        return s;
    }

    private static void weather(String name, Diagnostics.Weather expected, double[] s) {
        check(name, () -> eq(expected, Diagnostics.weather(s)));
    }

    private static void weatherChecks() {
        double[] none = new double[Diagnostics.SIZE];
        Arrays.fill(none, Double.NaN);
        weather("an empty buffer waits for a driving scene", Diagnostics.Weather.WAITING, none);
        weather("target fields without a sample age are not active", Diagnostics.Weather.WAITING,
                with(sky(RAINY, RAINY, RAINY, false), Diagnostics.WEATHER_AGE_MS, Double.NaN));
        weather("a stale sample at the target is not reported active", Diagnostics.Weather.STALE,
                with(sky(RAINY, RAINY, RAINY, false), Diagnostics.WEATHER_AGE_MS, 5001));
        weather("override off is reported even when the sky matches", Diagnostics.Weather.OFF,
                with(sky(RAINY, RAINY, RAINY, false), Diagnostics.WEATHER_ENABLED, 0));
        weather("unknown override state waits", Diagnostics.Weather.WAITING,
                with(sky(RAINY, RAINY, RAINY, false), Diagnostics.WEATHER_ENABLED, Double.NaN));

        weather("a newer request than applied is queued", Diagnostics.Weather.QUEUED,
                with(sky(CLOUDY, RAINY, RAINY, false), Diagnostics.REQUEST_GENERATION, 4));
        weather("re-selecting during a vanilla transition is queued, not active", Diagnostics.Weather.QUEUED,
                with(sky(RAINY, RAINY, CLEAR, true), Diagnostics.REQUEST_GENERATION, 5));
        weather("missing generations do not block the state", Diagnostics.Weather.ACTIVE,
                with(with(sky(FOGGY, FOGGY, FOGGY, false), Diagnostics.REQUEST_GENERATION, Double.NaN),
                        Diagnostics.APPLIED_GENERATION, Double.NaN));

        weather("applied but not started yet is transitioning", Diagnostics.Weather.TRANSITIONING,
                sky(RAINY, CLEAR, RAINY, false));
        weather("ramping toward the request is transitioning", Diagnostics.Weather.TRANSITIONING,
                sky(FOGGY, CLEAR, FOGGY, true));
        weather("another non-clear weather fades through Clear", Diagnostics.Weather.TRANSITIONING,
                sky(FOGGY, RAINY, CLEAR, true));
        weather("drying after rain is transitioning", Diagnostics.Weather.TRANSITIONING,
                sky(CLEAR, AFTER_RAIN, CLEAR, true));
        weather("leaving the requested weather is transitioning", Diagnostics.Weather.TRANSITIONING,
                sky(RAINY, RAINY, CLEAR, true));

        weather("settled rain is active", Diagnostics.Weather.ACTIVE, sky(RAINY, RAINY, RAINY, false));
        weather("settled clear is active", Diagnostics.Weather.ACTIVE, sky(CLEAR, CLEAR, CLEAR, false));

        weather("rain in a no-rain area is suppressed", Diagnostics.Weather.SUPPRESSED,
                with(sky(RAINY, RAINY, RAINY, false), Diagnostics.NO_RAIN_AREA, 1));
        weather("rain in a stop-rain zone is suppressed", Diagnostics.Weather.SUPPRESSED,
                with(sky(RAINY, RAINY, RAINY, false), Diagnostics.STOP_RAIN_AREA, 1));
        weather("a stop-rain zone that settled clear is suppressed, not stuck transitioning",
                Diagnostics.Weather.SUPPRESSED,
                with(sky(RAINY, CLEAR, CLEAR, false), Diagnostics.STOP_RAIN_AREA, 1));
        weather("fog is not suppressed by a no-rain area", Diagnostics.Weather.ACTIVE,
                with(sky(FOGGY, FOGGY, FOGGY, false), Diagnostics.NO_RAIN_AREA, 1));
        weather("rain still on its way is not suppressed yet", Diagnostics.Weather.TRANSITIONING,
                with(sky(RAINY, CLEAR, RAINY, true), Diagnostics.NO_RAIN_AREA, 1));

        double[] restoringRain = sky(RAINY, RAINY, RAINY, true);
        restoringRain[Diagnostics.RAIN] = 0.0;
        restoringRain[Diagnostics.WEATHER_TARGET] = 0.8;
        weather("zero rain still being restored is not reported active",
                Diagnostics.Weather.TRANSITIONING, restoringRain);

        weather("an unknown condition code waits", Diagnostics.Weather.WAITING, sky(RAINY, 7, RAINY, false));
        weather("a fractional condition code waits", Diagnostics.Weather.WAITING,
                with(sky(RAINY, RAINY, RAINY, false), Diagnostics.WEATHER_CURRENT, 1.5));
        weather("a missing requested weather waits", Diagnostics.Weather.WAITING,
                with(sky(RAINY, RAINY, RAINY, false), Diagnostics.WEATHER_REQUESTED, Double.NaN));
    }

    // ------------------------------------------------------------------ presentation

    private static void noDigits(String name, String shown) {
        check(name + " (" + shown + ")", () -> truth(!shown.matches(".*\\d.*")));
    }

    private static void formattingChecks() {
        noDigits("missing number is not shown as zero", Diagnostics.number(Double.NaN, 1, "km/h"));
        noDigits("missing percentage is not shown as zero", Diagnostics.percent(Double.NaN));
        noDigits("missing count is not shown as zero", Diagnostics.count(Double.NaN));
        noDigits("missing flag is not shown as no", Diagnostics.flag(Double.NaN));
        check("missing flag is distinct from false", () -> truth(!Diagnostics.flag(Double.NaN).equals(Diagnostics.flag(0))));
        noDigits("missing clock is not midnight", Diagnostics.clock(Double.NaN));
        noDigits("missing weather is not Clear", Diagnostics.weatherName(Double.NaN));
        check("missing weather is not named Clear", () -> truth(!Diagnostics.weatherName(Double.NaN).contains("Clear")));
        noDigits("never sampled age has no number", Diagnostics.age(Double.NaN));
        check("an age past 5 s is labelled stale", () -> truth(Diagnostics.age(5001).contains("stale")));
        check("an age within 5 s is not labelled stale", () -> truth(!Diagnostics.age(4999).contains("stale")));
        check("out-of-range fractions are not clamped", () -> truth(Diagnostics.percent(1.25).contains("125")));
        check("negative zero is not shown as -0", () -> eq("0.0 km/h", Diagnostics.number(-0.0, 1, "km/h")));
        check("clock reads half past", () -> eq("13:30", Diagnostics.clock(13.5)));
        check("clock reads midnight", () -> eq("00:00", Diagnostics.clock(0)));
        check("clock never rounds up to 24:00", () -> eq("23:59", Diagnostics.clock(23.9999)));
        check("hours outside a day are not shown as a clock", () -> truth(!Diagnostics.clock(30).contains(":")));
        check("all five conditions have names", () -> {
            for (int c = CLEAR; c <= AFTER_RAIN; c++) truth(!Diagnostics.weatherName(c).contains("unknown"));
        });
    }

    // ------------------------------------------------------------------ screens

    private static void pollingChecks() {
        check("World on screen reads diagnostics", () -> truth(MenuModel.needsSnapshot(MenuModel.Page.WORLD, true, true)));
        check("Debug on screen reads diagnostics", () -> truth(MenuModel.needsSnapshot(MenuModel.Page.DEBUG, true, true)));
        check("Home, categories, Cloud and Updates never read diagnostics", () -> {
            for (MenuModel.Page p : new MenuModel.Page[] {MenuModel.Page.HOME, MenuModel.Page.CATEGORY, MenuModel.Page.CLOUD,
                    MenuModel.Page.UPDATES}) {
                truth(!MenuModel.needsSnapshot(p, true, true));
            }
        });
        check("a collapsed panel stops reading", () -> {
            truth(!MenuModel.needsSnapshot(MenuModel.Page.WORLD, false, true));
            truth(!MenuModel.needsSnapshot(MenuModel.Page.DEBUG, false, true));
        });
        check("a paused game stops reading", () -> {
            truth(!MenuModel.needsSnapshot(MenuModel.Page.WORLD, true, false));
            truth(!MenuModel.needsSnapshot(MenuModel.Page.DEBUG, true, false));
        });
        check("a category screen belongs to the Home tab", () -> eq(MenuModel.Page.HOME, MenuModel.tab(MenuModel.Page.CATEGORY)));
        check("tab screens keep their own tab", () -> {
            for (MenuModel.Page p : MenuModel.Page.values()) if (p != MenuModel.Page.CATEGORY) eq(p, MenuModel.tab(p));
        });
    }

    // ------------------------------------------------------------------ exact values

    private static void accepts(String name, String text, float min, float max, boolean integer, float expected) {
        check(name, () -> eq(expected, MenuModel.parseValue(text, min, max, integer)));
    }

    private static void refuses(String name, String text, float min, float max, boolean integer) {
        check(name, () -> truth(Float.isNaN(MenuModel.parseValue(text, min, max, integer))));
    }

    private static void valueEntryChecks() {
        accepts("whole number at the top of the range", "200", 0, 200, true, 200f);
        accepts("whole number at the bottom of the range", "1", 1, 200, true, 1f);
        refuses("one above the range is refused, not clamped", "201", 0, 200, true);
        refuses("below the range is refused, not clamped", "0", 1, 200, true);
        refuses("negative money is refused", "-1", 0, 100000000, true);
        refuses("a fraction for a whole-number control", "1.5", 0, 200, true);
        refuses("a decimal point for a whole-number control", "2.0", 0, 200, true);
        refuses("grouping separators for a whole-number control", "1,000", 0, 100000000, true);
        refuses("empty text", "", 0, 200, true);
        refuses("blank text", "   ", 0, 200, false);
        refuses("null text", null, 0, 200, false);
        for (String hostile : new String[] {"NaN", "Infinity", "-Infinity", "1e3", "0x10", "2f", "2d", "+5", "5-", "1..2", "--1"}) {
            refuses("non-plain number " + hostile, hostile, -1000, 1000, false);
        }
        refuses("absurdly long digit strings", "1234567890123", 0, Float.MAX_VALUE, true);
        accepts("decimal multiplier", "2.5", 1, 20, false, 2.5f);
        accepts("decimal comma", "1,5", 1, 20, false, 1.5f);
        accepts("surrounding spaces", " 20 ", 1, 20, false, 20f);
        accepts("leading decimal point", ".5", 0, 24, false, 0.5f);
        refuses("just above a fractional maximum", "20.000001", 1, 20, false);
        accepts("negative ranges stay usable", "-5", -10, 10, false, -5f);
        check("float rounding never leaves the range", () -> {
            float v = MenuModel.parseValue("99999999", 0, 100000000, true);
            truth(!Float.isNaN(v) && v <= 100000000f && v >= 0);
        });
    }

    // ------------------------------------------------------------------ harness

    private static void check(String name, Body body) {
        try {
            body.run();
            passed++;
        } catch (AssertionError | RuntimeException e) {
            failed++;
            System.out.println("FAIL " + name + ": " + e);
        }
    }

    private static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static void truth(boolean condition) {
        if (!condition) throw new AssertionError("condition false");
    }
}
