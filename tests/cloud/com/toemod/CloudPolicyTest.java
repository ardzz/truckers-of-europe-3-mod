package com.toemod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPOutputStream;

/**
 * Host checks of the cloud-save rules that need no Android: the exact toe3-prefs-snapshot v1 bytes,
 * lossless round trips of every SharedPreferences type (raw URL-encoded keys, non-ASCII and lone
 * surrogates, NaN and -0 floats, longs beyond 2^53), refusal of foreign, malformed, newer-format and
 * newer-build snapshots, gzip and size limits, backup names, automatic-backup rotation and its interval.
 * Run by ci/test_cloud_client.py.
 */
public final class CloudPolicyTest {
    private static final String PKG = "com.WandaSoftware.TruckersofEurope3";
    private static final long BUILD = 193;

    private static int passed;
    private static int failed;

    interface Body {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        encodeChecks();
        roundTripChecks();
        decodeChecks();
        gzipChecks();
        nameChecks();
        rotationChecks();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0 || passed == 0) System.exit(1);
    }

    // ------------------------------------------------------------------ encode

    private static SnapshotFormat.Meta meta(long build) {
        return new SnapshotFormat.Meta(PKG, "0.7.64", build, "v0.2.0", "2026-10-21T05:00:00Z", "manual", "HUAWEI BTK-W09");
    }

    private static Map<String, Object> everyType() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ss", new HashSet<>(Arrays.asList("y", "x")));
        p.put("l", 9007199254740993L);
        p.put("i", -7);
        p.put("f", 1.5f);
        p.put("e", Collections.emptySet());
        p.put("b", true);
        p.put("I2%20Language", "Espa%C3%B1ol");
        return p;
    }

    private static void encodeChecks() {
        check("every type has its exact bytes, keys sorted", () -> eq(
                "{\"format\":\"toe3-prefs-snapshot\",\"version\":1,\"package\":\"com.WandaSoftware.TruckersofEurope3\","
                        + "\"game_version\":\"0.7.64\",\"game_build\":193,\"mod_version\":\"v0.2.0\","
                        + "\"created_at\":\"2026-10-21T05:00:00Z\",\"reason\":\"manual\",\"device\":\"HUAWEI BTK-W09\","
                        + "\"prefs\":{\"I2%20Language\":{\"t\":\"s\",\"v\":\"Espa%C3%B1ol\"},\"b\":{\"t\":\"b\",\"v\":true},"
                        + "\"e\":{\"t\":\"ss\",\"v\":[]},\"f\":{\"t\":\"f\",\"v\":\"1.5\"},\"i\":{\"t\":\"i\",\"v\":-7},"
                        + "\"l\":{\"t\":\"l\",\"v\":\"9007199254740993\"},\"ss\":{\"t\":\"ss\",\"v\":[\"x\",\"y\"]}}}",
                SnapshotFormat.encode(meta(BUILD), everyType())));
        check("quotes, backslashes, controls and non-ASCII become escapes", () -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("q\"\\", "\u00e9\n\u007f");
            String json = SnapshotFormat.encode(meta(BUILD), p);
            truth(json.endsWith("\"prefs\":{\"q\\\"\\\\\":{\"t\":\"s\",\"v\":\"\\u00e9\\u000a\\u007f\"}}}"));
        });
        check("the output is pure ASCII", () -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("k\u4e16", "\ud83d\ude9a \ud83d \u2028");
            for (char c : SnapshotFormat.encode(meta(BUILD), p).toCharArray()) truth(c >= 0x20 && c < 0x7f);
        });
        check("insertion order does not change the bytes", () -> {
            Map<String, Object> reversed = new LinkedHashMap<>();
            List<Map.Entry<String, Object>> entries = new java.util.ArrayList<>(everyType().entrySet());
            Collections.reverse(entries);
            for (Map.Entry<String, Object> e : entries) reversed.put(e.getKey(), e.getValue());
            eq(SnapshotFormat.encode(meta(BUILD), everyType()), SnapshotFormat.encode(meta(BUILD), reversed));
        });
        check("null meta text is written as empty", () -> truth(SnapshotFormat.encode(
                new SnapshotFormat.Meta(PKG, null, BUILD, null, null, null, null), everyType())
                .contains("\"mod_version\":\"\",\"created_at\":\"\",\"reason\":\"\",\"device\":\"\"")));
        refusesToEncode("no values", new LinkedHashMap<String, Object>());
        refusesToEncode("a double", one("d", 1.0d));
        refusesToEncode("a null value", one("n", null));
        refusesToEncode("a set holding a number", one("s", new HashSet<Object>(Arrays.<Object>asList("a", 1))));
        refusesToEncode("JSON larger than 16 MiB", one("big", repeat('a', SnapshotFormat.MAX_JSON_BYTES)));
        check("ISO-8601 UTC timestamps", () -> {
            eq("1970-01-01T00:00:00Z", SnapshotFormat.isoUtc(0));
            eq("2026-10-21T05:00:00Z", SnapshotFormat.isoUtc(1792558800000L));
        });
    }

    private static void refusesToEncode(String name, Map<String, Object> prefs) {
        check("encode refuses " + name, () -> {
            try {
                SnapshotFormat.encode(meta(BUILD), prefs);
            } catch (IllegalArgumentException expected) {
                return;
            }
            throw new AssertionError("encoded");
        });
    }

    // ------------------------------------------------------------------ round trip

    private static SnapshotFormat.Snapshot roundTrip(Map<String, Object> prefs) throws SnapshotException {
        String json = SnapshotFormat.gunzip(SnapshotFormat.gzip(SnapshotFormat.encode(meta(BUILD), prefs)));
        return SnapshotFormat.decode(MiniJson.object(json), PKG, BUILD);
    }

    private static void roundTripChecks() {
        check("every type comes back with its type and value", () -> eq(everyType(), roundTrip(everyType()).prefs));
        check("metadata comes back", () -> {
            SnapshotFormat.Meta m = roundTrip(everyType()).meta;
            eq(PKG, m.pkg);
            eq("0.7.64", m.gameVersion);
            eq(BUILD, m.gameBuild);
            eq("v0.2.0", m.modVersion);
            eq("2026-10-21T05:00:00Z", m.createdAt);
            eq("manual", m.reason);
            eq("HUAWEI BTK-W09", m.device);
        });
        check("floats keep NaN, infinities, -0 and float precision", () -> {
            Map<String, Object> p = new LinkedHashMap<>();
            float[] values = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f, 0.1f,
                    Float.MIN_VALUE, Float.MAX_VALUE, 16777217f, 3.4028235E38f};
            for (int i = 0; i < values.length; i++) p.put("f" + i, values[i]);
            eq(p, roundTrip(p).prefs);
        });
        check("integers and longs keep their extremes", () -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("imin", Integer.MIN_VALUE);
            p.put("imax", Integer.MAX_VALUE);
            p.put("lmin", Long.MIN_VALUE);
            p.put("lmax", Long.MAX_VALUE);
            eq(p, roundTrip(p).prefs);
        });
        check("non-ASCII text, lone surrogates and controls come back unchanged", () -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("k\u4e16\"\\", "\ud83d\ude9a \ud83d \u2028 \u0000\t\u007f Espa\u00f1ol");
            eq(p, roundTrip(p).prefs);
        });
    }

    // ------------------------------------------------------------------ decode

    private static Map<String, Object> validRoot() {
        return MiniJson.object(SnapshotFormat.encode(meta(BUILD), everyType()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> prefsOf(Map<String, Object> root) {
        return (Map<String, Object>) root.get("prefs");
    }

    private static void decodeChecks() {
        check("decode refuses text that is not one JSON object", () ->
                expectSnapshotException(() -> SnapshotFormat.decode(null, PKG, BUILD), "not a TOE3 Stormline backup"));
        refuses("another format", r -> r.put("format", "toe3-other"), "not a TOE3 Stormline backup");
        refuses("a missing format", r -> r.remove("format"), "not a TOE3 Stormline backup");
        refuses("version 0", r -> r.put("version", 0L), "not a TOE3 Stormline backup");
        refuses("a textual version", r -> r.put("version", "1"), "not a TOE3 Stormline backup");
        refuses("a newer format version", r -> r.put("version", 2L), "newer version of the mod");
        refuses("another package", r -> r.put("package", "com.example.game"), "different game");
        refuses("a missing game build", r -> r.remove("game_build"), "which game build");
        refuses("a negative game build", r -> r.put("game_build", -1L), "which game build");
        refuses("a textual game build", r -> r.put("game_build", "193"), "which game build");
        refuses("a newer game build", r -> r.put("game_build", BUILD + 1), "Update the game first");
        refuses("no prefs", r -> r.remove("prefs"), "no saved game");
        refuses("empty prefs", r -> prefsOf(r).clear(), "no saved game");
        refuses("prefs as a list", r -> r.put("prefs", Collections.emptyList()), "no saved game");
        refuses("an unknown type", r -> prefsOf(r).put("x", typed("d", 1.0d)), "\"x\"");
        refuses("an entry that is not an object", r -> prefsOf(r).put("x", "plain"), "\"x\"");
        refuses("an int above 2^31-1", r -> prefsOf(r).put("x", typed("i", 2147483648L)), "\"x\"");
        refuses("an int written as text", r -> prefsOf(r).put("x", typed("i", "5")), "\"x\"");
        refuses("a fractional int", r -> prefsOf(r).put("x", typed("i", 1.5d)), "\"x\"");
        refuses("a float that is not a number", r -> prefsOf(r).put("x", typed("f", "abc")), "\"x\"");
        refuses("a float written as a number", r -> prefsOf(r).put("x", typed("f", 1.5d)), "\"x\"");
        refuses("a long with a fraction", r -> prefsOf(r).put("x", typed("l", "1.5")), "\"x\"");
        refuses("a boolean written as text", r -> prefsOf(r).put("x", typed("b", "true")), "\"x\"");
        refuses("a string written as a number", r -> prefsOf(r).put("x", typed("s", 5L)), "\"x\"");
        refuses("a set holding a number", r -> prefsOf(r).put("x", typed("ss", Arrays.<Object>asList("a", 1L))), "\"x\"");
        refuses("a set holding null", r -> prefsOf(r).put("x", typed("ss", Arrays.<Object>asList("a", null))), "\"x\"");
        check("an older game build is accepted and flagged", () -> {
            Map<String, Object> r = validRoot();
            r.put("game_build", BUILD - 1);
            SnapshotFormat.Snapshot s = SnapshotFormat.decode(r, PKG, BUILD);
            truth(s.olderThan(BUILD));
            eq(everyType(), s.prefs);
        });
        check("the same game build is not flagged", () -> truth(!SnapshotFormat.decode(validRoot(), PKG, BUILD).olderThan(BUILD)));
        check("missing optional metadata reads as empty text", () -> {
            Map<String, Object> r = validRoot();
            r.remove("device");
            r.put("mod_version", 7L);
            SnapshotFormat.Meta m = SnapshotFormat.decode(r, PKG, BUILD).meta;
            eq("", m.device);
            eq("", m.modVersion);
        });
        check("int-sized org.json numbers are read like longs", () -> {
            Map<String, Object> r = validRoot();
            r.put("version", 1);
            r.put("game_build", 193);
            prefsOf(r).put("x", typed("i", 42));
            eq(42, SnapshotFormat.decode(r, PKG, BUILD).prefs.get("x"));
        });
    }

    interface Mutation {
        void apply(Map<String, Object> root);
    }

    private static void refuses(String name, Mutation m, String reason) {
        check("decode refuses " + name, () -> {
            Map<String, Object> r = validRoot();
            m.apply(r);
            try {
                SnapshotFormat.decode(r, PKG, BUILD);
            } catch (SnapshotException e) {
                if (!e.getMessage().contains(reason)) throw new AssertionError("message: " + e.getMessage());
                return;
            }
            throw new AssertionError("accepted");
        });
    }

    private static Map<String, Object> typed(String t, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", t);
        m.put("v", v);
        return m;
    }

    // ------------------------------------------------------------------ bytes

    private static void gzipChecks() {
        check("gzip round trip", () -> eq("{\"a\":1}", SnapshotFormat.gunzip(SnapshotFormat.gzip("{\"a\":1}"))));
        notABackup("plain JSON", "{\"format\":\"toe3-prefs-snapshot\"}".getBytes(StandardCharsets.UTF_8));
        notABackup("an empty file", new byte[0]);
        check("decode refuses truncated gzip", () -> {
            byte[] gz = SnapshotFormat.gzip(SnapshotFormat.encode(meta(BUILD), everyType()));
            expectSnapshotException(() -> SnapshotFormat.gunzip(Arrays.copyOf(gz, gz.length - 9)), "not a TOE3 Stormline backup");
        });
        check("JSON of exactly 16 MiB inflates", () -> eq(SnapshotFormat.MAX_JSON_BYTES, SnapshotFormat.gunzip(zeros(SnapshotFormat.MAX_JSON_BYTES)).length()));
        check("JSON beyond 16 MiB is refused while inflating", () ->
                expectSnapshotException(() -> SnapshotFormat.gunzip(zeros(SnapshotFormat.MAX_JSON_BYTES + 1)), "larger than 16 MiB"));
        check("readAll accepts exactly its limit", () -> eq(10, SnapshotFormat.readAll(new ByteArrayInputStream(new byte[10]), 10).length));
        check("readAll refuses one byte more", () ->
                expectSnapshotException(() -> SnapshotFormat.readAll(new ByteArrayInputStream(new byte[11]), 10), "larger than"));
    }

    private static void notABackup(String name, byte[] data) {
        check("decode refuses " + name, () -> expectSnapshotException(() -> SnapshotFormat.gunzip(data), "not a TOE3 Stormline backup"));
    }

    private static byte[] zeros(int n) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            byte[] chunk = new byte[1 << 20];
            Arrays.fill(chunk, (byte) '0');
            for (int left = n; left > 0; left -= chunk.length) gz.write(chunk, 0, Math.min(left, chunk.length));
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ names and rotation

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static void nameChecks() {
        check("names carry the local time and reason", () -> {
            eq("toe3-20261021-050000-manual.json.gz", BackupPolicy.fileName(1792558800000L, UTC, BackupPolicy.Reason.MANUAL));
            eq("toe3-20261021-120000-auto.json.gz",
                    BackupPolicy.fileName(1792558800000L, TimeZone.getTimeZone("Asia/Jakarta"), BackupPolicy.Reason.AUTO));
            eq("toe3-20261021-050000-prerestore.json.gz", BackupPolicy.fileName(1792558800000L, UTC, BackupPolicy.Reason.PRERESTORE));
        });
        check("every reason parses back from its name", () -> {
            for (BackupPolicy.Reason r : BackupPolicy.Reason.values()) eq(r, BackupPolicy.reason(BackupPolicy.fileName(0, UTC, r)));
        });
        for (String foreign : new String[] {"toe3-20261021-050000-manual.json", "toe3-20261021-050000-manual (1).json.gz",
                "toe3-2026102-050000-auto.json.gz", "toe3-20261021-050000-backup.json.gz", "TOE3-20261021-050000-auto.json.gz",
                "save.json.gz", "toe3-20261021-050000-auto.json.gz.bak"}) {
            check("not a backup name: " + foreign, () -> eq(null, BackupPolicy.reason(foreign)));
        }
    }

    private static void rotationChecks() {
        check("only the oldest automatic backups beyond five are deleted", () -> {
            List<String> names = Arrays.asList(
                    "toe3-20261021-050000-auto.json.gz", "toe3-20261019-050000-auto.json.gz",
                    "toe3-20261020-050000-manual.json.gz", "toe3-20261025-050000-auto.json.gz",
                    "toe3-20261018-050000-prerestore.json.gz", "toe3-20261024-050000-auto.json.gz",
                    "toe3-20261020-050000-auto.json.gz", "notes.txt", "toe3-20261023-050000-auto.json.gz",
                    "toe3-20261022-050000-auto.json.gz");
            eq(Arrays.asList("toe3-20261020-050000-auto.json.gz", "toe3-20261019-050000-auto.json.gz"),
                    BackupPolicy.autoToDelete(names, BackupPolicy.KEEP_AUTO));
        });
        check("five automatic backups are all kept", () -> eq(Collections.emptyList(), BackupPolicy.autoToDelete(Arrays.asList(
                "toe3-20261021-050000-auto.json.gz", "toe3-20261022-050000-auto.json.gz", "toe3-20261023-050000-auto.json.gz",
                "toe3-20261024-050000-auto.json.gz", "toe3-20261025-050000-auto.json.gz",
                "toe3-20261001-050000-manual.json.gz"), BackupPolicy.KEEP_AUTO)));
        long t = 1792558800000L;
        check("the first automatic backup is due", () -> truth(BackupPolicy.autoDue(t, 0)));
        check("an automatic backup is not due within 10 minutes", () -> truth(!BackupPolicy.autoDue(t + BackupPolicy.AUTO_INTERVAL_MS - 1, t)));
        check("an automatic backup is due after exactly 10 minutes", () -> truth(BackupPolicy.autoDue(t + BackupPolicy.AUTO_INTERVAL_MS, t)));
        check("a clock set backwards makes a backup due", () -> truth(BackupPolicy.autoDue(t - 1, t)));
    }

    // ------------------------------------------------------------------ harness

    private static Map<String, Object> one(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private static void expectSnapshotException(Body body, String reason) throws Exception {
        try {
            body.run();
        } catch (SnapshotException e) {
            if (!e.getMessage().contains(reason)) throw new AssertionError("message: " + e.getMessage());
            return;
        }
        throw new AssertionError("no SnapshotException");
    }

    private static void check(String name, Body body) {
        try {
            body.run();
            passed++;
        } catch (Exception | AssertionError e) {
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
