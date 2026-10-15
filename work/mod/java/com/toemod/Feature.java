package com.toemod;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One entry of {@link Native#features()} plus the user's persisted state. */
final class Feature {
    static final int TOGGLE = 0;
    static final int TOGGLE_FLOAT = 1;
    static final int TOGGLE_INT = 2;
    static final int CHOICE = 3;

    private static final int FLOAT_STEPS = 200;
    private static final int MAX_INT_STEPS = 1000;

    final String id;
    final String category;
    final String label;
    final int type;
    final boolean defOn;
    final float defValue;
    final float min;
    final float max;
    final String[] choices;

    boolean on;
    float value;

    private Feature(JSONObject o) throws JSONException {
        id = o.getString("id");
        category = str(o, "category", "Misc");
        label = str(o, "label", id);
        type = o.optInt("type", TOGGLE);
        Object on = o.opt("def_on");
        defOn = on instanceof Number ? ((Number) on).intValue() != 0 : o.optBoolean("def_on", false);
        defValue = (float) o.optDouble("def_value", 0);
        min = (float) o.optDouble("min", 0);
        max = (float) o.optDouble("max", 0);
        String c = str(o, "choices", "");
        choices = c.isEmpty() ? new String[0] : c.split("\\|");
    }

    static List<Feature> parse(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<Feature> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) out.add(new Feature(arr.getJSONObject(i)));
        return out;
    }

    /** optString() turns JSON null into "null"; treat null/missing as the fallback. */
    private static String str(JSONObject o, String key, String fallback) {
        return o.isNull(key) ? fallback : o.optString(key, fallback);
    }

    boolean hasSlider() {
        return (type == TOGGLE_FLOAT || type == TOGGLE_INT) && max > min;
    }

    boolean hasChoices() {
        return type == CHOICE && choices.length > 0;
    }

    float clamp(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) v = defValue;
        switch (type) {
            case TOGGLE_FLOAT:
            case TOGGLE_INT:
                if (max > min) v = Math.max(min, Math.min(max, v));
                return type == TOGGLE_INT ? (float) Math.rint(v) : v;
            case CHOICE:
                return choices.length == 0 ? v : Math.max(0, Math.min(choices.length - 1, (int) Math.rint(v)));
            default:
                return v;
        }
    }

    /** Number of SeekBar positions above 0 (SeekBar.setMin needs API 26, so progress is 0-based). */
    int steps() {
        if (!hasSlider()) return 0;
        if (type == TOGGLE_INT) return (int) Math.max(1, Math.min(Math.rint(max - min), MAX_INT_STEPS));
        return FLOAT_STEPS;
    }

    float fromStep(int p) {
        double v = min + (double) (max - min) * p / steps();
        if (type == TOGGLE_INT) return (float) Math.rint(v);
        double m = Math.pow(10, decimals());
        return (float) (Math.rint(v * m) / m);
    }

    int toStep(float v) {
        int n = steps();
        long p = Math.round((double) (v - min) / (max - min) * n);
        return (int) Math.max(0, Math.min(n, p));
    }

    /** Decimal places that resolve one slider step (0..3). */
    private int decimals() {
        if (type != TOGGLE_FLOAT || !hasSlider()) return 0;
        double step = (double) (max - min) / FLOAT_STEPS;
        return (int) Math.max(0, Math.min(3, Math.ceil(-Math.log10(step) - 1e-6)));
    }

    String format(float v) {
        if (type == TOGGLE_INT) return Long.toString(Math.round((double) v));
        return String.format(Locale.US, "%." + decimals() + "f", v);
    }
}
