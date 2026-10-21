package com.toemod;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain Java views of JSON text: objects become LinkedHashMaps, arrays Lists and JSON null null. */
final class JsonMaps {
    private JsonMaps() {}

    /** The JSON object that is the whole of {@code text}, or null when the text holds anything else. */
    static Map<String, Object> object(String text) throws JSONException {
        JSONTokener t = new JSONTokener(text);
        Object v = t.nextValue();
        if (!(v instanceof JSONObject) || t.nextClean() != 0) return null;
        return toMap((JSONObject) v);
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
}
