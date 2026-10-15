package com.toemod;

import java.util.Locale;

/**
 * The read-only values {@link Native#debugSnapshot} copies from the native cache, and how the menu
 * presents them honestly: NaN is "never sampled / unavailable" and is never shown as zero, and a
 * sample older than {@link #STALE_MS} is no longer reported as the live game state.
 * Plain Java (no Android) so the host tests can exercise it.
 */
final class Diagnostics {
    static final int SIZE = 32;

    static final int NATIVE_READY = 0;
    static final int HOOKS_INSTALLED = 1;
    static final int HOOKS_FAILED = 2;
    static final int PATCHES_FAILED = 3;
    static final int WEATHER_AGE_MS = 4;
    static final int WEATHER_ENABLED = 5;
    static final int WEATHER_REQUESTED = 6;
    static final int WEATHER_CURRENT = 7;
    static final int WEATHER_NEXT = 8;
    static final int WEATHER_TIMER_S = 9;
    static final int WEATHER_CHANGING = 10;
    static final int RAIN = 11;
    static final int CLOUD = 12;
    static final int MIST = 13;
    static final int WEATHER_TARGET = 14;
    static final int NO_RAIN_AREA = 15;
    static final int STOP_RAIN_AREA = 16;
    static final int HOUR = 17;
    static final int TRUCK_AGE_MS = 18;
    static final int SPEED_KPH = 19;
    static final int FUEL_L = 20;
    static final int FUEL_FRACTION = 21;
    static final int HEALTH_FRACTION = 22;
    static final int DIRT = 23;
    static final int DUST = 24;
    static final int TRUCK_RAINING = 25;
    static final int REQUEST_GENERATION = 26;
    static final int APPLIED_GENERATION = 27;
    static final int TRANSITIONS = 28;

    /** Minimum time between two native reads (at most 2 Hz). */
    static final long POLL_MS = 500;
    /** Older samples mean the game stopped updating (pause, menus, loading): not the live state. */
    static final double STALE_MS = 5000;

    static final int CLEAR = 0;
    static final int RAINY = 1;
    static final int CLOUDY = 2;
    static final int FOGGY = 3;
    static final int AFTER_RAIN = 4;
    private static final String[] WEATHER = {"Clear", "Rainy", "Cloudy", "Foggy", "After rain"};

    enum Freshness { MISSING, FRESH, STALE }

    /** Forced-weather state as observed in the game, not as requested by the menu. */
    enum Weather { WAITING, STALE, OFF, QUEUED, TRANSITIONING, ACTIVE, SUPPRESSED }

    private Diagnostics() {}

    static boolean known(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    static Freshness freshness(double ageMs) {
        if (!known(ageMs) || ageMs < 0) return Freshness.MISSING;
        return ageMs > STALE_MS ? Freshness.STALE : Freshness.FRESH;
    }

    /** SkyValues condition 0..4, or -1 when missing or not a known condition. */
    static int weatherCode(double v) {
        if (!known(v) || v != Math.rint(v) || v < CLEAR || v > AFTER_RAIN) return -1;
        return (int) v;
    }

    private static boolean set(double flag) {
        return known(flag) && flag != 0;
    }

    static Weather weather(double[] s) {
        switch (freshness(s[WEATHER_AGE_MS])) {
            case MISSING:
                return Weather.WAITING;
            case STALE:
                return Weather.STALE;
            default:
                break;
        }
        if (!known(s[WEATHER_ENABLED])) return Weather.WAITING;
        if (s[WEATHER_ENABLED] == 0) return Weather.OFF;
        double requested = s[REQUEST_GENERATION];
        double applied = s[APPLIED_GENERATION];
        if (known(requested) && known(applied) && requested != applied) return Weather.QUEUED;
        int want = weatherCode(s[WEATHER_REQUESTED]);
        int now = weatherCode(s[WEATHER_CURRENT]);
        if (want < 0 || now < 0) return Weather.WAITING;
        boolean changing = set(s[WEATHER_CHANGING]);
        if (want == RAINY && set(s[STOP_RAIN_AREA])) return Weather.SUPPRESSED;
        if (now != want || changing) return Weather.TRANSITIONING;
        if (want == RAINY && set(s[NO_RAIN_AREA])) return Weather.SUPPRESSED;
        return Weather.ACTIVE;
    }

    static String headline(Weather w, double[] s) {
        String want = weatherName(s[WEATHER_REQUESTED]);
        switch (w) {
            case WAITING:
                return "Waiting for driving scene";
            case STALE:
                return "No live weather sample";
            case OFF:
                return "Game weather (override off)";
            case QUEUED:
                return "Queued: " + want;
            case TRANSITIONING:
                return "Transitioning to " + want;
            case SUPPRESSED:
                return "Suppressed: " + want;
            default:
                return "Active: " + want;
        }
    }

    /** What the sky is doing right now; empty while there is no live sample. */
    static String detail(Weather w, double[] s) {
        if (w == Weather.WAITING) return "Status appears once the game updates the sky while driving.";
        if (w == Weather.STALE) return "Last sample " + age(s[WEATHER_AGE_MS]) + ": paused, in a menu or loading.";
        StringBuilder b = new StringBuilder("Now ").append(weatherName(s[WEATHER_CURRENT]));
        int next = weatherCode(s[WEATHER_NEXT]);
        if (set(s[WEATHER_CHANGING])) {
            b.append(" \u2192 ").append(weatherName(s[WEATHER_NEXT]));
            int intensity = next == RAINY ? RAIN : next == CLOUDY ? CLOUD : next == FOGGY ? MIST : -1;
            if (intensity >= 0 && known(s[intensity]) && known(s[WEATHER_TARGET])) {
                b.append(String.format(Locale.US, " (%.2f of %.2f)", s[intensity], s[WEATHER_TARGET]));
            }
        } else if (next >= 0 && next != weatherCode(s[WEATHER_CURRENT]) && known(s[WEATHER_TIMER_S])) {
            b.append(" \u00B7 ").append(weatherName(s[WEATHER_NEXT])).append(" in ").append(number(s[WEATHER_TIMER_S], 0, "s"));
        }
        if (known(s[HOUR])) b.append(" \u00B7 clock ").append(clock(s[HOUR]));
        return b.toString();
    }

    /** Why the observed weather differs from the request; empty when nothing needs explaining. */
    static String hint(Weather w, double[] s) {
        switch (w) {
            case OFF:
                return "Force weather or the Mods switch is off, so the game picks the weather.";
            case QUEUED:
                return "Applies on the next game update.";
            case SUPPRESSED:
                return "Tunnel or no-rain area: the game hides rain here; it returns outside.";
            case TRANSITIONING:
                int now = weatherCode(s[WEATHER_CURRENT]);
                int want = weatherCode(s[WEATHER_REQUESTED]);
                if (now == AFTER_RAIN) return "Roads dry after rain before the next weather starts.";
                if (now == RAINY) return "Rain ends through After rain (drying) first.";
                if (now != CLEAR && want != CLEAR) return "The current weather fades through Clear first.";
                return "";
            default:
                return "";
        }
    }

    static String weatherName(double v) {
        int c = weatherCode(v);
        if (c >= 0) return WEATHER[c];
        return known(v) ? "unknown (" + count(v) + ")" : "unavailable";
    }

    static String age(double ms) {
        Freshness f = freshness(ms);
        if (f == Freshness.MISSING) return "never sampled";
        String s = String.format(Locale.US, "%.1f s ago", ms / 1000);
        return f == Freshness.STALE ? s + " (stale)" : s;
    }

    static String number(double v, int decimals, String unit) {
        if (!known(v)) return "unavailable";
        String s = String.format(Locale.US, "%." + decimals + "f", v + 0.0); // + 0.0: no "-0"
        return unit.isEmpty() ? s : s + " " + unit;
    }

    /** A 0..1 fraction as a percentage, deliberately not clamped: out-of-range game values stay visible. */
    static String percent(double fraction) {
        if (!known(fraction)) return "unavailable";
        return String.format(Locale.US, "%.0f%%", fraction * 100 + 0.0);
    }

    static String count(double v) {
        return known(v) ? Long.toString(Math.round(v)) : "unavailable";
    }

    static String flag(double v) {
        if (!known(v)) return "unknown";
        return v != 0 ? "yes" : "no";
    }

    /** Hour of day as HH:MM, truncated so 23.999 h reads 23:59 rather than a non-existent 24:00. */
    static String clock(double hour) {
        if (!known(hour)) return "unavailable";
        if (hour < 0 || hour > 24) return number(hour, 2, "h");
        int minutes = (int) Math.floor(hour * 60 + 1e-6);
        if (hour < 24) minutes = Math.min(minutes, 24 * 60 - 1);
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
