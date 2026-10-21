package com.toemod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/**
 * The toe3-prefs-snapshot v1 format: every game PlayerPrefs value as gzip-compressed JSON.
 *
 * <pre>{"format":"toe3-prefs-snapshot","version":1,"package":"…","game_version":"0.7.64","game_build":193,
 *  "mod_version":"…","created_at":"2026-10-21T05:00:00Z","reason":"manual","device":"…",
 *  "prefs":{"key":{"t":"s|i|f|l|b|ss","v":…}}}</pre>
 *
 * Keys and strings are copied raw: Unity already stores them URL-encoded ("I2%20Language"), and
 * decoding them would change the save. Floats and longs are JSON strings, because Float.toString keeps
 * NaN, infinities and float precision and JSON numbers are only exact up to 2^53. Android-free so host
 * tests cover it.
 */
final class SnapshotFormat {
    static final String FORMAT = "toe3-prefs-snapshot";
    static final int VERSION = 1;
    /** Largest compressed snapshot read from a file or downloaded (the server's limit may not exceed it). */
    static final int MAX_GZIP_BYTES = 64 << 20;
    /** Largest decompressed snapshot JSON; encode refuses more, so every backup the mod writes can be restored. */
    static final int MAX_JSON_BYTES = 16 << 20;

    private static final String NOT_A_BACKUP = "This file is not a TOE3 Stormline backup.";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SnapshotFormat() {}

    /** Who made a snapshot, when and why. Null text becomes "". */
    static final class Meta {
        final String pkg;
        final String gameVersion;
        final long gameBuild;
        final String modVersion;
        final String createdAt;
        final String reason;
        final String device;

        Meta(String pkg, String gameVersion, long gameBuild, String modVersion, String createdAt, String reason,
                String device) {
            this.pkg = text(pkg);
            this.gameVersion = text(gameVersion);
            this.gameBuild = gameBuild;
            this.modVersion = text(modVersion);
            this.createdAt = text(createdAt);
            this.reason = text(reason);
            this.device = text(device);
        }
    }

    /** A validated snapshot of the installed game. */
    static final class Snapshot {
        final Meta meta;
        /** Key to String, Integer, Float, Long, Boolean or Set of String, as SharedPreferences stores them. */
        final Map<String, Object> prefs;

        Snapshot(Meta meta, Map<String, Object> prefs) {
            this.meta = meta;
            this.prefs = Collections.unmodifiableMap(prefs);
        }

        /** True when an older game build made it; restoring is allowed, the player is warned. */
        boolean olderThan(long installedBuild) {
            return meta.gameBuild < installedBuild;
        }
    }

    // ------------------------------------------------------------------ encode

    /**
     * The snapshot JSON of {@code prefs}: pure ASCII (every other character is a \\u escape, so no
     * UTF-16 content is lost) with sorted keys, so equal values give equal bytes.
     *
     * @throws IllegalArgumentException for no values, a value type SharedPreferences cannot hold, or
     *     JSON larger than {@link #MAX_JSON_BYTES}
     */
    static String encode(Meta m, Map<String, ?> prefs) {
        if (prefs.isEmpty()) throw new IllegalArgumentException("the game has saved nothing yet");
        StringBuilder b = new StringBuilder(64 * 1024);
        b.append("{\"format\":");
        string(b, FORMAT);
        b.append(",\"version\":").append(VERSION).append(",\"package\":");
        string(b, m.pkg);
        b.append(",\"game_version\":");
        string(b, m.gameVersion);
        b.append(",\"game_build\":").append(m.gameBuild).append(",\"mod_version\":");
        string(b, m.modVersion);
        b.append(",\"created_at\":");
        string(b, m.createdAt);
        b.append(",\"reason\":");
        string(b, m.reason);
        b.append(",\"device\":");
        string(b, m.device);
        b.append(",\"prefs\":{");
        String sep = "";
        for (String key : new TreeSet<>(prefs.keySet())) {
            b.append(sep);
            sep = ",";
            string(b, key);
            b.append(':');
            typed(b, key, prefs.get(key));
        }
        b.append("}}");
        if (b.length() > MAX_JSON_BYTES) throw new IllegalArgumentException("the save is larger than 16 MiB");
        return b.toString();
    }

    private static void typed(StringBuilder b, String key, Object v) {
        if (v instanceof String) {
            b.append("{\"t\":\"s\",\"v\":");
            string(b, (String) v);
        } else if (v instanceof Integer) {
            b.append("{\"t\":\"i\",\"v\":").append(((Integer) v).intValue());
        } else if (v instanceof Float) {
            b.append("{\"t\":\"f\",\"v\":");
            string(b, Float.toString((Float) v));
        } else if (v instanceof Long) {
            b.append("{\"t\":\"l\",\"v\":");
            string(b, Long.toString((Long) v));
        } else if (v instanceof Boolean) {
            b.append("{\"t\":\"b\",\"v\":").append(((Boolean) v).booleanValue());
        } else if (v instanceof Set) {
            b.append("{\"t\":\"ss\",\"v\":[");
            String sep = "";
            for (String s : sortedStrings(key, (Set<?>) v)) {
                b.append(sep);
                sep = ",";
                string(b, s);
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("unsupported value for " + key + ": "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        b.append('}');
    }

    private static TreeSet<String> sortedStrings(String key, Set<?> set) {
        TreeSet<String> sorted = new TreeSet<>();
        for (Object o : set) {
            if (!(o instanceof String)) throw new IllegalArgumentException("non-string set element in " + key);
            sorted.add((String) o);
        }
        return sorted;
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c >= 0x20 && c < 0x7f) {
                b.append(c);
            } else {
                b.append("\\u").append(HEX[c >> 12]).append(HEX[(c >> 8) & 15]).append(HEX[(c >> 4) & 15])
                        .append(HEX[c & 15]);
            }
        }
        b.append('"');
    }

    /** ISO-8601 UTC time with seconds, e.g. "2026-10-21T05:00:00Z". */
    static String isoUtc(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    // ------------------------------------------------------------------ decode

    /**
     * Validates a parsed snapshot (JSON objects as Maps, arrays as Lists, integers as Integer or Long;
     * null for text that is not one JSON object) for this game install. A snapshot from a newer game
     * build is refused: it may hold data the installed game does not understand.
     */
    static Snapshot decode(Map<String, ?> root, String pkg, long installedBuild) throws SnapshotException {
        if (root == null || !FORMAT.equals(root.get("format"))) throw new SnapshotException(NOT_A_BACKUP);
        Long version = integer(root.get("version"));
        if (version == null || version < 1) throw new SnapshotException(NOT_A_BACKUP);
        if (version != VERSION) throw new SnapshotException("This backup needs a newer version of the mod.");
        if (!pkg.equals(root.get("package"))) throw new SnapshotException("This backup belongs to a different game.");
        Long build = integer(root.get("game_build"));
        if (build == null || build < 0) throw new SnapshotException("This backup does not say which game build made it.");
        if (build > installedBuild) {
            throw new SnapshotException("This backup comes from game build " + build + ", newer than the installed build "
                    + installedBuild + ". Update the game first.");
        }
        Object raw = root.get("prefs");
        if (!(raw instanceof Map) || ((Map<?, ?>) raw).isEmpty()) throw new SnapshotException("This backup contains no saved game.");
        Map<String, Object> prefs = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            String key = String.valueOf(e.getKey());
            prefs.put(key, value(key, e.getValue()));
        }
        Meta m = new Meta(pkg, str(root.get("game_version")), build, str(root.get("mod_version")),
                str(root.get("created_at")), str(root.get("reason")), str(root.get("device")));
        return new Snapshot(m, prefs);
    }

    private static Object value(String key, Object raw) throws SnapshotException {
        if (raw instanceof Map) {
            Object t = ((Map<?, ?>) raw).get("t");
            Object v = ((Map<?, ?>) raw).get("v");
            if ("s".equals(t) && v instanceof String) return v;
            if ("b".equals(t) && v instanceof Boolean) return v;
            if ("i".equals(t)) {
                Long n = integer(v);
                if (n != null && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) return n.intValue();
            }
            try {
                if ("f".equals(t) && v instanceof String) return Float.parseFloat((String) v);
                if ("l".equals(t) && v instanceof String) return Long.parseLong((String) v);
            } catch (NumberFormatException unreadable) {
                // reported below
            }
            if ("ss".equals(t) && v instanceof List) {
                Set<String> set = new HashSet<>();
                for (Object o : (List<?>) v) {
                    if (!(o instanceof String)) throw unreadable(key);
                    set.add((String) o);
                }
                return set;
            }
        }
        throw unreadable(key);
    }

    private static SnapshotException unreadable(String key) {
        return new SnapshotException("This backup has an unreadable value for \"" + key + "\".");
    }

    private static Long integer(Object o) {
        return o instanceof Integer || o instanceof Long ? ((Number) o).longValue() : null;
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ bytes

    /** Gzip of the (ASCII) snapshot JSON. */
    static byte[] gzip(String json) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length() / 4 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e); // writing to memory cannot fail
        }
        return bos.toByteArray();
    }

    /**
     * The JSON inside a gzip snapshot. Accepts exactly one gzip member (RFC 1952) with a matching CRC-32 and
     * length and nothing after it, inflating at most {@link #MAX_JSON_BYTES}, and refuses text that is not
     * valid UTF-8. Decoded with Inflater directly: GZIPInputStream recurses once per extra member, so a file
     * of many empty members would overflow the stack before any size limit applied.
     */
    static String gunzip(byte[] gz) throws SnapshotException {
        Inflater inf = new Inflater(true);
        try {
            int start = gzipHeaderEnd(gz);
            inf.setInput(gz, start, gz.length - start);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(gz.length * 8, MAX_JSON_BYTES));
            byte[] buf = new byte[64 * 1024];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new SnapshotException(NOT_A_BACKUP);
                if (out.size() + n > MAX_JSON_BYTES) throw new SnapshotException("This backup is larger than 16 MiB.");
                out.write(buf, 0, n);
            }
            byte[] json = out.toByteArray();
            int trailer = gz.length - inf.getRemaining();
            CRC32 crc = new CRC32();
            crc.update(json, 0, json.length);
            if (gz.length - trailer != 8 || le32(gz, trailer) != (int) crc.getValue() || le32(gz, trailer + 4) != json.length) {
                throw new SnapshotException(NOT_A_BACKUP);
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(json)).toString();
        } catch (DataFormatException | CharacterCodingException e) {
            throw new SnapshotException(NOT_A_BACKUP);
        } finally {
            inf.end();
        }
    }

    /** Offset of the deflate data after a gzip member header (RFC 1952 section 2.3). */
    private static int gzipHeaderEnd(byte[] gz) throws SnapshotException {
        if (gz.length < 18 || (gz[0] & 0xff) != 0x1f || (gz[1] & 0xff) != 0x8b || gz[2] != 8 || (gz[3] & 0xe0) != 0) {
            throw new SnapshotException(NOT_A_BACKUP);
        }
        int flags = gz[3];
        long p = 10;
        if ((flags & 4) != 0) p = p + 2 + ((gz[10] & 0xff) | (gz[11] & 0xff) << 8); // FEXTRA
        for (int bit : new int[] {8, 16}) { // FNAME, FCOMMENT: zero-terminated
            if ((flags & bit) == 0) continue;
            while (p < gz.length && gz[(int) p] != 0) p++;
            p++;
        }
        if ((flags & 2) != 0) p += 2; // FHCRC
        if (p > gz.length - 8) throw new SnapshotException(NOT_A_BACKUP);
        return (int) p;
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }

    /** Every byte of {@code in}, failing as soon as more than {@code max} arrive. */
    static byte[] readAll(InputStream in, int max) throws IOException, SnapshotException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        for (int r; (r = in.read(buf)) != -1; ) {
            if (bos.size() + r > max) throw new SnapshotException("This backup is larger than " + (max >> 20) + " MiB.");
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }
}
