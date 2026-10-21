package com.toemod;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Names, rotation and timing of local backup files. Android-free so host tests cover it. */
final class BackupPolicy {
    /** Why a backup was made; the id is part of the file name and the snapshot's "reason". */
    enum Reason {
        MANUAL("manual"),
        AUTO("auto"),
        PRERESTORE("prerestore");

        final String id;

        Reason(String id) {
            this.id = id;
        }
    }

    /** Automatic backups kept; the mod never deletes manual or pre-restore backups. */
    static final int KEEP_AUTO = 5;
    /** Shortest time between automatic backups. */
    static final long AUTO_INTERVAL_MS = 10 * 60 * 1000L;

    private static final Pattern NAME = Pattern.compile("toe3-\\d{8}-\\d{6}-(manual|auto|prerestore)\\.json\\.gz");

    private BackupPolicy() {}

    /** "toe3-YYYYMMDD-HHMMSS-reason.json.gz" in {@code zone}, so names sort by time. */
    static String fileName(long millis, TimeZone zone, Reason reason) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        f.setTimeZone(zone);
        return "toe3-" + f.format(new Date(millis)) + "-" + reason.id + ".json.gz";
    }

    /** The reason in one of the mod's backup names, or null for any other file. */
    static Reason reason(String name) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) return null;
        for (Reason r : Reason.values()) {
            if (r.id.equals(m.group(1))) return r;
        }
        return null;
    }

    /** Automatic backups to delete so that only the newest {@code keep} remain; other files are never chosen. */
    static List<String> autoToDelete(List<String> names, int keep) {
        List<String> auto = new ArrayList<>();
        for (String n : names) {
            if (reason(n) == Reason.AUTO) auto.add(n);
        }
        if (auto.size() <= keep) return Collections.emptyList();
        Collections.sort(auto, Collections.reverseOrder());
        return new ArrayList<>(auto.subList(keep, auto.size()));
    }

    /** Whether an automatic backup is due. A clock set backwards makes one due instead of blocking them for good. */
    static boolean autoDue(long now, long last) {
        return last <= 0 || now < last || now - last >= AUTO_INTERVAL_MS;
    }
}
