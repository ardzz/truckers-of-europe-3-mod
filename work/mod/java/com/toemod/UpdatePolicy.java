package com.toemod;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Platform-independent OTA trust policy: validates the signed build identity, the public
 * {@code update.json} and the GitHub release listing, decides whether a release is an update,
 * and enforces download locations, sizes and SHA-256 digests.
 *
 * <p>JSON arrives already parsed into Map / List / String / Integer / Long / Double / Boolean.
 * No Android classes are used here, so the whole policy runs on a plain JVM.
 */
final class UpdatePolicy {
    static final int SCHEMA = 1;
    /** Signed build identity: AssetManager path, and the same file as a ZIP entry of every APK. */
    static final String BUILD_ASSET = "toemod/build.json";
    static final String BUILD_ENTRY = "assets/" + BUILD_ASSET;
    static final String MANIFEST_NAME = "update.json";

    static final int MAX_BUILD_JSON = 8 * 1024;
    static final int MAX_MANIFEST = 64 * 1024;
    static final int MAX_RELEASE_JSON = 1024 * 1024;
    static final int MAX_REDIRECTS = 5;
    static final int MAX_APKS = 16;
    static final long MAX_APK_SIZE = 4L << 30;
    static final long MAX_TOTAL_SIZE = 8L << 30;

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern ABI = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern APK_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.apk");
    private static final Pattern VERSION_NAME = Pattern.compile("[\\x21-\\x7E](?:[\\x20-\\x7E]{0,98}[\\x21-\\x7E])?");

    private static final List<String> IDENTITY_KEYS = Arrays.asList("schema", "repository", "package", "abi",
            "tag", "mod_build", "mod_revision", "version_code", "version_name", "required_apks");
    private static final List<String> MANIFEST_KEYS;
    private static final List<String> APK_KEYS = Arrays.asList("name", "size", "sha256");

    static {
        List<String> keys = new ArrayList<>(IDENTITY_KEYS);
        keys.addAll(Arrays.asList("base_apk", "signing_cert_sha256", "apks"));
        MANIFEST_KEYS = Collections.unmodifiableList(keys);
    }

    private static final OutputStream DISCARD = new OutputStream() {
        @Override
        public void write(int b) {}

        @Override
        public void write(byte[] b, int off, int len) {}
    };

    private UpdatePolicy() {}

    /** Byte progress of a long copy; implementations may throw to cancel. */
    interface Progress {
        void advance(long bytes) throws IOException;
    }

    enum Verdict { UP_TO_DATE, NOT_NEWER, UPDATE }

    /** The signed {@code build.json}: which channel, package, game version and mod build an APK set is. */
    static final class Identity {
        final String repository;
        final String packageName;
        final String abi;
        final String tag;
        final long modBuild;
        final String revision;
        final long versionCode;
        final String versionName;
        /** Sorted, unique file names of the complete APK set. */
        final List<String> requiredApks;

        Identity(String repository, String packageName, String abi, String tag, long modBuild, String revision,
                long versionCode, String versionName, List<String> requiredApks) {
            this.repository = repository;
            this.packageName = packageName;
            this.abi = abi;
            this.tag = tag;
            this.modBuild = modBuild;
            this.revision = revision;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.requiredApks = requiredApks;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Identity)) return false;
            Identity i = (Identity) o;
            return repository.equals(i.repository) && packageName.equals(i.packageName) && abi.equals(i.abi)
                    && tag.equals(i.tag) && modBuild == i.modBuild && revision.equals(i.revision)
                    && versionCode == i.versionCode && versionName.equals(i.versionName)
                    && requiredApks.equals(i.requiredApks);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repository, packageName, abi, tag, modBuild, revision, versionCode, versionName,
                    requiredApks);
        }
    }

    static final class Apk {
        final String name;
        final long size;
        final String sha256;

        Apk(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** A parsed {@code update.json}. */
    static final class Release {
        final Identity identity;
        final String baseApk;
        final String signingCertSha256;
        /** Every APK of the set, base APK first. */
        final List<Apk> apks;
        final long totalSize;

        Release(Identity identity, String baseApk, String signingCertSha256, List<Apk> apks, long totalSize) {
            this.identity = identity;
            this.baseApk = baseApk;
            this.signingCertSha256 = signingCertSha256;
            this.apks = apks;
            this.totalSize = totalSize;
        }
    }

    static final class Asset {
        final String name;
        final URI url;
        final long size;

        Asset(String name, URI url, long size) {
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }

    static final class Download {
        final Apk apk;
        final URI url;

        Download(Apk apk, URI url) {
            this.apk = apk;
            this.url = url;
        }
    }

    /** The latest stable release of the channel as listed by the GitHub API. */
    static final class GithubRelease {
        final String repository;
        final String tag;
        private final Map<String, Map<?, ?>> uploaded;

        GithubRelease(String repository, String tag, Map<String, Map<?, ?>> uploaded) {
            this.repository = repository;
            this.tag = tag;
            this.uploaded = uploaded;
        }

        boolean has(String name) {
            return uploaded.containsKey(name);
        }

        /** Size and validated canonical download URL of an uploaded asset. */
        Asset asset(String name) throws UpdateException {
            Map<?, ?> a = uploaded.get(name);
            if (a == null) throw new UpdateException("release " + tag + " has no uploaded asset " + name);
            long size = integer(a, "size", 0, MAX_TOTAL_SIZE, "release asset " + name);
            Object url = a.get("browser_download_url");
            if (!(url instanceof String)) throw new UpdateException("release asset " + name + " has no download URL");
            return new Asset(name, assetUrl((String) url, repository, tag, name), size);
        }
    }

    // ------------------------------------------------------------------ metadata

    /**
     * Parses a {@code build.json} (exactly the identity keys). {@code published} identities come
     * from a release and must carry a CI build number; an installed local build may be 0.
     */
    static Identity identity(Map<?, ?> json, boolean published) throws UpdateException {
        requireKeys(json, IDENTITY_KEYS, "build.json");
        return readIdentity(json, published, "build.json");
    }

    /** Parses {@code update.json}: a published identity plus the complete, hashed APK set. */
    static Release release(Map<?, ?> json) throws UpdateException {
        String what = MANIFEST_NAME;
        requireKeys(json, MANIFEST_KEYS, what);
        Identity id = readIdentity(json, true, what);
        String base = string(json, "base_apk", APK_NAME, what);
        String cert = string(json, "signing_cert_sha256", SHA256, what);

        Object list = json.get("apks");
        if (!(list instanceof List) || ((List<?>) list).isEmpty() || ((List<?>) list).size() > MAX_APKS) {
            throw new UpdateException(what + ": apks must list 1-" + MAX_APKS + " files");
        }
        List<Apk> apks = new ArrayList<>();
        Set<String> folded = new HashSet<>();
        Set<String> names = new HashSet<>();
        long total = 0;
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) throw new UpdateException(what + ": apks entries must be objects");
            Map<?, ?> m = (Map<?, ?>) o;
            requireKeys(m, APK_KEYS, what + " apks entry");
            String name = string(m, "name", APK_NAME, what + " apks entry");
            long size = integer(m, "size", 1, MAX_APK_SIZE, what + " " + name);
            String sha = string(m, "sha256", SHA256, what + " " + name);
            if (!folded.add(name.toLowerCase(Locale.ROOT))) throw new UpdateException(what + ": duplicate APK " + name);
            names.add(name);
            total += size;
            Apk apk = new Apk(name, size, sha);
            if (name.equals(base)) apks.add(0, apk);
            else apks.add(apk);
        }
        if (!apks.get(0).name.equals(base)) throw new UpdateException(what + ": base_apk " + base + " is not in apks");
        if (total > MAX_TOTAL_SIZE) throw new UpdateException(what + ": APK set is larger than " + MAX_TOTAL_SIZE + " bytes");
        if (!names.equals(new HashSet<>(id.requiredApks))) {
            List<String> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            throw new UpdateException(what + ": apks " + sorted + " differ from the signed required_apks " + id.requiredApks);
        }
        return new Release(id, base, cert, Collections.unmodifiableList(apks), total);
    }

    /** Parses {@code GET /repos/OWNER/REPO/releases/latest}; only a published stable release is usable. */
    static GithubRelease githubRelease(Map<?, ?> json, String repository) throws UpdateException {
        String what = "GitHub release";
        if (!Boolean.FALSE.equals(json.get("draft")) || !Boolean.FALSE.equals(json.get("prerelease"))) {
            throw new UpdateException("the latest GitHub release is not a published stable release");
        }
        String tag = string(json, "tag_name", TAG, what);
        Object list = json.get("assets");
        if (!(list instanceof List)) throw new UpdateException(what + " " + tag + " has no asset list");
        Set<String> seen = new HashSet<>();
        Map<String, Map<?, ?>> uploaded = new HashMap<>();
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map) || !(((Map<?, ?>) o).get("name") instanceof String)) {
                throw new UpdateException(what + " " + tag + " has a malformed asset entry");
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String name = (String) m.get("name");
            if (!seen.add(name)) throw new UpdateException(what + " " + tag + " lists asset " + name + " twice");
            if ("uploaded".equals(m.get("state"))) uploaded.put(name, m);
        }
        return new GithubRelease(repository, tag, uploaded);
    }

    /** Pairs every APK of {@code r} with its release asset; sizes must agree with update.json. */
    static List<Download> plan(Release r, GithubRelease release) throws UpdateException {
        if (!r.identity.tag.equals(release.tag)) {
            throw new UpdateException(MANIFEST_NAME + " describes " + r.identity.tag + " but was published in " + release.tag);
        }
        List<Download> out = new ArrayList<>(r.apks.size());
        for (Apk apk : r.apks) {
            Asset a = release.asset(apk.name);
            if (a.size != apk.size) {
                throw new UpdateException(apk.name + ": release asset has " + a.size + " bytes, " + MANIFEST_NAME + " says " + apk.size);
            }
            out.add(new Download(apk, a.url));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Whether {@code r} may be offered to the {@code installed} build signed with {@code installedCert}.
     * The same tag is up to date; a build number that is not higher is never re-offered; a release
     * for another channel, package, ABI or certificate, or one that lowers the game version, is refused.
     */
    static Verdict decide(Identity installed, String installedCert, Release r) throws UpdateException {
        Identity n = r.identity;
        if (!n.repository.equalsIgnoreCase(installed.repository)) {
            throw new UpdateException("release belongs to " + n.repository + ", this build follows " + installed.repository);
        }
        if (!n.packageName.equals(installed.packageName)) {
            throw new UpdateException("release is for package " + n.packageName + ", not " + installed.packageName);
        }
        if (!n.abi.equals(installed.abi)) throw new UpdateException("release is for " + n.abi + ", this build is " + installed.abi);
        if (n.tag.equals(installed.tag)) return Verdict.UP_TO_DATE;
        if (n.modBuild <= installed.modBuild) return Verdict.NOT_NEWER;
        if (n.versionCode < installed.versionCode) {
            throw new UpdateException("release " + n.tag + " would downgrade the game from version code "
                    + installed.versionCode + " to " + n.versionCode);
        }
        if (!r.signingCertSha256.equals(installedCert)) {
            throw new UpdateException("release " + n.tag + " is signed with a different certificate than this installation");
        }
        return Verdict.UPDATE;
    }

    /** Every APK of the set carries the signed identity of the release it belongs to. */
    static void requireEmbedded(Identity embedded, Release r, String apk) throws UpdateException {
        if (!embedded.equals(r.identity)) {
            throw new UpdateException(apk + " belongs to " + embedded.tag + " (build " + embedded.modBuild
                    + "), not to " + r.identity.tag + " (build " + r.identity.modBuild + ")");
        }
    }

    private static Identity readIdentity(Map<?, ?> json, boolean published, String what) throws UpdateException {
        integer(json, "schema", SCHEMA, SCHEMA, what);
        String repository = string(json, "repository", REPOSITORY, what);
        String repoName = repository.substring(repository.indexOf('/') + 1);
        if (repoName.equals(".") || repoName.equals("..")) throw new UpdateException(what + ": invalid repository");
        return new Identity(
                repository,
                string(json, "package", PACKAGE, what),
                string(json, "abi", ABI, what),
                string(json, "tag", TAG, what),
                integer(json, "mod_build", published ? 1 : 0, Integer.MAX_VALUE, what),
                string(json, "mod_revision", REVISION, what),
                integer(json, "version_code", 1, Integer.MAX_VALUE, what),
                string(json, "version_name", VERSION_NAME, what),
                apkList(json, "required_apks", what));
    }

    private static List<String> apkList(Map<?, ?> json, String key, String what) throws UpdateException {
        Object v = json.get(key);
        if (!(v instanceof List) || ((List<?>) v).isEmpty() || ((List<?>) v).size() > MAX_APKS) {
            throw new UpdateException(what + ": " + key + " must list 1-" + MAX_APKS + " APK names");
        }
        List<String> out = new ArrayList<>();
        String previous = null;
        for (Object o : (List<?>) v) {
            if (!(o instanceof String) || !APK_NAME.matcher((String) o).matches()) {
                throw new UpdateException(what + ": invalid " + key + " entry " + shorten(o));
            }
            String name = (String) o;
            if (previous != null && previous.compareTo(name) >= 0) {
                throw new UpdateException(what + ": " + key + " must be sorted and unique");
            }
            out.add(name);
            previous = name;
        }
        return Collections.unmodifiableList(out);
    }

    private static void requireKeys(Map<?, ?> json, List<String> expected, String what) throws UpdateException {
        List<String> missing = new ArrayList<>();
        for (String k : expected) if (!json.containsKey(k)) missing.add(k);
        List<String> unexpected = new ArrayList<>();
        for (Object k : json.keySet()) if (!expected.contains(k)) unexpected.add(shorten(k));
        if (missing.isEmpty() && unexpected.isEmpty()) return;
        throw new UpdateException(what + ": missing fields " + missing + ", unexpected fields " + unexpected);
    }

    private static String string(Map<?, ?> json, String key, Pattern format, String what) throws UpdateException {
        Object v = json.get(key);
        if (v instanceof String && format.matcher((String) v).matches()) return (String) v;
        throw new UpdateException(what + ": invalid " + key + " " + shorten(v));
    }

    private static long integer(Map<?, ?> json, String key, long min, long max, String what) throws UpdateException {
        Object v = json.get(key);
        if (v instanceof Integer || v instanceof Long) {
            long n = ((Number) v).longValue();
            if (n >= min && n <= max) return n;
        }
        throw new UpdateException(what + ": invalid " + key + " " + shorten(v));
    }

    private static String shorten(Object v) {
        String s = v instanceof String ? "\"" + v + "\"" : String.valueOf(v);
        return s.length() <= 48 ? s : s.substring(0, 45) + "...";
    }

    // ------------------------------------------------------------------ locations

    static URI latestReleaseUrl(String repository) {
        return URI.create("https://api.github.com/repos/" + repository + "/releases/latest");
    }

    static URI releasePage(String repository, String tag) {
        return URI.create("https://github.com/" + repository + "/releases/tag/" + tag);
    }

    /**
     * An asset's {@code browser_download_url} must be exactly
     * {@code https://github.com/OWNER/REPO/releases/download/TAG/NAME} (owner/repo case-insensitive).
     */
    static URI assetUrl(String raw, String repository, String tag, String name) throws UpdateException {
        URI u;
        try {
            u = new URI(raw);
        } catch (URISyntaxException e) {
            throw new UpdateException("malformed download URL for " + name);
        }
        String prefix = "/" + repository + "/releases/download/";
        String leaf = tag + "/" + name;
        String path = u.getRawPath();
        boolean canonical = "https".equals(u.getScheme()) && "github.com".equals(u.getHost()) && u.getPort() == -1
                && u.getRawUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null
                && path != null && path.length() == prefix.length() + leaf.length()
                && path.regionMatches(true, 0, prefix, 0, prefix.length())
                && path.startsWith(leaf, prefix.length());
        if (!canonical) throw new UpdateException("unexpected download URL for " + name);
        return transport(u);
    }

    /** Every connection, including each redirect hop: HTTPS on the default port to GitHub hosts only. */
    static URI transport(URI u) throws UpdateException {
        String host = u.getHost();
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String cdn = ".githubusercontent.com";
        boolean allowed = h.equals("github.com") || h.equals("api.github.com")
                || (h.endsWith(cdn) && h.length() > cdn.length());
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null || !allowed
                || (u.getPort() != -1 && u.getPort() != 443)) {
            throw new UpdateException("refusing to connect to " + (host == null ? "a malformed location" : host));
        }
        return u;
    }

    /** Resolves a redirect {@code Location} against the current URL and applies {@link #transport}. */
    static URI redirect(URI from, String location) throws UpdateException {
        if (location == null || location.isEmpty()) throw new UpdateException("redirect without a location from " + from.getHost());
        URI next;
        try {
            next = from.resolve(new URI(location));
        } catch (URISyntaxException e) {
            throw new UpdateException("malformed redirect from " + from.getHost());
        }
        return transport(next);
    }

    // ------------------------------------------------------------------ bytes

    static byte[] readBounded(InputStream in, int max, String what) throws IOException, UpdateException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > max) throw new UpdateException(what + " is larger than " + max + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Copies exactly {@code size} bytes and returns their SHA-256; a shorter or longer stream is
     * rejected, so nothing beyond the announced size is ever accepted.
     */
    static String copyExact(InputStream in, OutputStream out, long size, Progress progress, String what)
            throws IOException, UpdateException {
        MessageDigest md = sha256();
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        while (done < size) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, size - done));
            if (n < 0) throw new UpdateException(what + " is truncated (" + done + " of " + size + " bytes)");
            md.update(buf, 0, n);
            out.write(buf, 0, n);
            done += n;
            if (progress != null) progress.advance(n);
        }
        if (in.read() != -1) throw new UpdateException(what + " is larger than the expected " + size + " bytes");
        return hex(md.digest());
    }

    /** Re-checks a cached file against its expected size and SHA-256. */
    static void verifyFile(File f, long size, String sha256, Progress progress, String what)
            throws IOException, UpdateException {
        if (!f.isFile()) throw new UpdateException(what + " is missing");
        if (f.length() != size) throw new UpdateException(what + " has " + f.length() + " bytes, expected " + size);
        String actual;
        try (InputStream in = new FileInputStream(f)) {
            actual = copyExact(in, DISCARD, size, progress, what);
        }
        if (!actual.equals(sha256)) throw new UpdateException(what + " fails its SHA-256 check");
    }

    /** Reads one bounded entry (e.g. {@link #BUILD_ENTRY}) from an APK. */
    static byte[] readZipEntry(File zip, String entry, int max, String what) throws IOException, UpdateException {
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry e = z.getEntry(entry);
            if (e == null || e.isDirectory()) throw new UpdateException(what + " has no " + entry);
            try (InputStream in = z.getInputStream(e)) {
                return readBounded(in, max, what + " " + entry);
            }
        }
    }

    static String sha256Hex(byte[] data) {
        return hex(sha256().digest(data));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return s.toString();
    }
}
