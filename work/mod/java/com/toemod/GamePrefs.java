package com.toemod;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** The game's own PlayerPrefs store, where Unity keeps the whole save (not the mod's "toemod" settings). */
final class GamePrefs {
    private GamePrefs() {}

    private static SharedPreferences open(Context c) {
        return c.getSharedPreferences(c.getPackageName() + ".v2.playerprefs", Context.MODE_PRIVATE);
    }

    /** A copy of every value as Unity last saved it. */
    static Map<String, Object> read(Context c) {
        return new HashMap<String, Object>(open(c).getAll());
    }

    /**
     * Replaces every value with {@code values} in one synchronous commit. Returns false when Android
     * could not write the file; the in-memory store then already holds {@code values}.
     */
    static boolean replace(Context c, Map<String, Object> values) {
        SharedPreferences.Editor ed = open(c).edit().clear();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v instanceof String) ed.putString(k, (String) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Float) ed.putFloat(k, (Float) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else ed.putStringSet(k, strings(v));
        }
        return ed.commit();
    }

    @SuppressWarnings("unchecked") // SnapshotFormat only produces Set<String> besides the scalar types
    private static Set<String> strings(Object v) {
        return (Set<String>) v;
    }
}
