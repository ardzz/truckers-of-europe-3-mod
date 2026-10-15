package com.toemod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Host-side checks of the OTA trust boundaries in {@link UpdatePolicy}: malformed or hostile
 * metadata, rollback, download locations and redirects, checksums, cache corruption, the
 * signed per-APK identity, the move from legacy to semantic release tags and the bounded
 * plain-text change list taken from release notes.
 * Run by ci/test_ota_client.py; argument: a scratch directory.
 */
public final class UpdatePolicyTest {
    private static final String REPO = "ardzz/truckers-of-europe-3-mod";
    private static final String PKG = "com.WandaSoftware.TruckersofEurope3";
    private static final String REV = "0123456789abcdef0123456789abcdef01234567";
    private static final String CERT = repeat("ab", 32);
    private static final String BASE = "base.apk";
    private static final String ASSETS = "split_assets.apk";
    private static final String CONFIG = "split_config.arm64_v8a.apk";
    private static final List<String> APKS = Arrays.asList(BASE, ASSETS, CONFIG); // sorted
    private static final Map<String, Integer> SIZES = new LinkedHashMap<>();

    static {
        SIZES.put(BASE, 1000);
        SIZES.put(ASSETS, 5000);
        SIZES.put(CONFIG, 300);
    }

    private static int passed;
    private static int failed;

    interface Body {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        File scratch = new File(args[0]);
        identityChecks();
        manifestChecks();
        decisionChecks();
        githubReleaseChecks();
        semanticReleaseChecks();
        releaseNotesChecks();
        transportChecks();
        byteChecks(scratch);
        embeddedIdentityChecks(scratch);
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0 || passed == 0) System.exit(1);
    }

    // ------------------------------------------------------------------ build.json

    private static void identityChecks() {
        check("installed local build 0 is a valid identity",
                () -> eq(0L, UpdatePolicy.identity(identity("local-0123456", 0, 193), false).modBuild));
        check("required_apks is kept in signed order",
                () -> eq(APKS, UpdatePolicy.identity(identity(tag(7), 7, 193), true).requiredApks));
        rejects("published identity without a CI build number", () -> UpdatePolicy.identity(identity(tag(1), 0, 193), true));
        rejects("missing field", () -> UpdatePolicy.identity(without(identity(tag(7), 7, 193), "required_apks"), true));
        rejects("unexpected field", () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "channel", "beta"), true));
        rejects("mod_build as a string", () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "mod_build", "7"), true));
        rejects("version_code as a float", () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "version_code", 193.0), true));
        rejects("version_code beyond int", () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "version_code", 1L << 40), true));
        rejects("unknown schema", () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "schema", 2), true));
        rejects("uppercase revision", () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "mod_revision", REV.toUpperCase()), true));
        rejects("tag with a path separator", () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "tag", "v7/../../x"), true));
        rejects("repository traversal", () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "repository", "ardzz/.."), true));
        rejects("required_apks unsorted",
                () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "required_apks", list(CONFIG, BASE, ASSETS)), true));
        rejects("required_apks duplicate",
                () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "required_apks", list(BASE, BASE)), true));
        rejects("required_apks with a path",
                () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "required_apks", list("../base.apk")), true));
        rejects("required_apks with a non-APK file",
                () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "required_apks", list(BASE, "payload.so")), true));
        rejects("required_apks empty",
                () -> UpdatePolicy.identity(with(identity(tag(7), 7, 193), "required_apks", list()), true));
    }

    // ------------------------------------------------------------------ update.json

    private static void manifestChecks() {
        check("APK set is complete, base first, sizes totalled", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            List<Object> reversed = new ArrayList<>((List<?>) m.get("apks"));
            java.util.Collections.reverse(reversed);
            UpdatePolicy.Release r = UpdatePolicy.release(with(m, "apks", reversed));
            eq(BASE, r.apks.get(0).name);
            eq(3, r.apks.size());
            eq(6300L, r.totalSize);
        });
        rejects("manifest omitting an APK of the signed set", () -> UpdatePolicy.release(withoutApk(manifest(tag(8), 8, 193), ASSETS)));
        rejects("manifest adding an APK outside the signed set", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            List<Object> apks = new ArrayList<>((List<?>) m.get("apks"));
            apks.add(apk("split_extra.apk", 10));
            UpdatePolicy.release(with(m, "apks", apks));
        });
        rejects("base_apk not in the set", () -> UpdatePolicy.release(with(manifest(tag(8), 8, 193), "base_apk", "other.apk")));
        rejects("names differing only in case", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            List<Object> apks = new ArrayList<>((List<?>) m.get("apks"));
            apks.add(apk("BASE.apk", 10));
            UpdatePolicy.release(with(m, "apks", apks));
        });
        rejects("zero-byte APK", () -> UpdatePolicy.release(withApkField(manifest(tag(8), 8, 193), CONFIG, "size", 0)));
        rejects("negative size", () -> UpdatePolicy.release(withApkField(manifest(tag(8), 8, 193), CONFIG, "size", -5)));
        rejects("size beyond the limit",
                () -> UpdatePolicy.release(withApkField(manifest(tag(8), 8, 193), CONFIG, "size", UpdatePolicy.MAX_APK_SIZE + 1)));
        rejects("uppercase sha256",
                () -> UpdatePolicy.release(withApkField(manifest(tag(8), 8, 193), CONFIG, "sha256", sha(CONFIG).toUpperCase())));
        rejects("short sha256",
                () -> UpdatePolicy.release(withApkField(manifest(tag(8), 8, 193), CONFIG, "sha256", sha(CONFIG).substring(1))));
        rejects("APK entry carrying its own URL",
                () -> UpdatePolicy.release(withApkField(manifest(tag(8), 8, 193), BASE, "url", "https://evil.example/base.apk")));
        rejects("malformed signing certificate", () -> UpdatePolicy.release(with(manifest(tag(8), 8, 193), "signing_cert_sha256", "ab")));
        rejects("apks not a list", () -> UpdatePolicy.release(with(manifest(tag(8), 8, 193), "apks", "base.apk")));
        rejects("manifest missing an identity field", () -> UpdatePolicy.release(without(manifest(tag(8), 8, 193), "mod_revision")));
    }

    // ------------------------------------------------------------------ offer decision

    private static void decisionChecks() {
        check("same tag is up to date", () -> eq(UpdatePolicy.Verdict.UP_TO_DATE, decide(installed(7), manifest(tag(7), 7, 193))));
        check("older build is never offered", () -> eq(UpdatePolicy.Verdict.NOT_NEWER, decide(installed(7), manifest(tag(6), 6, 193))));
        check("same build under another tag is not re-offered",
                () -> eq(UpdatePolicy.Verdict.NOT_NEWER, decide(installed(7), manifest("toe3-rebuilt-7", 7, 193))));
        check("newer build is offered", () -> eq(UpdatePolicy.Verdict.UPDATE, decide(installed(7), manifest(tag(8), 8, 193))));
        check("local build 0 may move to the first public build",
                () -> eq(UpdatePolicy.Verdict.UPDATE, decide(UpdatePolicy.identity(identity("local-0123456", 0, 193), false),
                        manifest(tag(1), 1, 193))));
        check("newer game version is offered", () -> eq(UpdatePolicy.Verdict.UPDATE, decide(installed(7), manifest(tag(8), 8, 200))));
        check("owner/repo comparison ignores case", () -> eq(UpdatePolicy.Verdict.UPDATE,
                decide(installed(7), with(manifest(tag(8), 8, 193), "repository", "Ardzz/Truckers-Of-Europe-3-Mod"))));
        rejects("newer build that downgrades the game version", () -> decide(installed(7), manifest(tag(8), 8, 150)));
        rejects("release signed with another certificate",
                () -> UpdatePolicy.decide(installed(7), CERT, UpdatePolicy.release(with(manifest(tag(8), 8, 193), "signing_cert_sha256", repeat("cd", 32)))));
        rejects("release of another repository", () -> decide(installed(7), with(manifest(tag(8), 8, 193), "repository", "someone/fork")));
        rejects("release of another package", () -> decide(installed(7), with(manifest(tag(8), 8, 193), "package", "com.example.other")));
        rejects("release for another ABI", () -> decide(installed(7), with(manifest(tag(8), 8, 193), "abi", "armeabi-v7a")));
    }

    // ------------------------------------------------------------------ GitHub release listing

    private static void githubReleaseChecks() {
        check("plan pairs every APK with its canonical asset URL, base first", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            List<UpdatePolicy.Download> plan = plan(github(tag(8), m), m);
            eq(3, plan.size());
            eq(BASE, plan.get(0).apk.name);
            eq(URI.create("https://github.com/" + REPO + "/releases/download/" + tag(8) + "/" + BASE), plan.get(0).url);
        });
        check("release without update.json offers no OTA metadata", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            UpdatePolicy.GithubRelease gh = UpdatePolicy.githubRelease(withoutAsset(github(tag(8), m), UpdatePolicy.MANIFEST_NAME), REPO);
            yes(!gh.has(UpdatePolicy.MANIFEST_NAME), "update.json reported present");
            expectRejected(() -> gh.asset(UpdatePolicy.MANIFEST_NAME));
        });
        check("owner/repo case in an asset URL is ignored", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            plan(withAssetUrl(github(tag(8), m), BASE,
                    "https://github.com/Ardzz/Truckers-Of-Europe-3-Mod/releases/download/" + tag(8) + "/" + BASE), m);
        });
        rejects("draft release", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            UpdatePolicy.githubRelease(with(github(tag(8), m), "draft", true), REPO);
        });
        rejects("pre-release", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            UpdatePolicy.githubRelease(with(github(tag(8), m), "prerelease", true), REPO);
        });
        rejects("release without draft flag", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            UpdatePolicy.githubRelease(without(github(tag(8), m), "draft"), REPO);
        });
        rejects("asset still uploading", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            plan(withAssetField(github(tag(8), m), ASSETS, "state", "starter"), m);
        });
        rejects("asset listed twice", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            Map<String, Object> gh = github(tag(8), m);
            List<Object> assets = new ArrayList<>((List<?>) gh.get("assets"));
            assets.add(asset(tag(8), BASE, 1000));
            UpdatePolicy.githubRelease(with(gh, "assets", assets), REPO);
        });
        rejects("asset size differs from update.json", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            plan(withAssetField(github(tag(8), m), CONFIG, "size", 301), m);
        });
        rejects("update.json published in another release", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            plan(github(tag(9), m), m);
        });
        String good = "https://github.com/" + REPO + "/releases/download/" + tag(8) + "/" + BASE;
        String[] bad = {
            good.replace("https:", "http:"),
            good.replace("//github.com/", "//objects.githubusercontent.com/"),
            good.replace("//github.com/", "//evil.example/"),
            good.replace("//github.com/", "//user@github.com/"),
            good.replace("//github.com/", "//github.com:8443/"),
            good.replace("//github.com/", "//github.com:443/"),
            good.replace(REPO, "someone/fork"),
            good.replace(tag(8), tag(7)),
            good.replace("/" + BASE, "/" + ASSETS),
            good.replace("/" + BASE, "/%2e%2e/" + BASE),
            good.replace(tag(8) + "/", tag(8) + "/../" + tag(8) + "/"),
            good + "?token=1",
            good + "#x",
            "github.com/" + REPO + "/releases/download/" + tag(8) + "/" + BASE,
            "https://github.com/ " + REPO,
        };
        for (final String url : bad) {
            rejects("asset URL " + url, () -> {
                Map<String, Object> m = manifest(tag(8), 8, 193);
                plan(withAssetUrl(github(tag(8), m), BASE, url), m);
            });
        }
    }

    // ------------------------------------------------------------------ semantic release tags (unchanged schema)

    private static void semanticReleaseChecks() {
        check("legacy build 7 moves to v0.1.0 build 8: plan, offer and embedded identity", () -> {
            Map<String, Object> m = manifest("v0.1.0", 8, 193);
            UpdatePolicy.Release r = UpdatePolicy.release(m);
            List<UpdatePolicy.Download> plan = UpdatePolicy.plan(r, UpdatePolicy.githubRelease(github("v0.1.0", m), REPO));
            eq(3, plan.size());
            eq(URI.create("https://github.com/" + REPO + "/releases/download/v0.1.0/" + BASE), plan.get(0).url);
            eq(UpdatePolicy.Verdict.UPDATE, UpdatePolicy.decide(installed(7), CERT, r));
            UpdatePolicy.requireEmbedded(signed("v0.1.0", 8), r, ASSETS);
            expectRejected(() -> UpdatePolicy.requireEmbedded(signed(tag(7), 7), r, CONFIG));
        });
        rejects("v0.1.0 manifest carrying a mod_version field",
                () -> UpdatePolicy.release(with(manifest("v0.1.0", 8, 193), "mod_version", "0.1.0")));
        check("build counter, not the version tag, orders releases", () -> {
            UpdatePolicy.Identity v010 = installed("v0.1.0", 8);
            eq(UpdatePolicy.Verdict.UP_TO_DATE, decide(v010, manifest("v0.1.0", 8, 193)));
            eq(UpdatePolicy.Verdict.UPDATE, decide(v010, manifest("v0.1.1", 9, 193)));
            eq(UpdatePolicy.Verdict.NOT_NEWER, decide(v010, manifest("v9.0.0", 8, 193)));
            eq(UpdatePolicy.Verdict.NOT_NEWER, decide(v010, manifest(tag(7), 7, 193)));
            eq(UpdatePolicy.Verdict.NOT_NEWER, decide(installed(9), manifest("v0.1.0", 8, 193)));
            eq(UpdatePolicy.Verdict.UPDATE, decide(installed("local-0123456", 0), manifest("v0.1.0", 8, 193)));
        });
        check("mod version comes from a published vMAJOR.MINOR.PATCH tag only", () -> {
            eq("0.1.0", installed("v0.1.0", 8).modVersion());
            eq("1.10.2", installed("v1.10.2", 9).modVersion());
            eq(null, installed(7).modVersion()); // game version 0.7.64 inside a legacy tag is not a mod version
            eq(null, installed("toe3-v0.7.64-b193-mod-7f0d995", 7).modVersion());
            eq(null, installed("local-0123456", 0).modVersion());
            eq(null, installed("v0.1.0", 0).modVersion()); // unpublished
        });
        for (final String name : new String[] {"v01.1.0", "v1.02.3", "v0.1", "v0.1.0.1", "v0.1.0-rc.1", "0.1.0", "V0.1.0",
                "v0.7.64-b193"}) {
            check("tag " + name + " stays a valid identity without a mod version", () -> eq(null, installed(name, 8).modVersion()));
        }
    }

    // ------------------------------------------------------------------ release notes (unsigned, display only)

    private static void releaseNotesChecks() {
        String site = "https://github.com/" + REPO;
        final String body = "![artwork](" + site + "/hero.webp)\n\nIntro paragraph\n\n### Changes in this build\n\n"
                + "Commits that change the mod build inputs since [`" + tag(7) + "`](" + site + "/releases/tag/" + tag(7)
                + ") ([compare](" + site + "/compare/a...b)):\n\n"
                + "- [`0123456`](" + site + "/commit/" + REV + ") Fix \\*fuel\\* \\_hold\\_ \\[x\\] \\`y\\`\n"
                + "- [`89abcde`](" + site + "/commit/" + REV + ") Second\u202Echange\u0085here\u0007\n\n"
                + "Curated release history: [CHANGELOG.md](" + site + "/blob/main/CHANGELOG.md)\n\n"
                + "### Menu controls\n\n- **Economy:** money\n";
        check("change list keeps every short hash and message, drops link destinations", () -> {
            String c = UpdatePolicy.changes(body);
            yes(c.contains("0123456 Fix *fuel* _hold_ [x] `y`"), "first commit: " + c);
            yes(c.contains("89abcde Second change here\n"), "second commit: " + c);
            yes(c.contains(tag(7)), "previous release tag lost: " + c);
            yes(!c.contains("://") && !c.contains("]("), "link destination kept: " + c);
            displayOnly(c);
        });
        check("change list spans exactly its section", () -> {
            String c = UpdatePolicy.changes(body);
            yes(!c.contains("Intro") && !c.contains("artwork"), "text before the heading: " + c);
            yes(c.contains("CHANGELOG.md") && !c.contains("Menu controls") && !c.contains("Economy"), "section end: " + c);
        });
        check("deeper headings stay inside the section, a higher one ends it",
                () -> eq("Fixes\n\u2022 one\n\n\u2022 two", UpdatePolicy.changes(
                        "## Release\r\n### Changes in this build ###\r\n#### Fixes\r\n- one\r\n\r\n\r\n* two\r\n\r\n## Assets\r\n- three\r\n")));
        check("missing or empty change list is absent, not an error", () -> {
            eq(null, UpdatePolicy.changes(""));
            eq(null, UpdatePolicy.changes("### Menu controls\n- a\n"));
            eq(null, UpdatePolicy.changes("###Changes in this build\n- not a heading without a space\n"));
            eq(null, UpdatePolicy.changes("### Changes in this build\n\n \t\n``\n### Menu controls\n- a\n"));
        });
        check("release notes reach the parsed release", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            eq(UpdatePolicy.changes(body), UpdatePolicy.githubRelease(with(github(tag(8), m), "body", body), REPO).changes);
        });
        check("release without usable notes still plans its update", () -> {
            Map<String, Object> m = manifest(tag(8), 8, 193);
            for (Object notes : new Object[] {null, 42, "no change list here"}) {
                Map<String, Object> gh = notes == null ? github(tag(8), m) : with(github(tag(8), m), "body", notes);
                UpdatePolicy.GithubRelease r = UpdatePolicy.githubRelease(gh, REPO);
                eq(null, r.changes);
                eq(3, UpdatePolicy.plan(UpdatePolicy.release(m), r).size());
            }
        });
        check("long change list is bounded without splitting a character", () -> {
            for (int pairs = 1; pairs <= 12; pairs++) { // every alignment of the cut against surrogate pairs
                StringBuilder b = new StringBuilder("### Changes in this build\n");
                for (int i = 0; i < 6000; i++) b.append("- ").append(repeat("\uD83D\uDE9A", pairs)).append('\n');
                String c = UpdatePolicy.changes(b.append("### Menu controls\n").toString());
                yes(c.length() <= UpdatePolicy.MAX_CHANGES + 2, "length " + c.length());
                yes(c.endsWith("\n\u2026"), "truncation not marked");
                displayOnly(c);
            }
        });
        check("overlong line with unbalanced brackets stays bounded", () -> {
            String c = UpdatePolicy.changes("### Changes in this build\n- " + repeat("[x](", 100_000) + "\n- next\n");
            yes(c.length() <= UpdatePolicy.MAX_CHANGES + 2, "length " + c.length());
            yes(c.endsWith("\u2022 next"), "line after the overlong one lost: " + c.substring(Math.max(0, c.length() - 40)));
        });
    }

    // ------------------------------------------------------------------ connections and redirects

    private static void transportChecks() {
        check("API URL is an allowed connection",
                () -> eq("api.github.com", UpdatePolicy.transport(UpdatePolicy.latestReleaseUrl(REPO)).getHost()));
        String[] allowed = {
            "https://github.com/x",
            "https://api.github.com/x",
            "https://objects.githubusercontent.com/x?sig=1",
            "https://release-assets.githubusercontent.com:443/x",
            "HTTPS://GitHub.com/x",
        };
        for (final String u : allowed) check("connection to " + u, () -> UpdatePolicy.transport(URI.create(u)));
        String[] refused = {
            "http://github.com/x",
            "https://githubusercontent.com/x",
            "https://evilgithubusercontent.com/x",
            "https://github.com.evil.example/x",
            "https://objects.githubusercontent.com.evil.example/x",
            "https://u:p@objects.githubusercontent.com/x",
            "https://github.com:8443/x",
            "https://140.82.112.3/x",
            "https://github.com./x",
            "ftp://github.com/x",
        };
        for (final String u : refused) rejects("connection to " + u, () -> UpdatePolicy.transport(URI.create(u)));

        final URI from = URI.create("https://github.com/" + REPO + "/releases/download/t/base.apk");
        check("relative redirect stays on the current host", () -> {
            URI next = UpdatePolicy.redirect(from, "/other/path");
            eq("github.com", next.getHost());
            eq("/other/path", next.getPath());
        });
        check("redirect to the release CDN",
                () -> eq("release-assets.githubusercontent.com",
                        UpdatePolicy.redirect(from, "https://release-assets.githubusercontent.com/a?b=c").getHost()));
        String[] badRedirects = {
            "//evil.example/x",
            "http://objects.githubusercontent.com/x",
            "https://evil.example/x",
            "javascript:alert(1)",
            "https://exa mple.com/",
            "",
        };
        for (final String loc : badRedirects) rejects("redirect to '" + loc + "'", () -> UpdatePolicy.redirect(from, loc));
        rejects("redirect without Location", () -> UpdatePolicy.redirect(from, null));
    }

    // ------------------------------------------------------------------ bytes, checksums and cache

    private static void byteChecks(File scratch) throws IOException {
        check("copyExact returns the SHA-256 of exactly the announced bytes", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    UpdatePolicy.copyExact(stream("abc"), out, 3, null, "x"));
            eq("abc", new String(out.toByteArray(), StandardCharsets.UTF_8));
        });
        rejects("truncated download", () -> UpdatePolicy.copyExact(stream("abc"), new ByteArrayOutputStream(), 4, null, "x"));
        rejects("download longer than announced", () -> UpdatePolicy.copyExact(stream("abc"), new ByteArrayOutputStream(), 2, null, "x"));
        check("cancellation from the progress callback stops the copy", () -> {
            try {
                UpdatePolicy.copyExact(stream("abc"), new ByteArrayOutputStream(), 3, bytes -> {
                    throw new InterruptedIOException("cancelled");
                }, "x");
            } catch (InterruptedIOException expected) {
                return;
            }
            throw new AssertionError("copy finished despite cancellation");
        });
        check("bounded read at the limit", () -> eq(8, UpdatePolicy.readBounded(stream("12345678"), 8, "x").length));
        rejects("bounded read over the limit", () -> UpdatePolicy.readBounded(stream("123456789"), 8, "x"));

        File dir = new File(scratch, "cache");
        yes(dir.mkdirs() || dir.isDirectory(), "scratch dir");
        final File f = new File(dir, BASE);
        final byte[] data = "pretend this is an APK".getBytes(StandardCharsets.UTF_8);
        final String digest = UpdatePolicy.sha256Hex(data);
        check("intact cached file verifies", () -> {
            write(f, data);
            UpdatePolicy.verifyFile(f, data.length, digest, null, BASE);
        });
        rejects("cached file with a flipped byte", () -> {
            write(f, data);
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.seek(3);
                raf.write(data[3] ^ 1);
            }
            UpdatePolicy.verifyFile(f, data.length, digest, null, BASE);
        });
        rejects("truncated cached file", () -> {
            write(f, Arrays.copyOf(data, data.length - 1));
            UpdatePolicy.verifyFile(f, data.length, digest, null, BASE);
        });
        rejects("cached file with appended bytes", () -> {
            write(f, Arrays.copyOf(data, data.length + 1));
            UpdatePolicy.verifyFile(f, data.length, digest, null, BASE);
        });
        rejects("cached file deleted", () -> {
            yes(!f.exists() || f.delete(), "delete");
            UpdatePolicy.verifyFile(f, data.length, digest, null, BASE);
        });
    }

    // ------------------------------------------------------------------ signed identity inside every APK

    private static void embeddedIdentityChecks(File scratch) throws IOException {
        final byte[] buildJson = "{\"schema\":1}".getBytes(StandardCharsets.UTF_8);
        final File withId = zip(new File(scratch, "split-with-id.apk"), UpdatePolicy.BUILD_ENTRY, buildJson);
        final File withoutId = zip(new File(scratch, "split-without-id.apk"), "classes.dex", buildJson);
        final File huge = zip(new File(scratch, "split-huge-id.apk"), UpdatePolicy.BUILD_ENTRY, new byte[UpdatePolicy.MAX_BUILD_JSON + 1]);
        check("build.json is read from an APK", () -> eq(Arrays.toString(buildJson),
                Arrays.toString(UpdatePolicy.readZipEntry(withId, UpdatePolicy.BUILD_ENTRY, UpdatePolicy.MAX_BUILD_JSON, "split"))));
        rejects("APK without build.json (missing per-split metadata)",
                () -> UpdatePolicy.readZipEntry(withoutId, UpdatePolicy.BUILD_ENTRY, UpdatePolicy.MAX_BUILD_JSON, "split"));
        rejects("oversized build.json", () -> UpdatePolicy.readZipEntry(huge, UpdatePolicy.BUILD_ENTRY, UpdatePolicy.MAX_BUILD_JSON, "split"));

        check("APK carrying the release identity is accepted", () -> UpdatePolicy.requireEmbedded(
                UpdatePolicy.identity(identity(tag(8), 8, 193), true), UpdatePolicy.release(manifest(tag(8), 8, 193)), ASSETS));
        rejects("stale split from an older build of the same game version", () -> UpdatePolicy.requireEmbedded(
                UpdatePolicy.identity(identity(tag(7), 7, 193), true), UpdatePolicy.release(manifest(tag(8), 8, 193)), CONFIG));
        rejects("APK from another commit under the same tag", () -> UpdatePolicy.requireEmbedded(
                UpdatePolicy.identity(with(identity(tag(8), 8, 193), "mod_revision", repeat("f", 40)), true),
                UpdatePolicy.release(manifest(tag(8), 8, 193)), BASE));
        rejects("manifest that drops an APK from both lists contradicts the signed base", () -> {
            Map<String, Object> m = with(withoutApk(manifest(tag(8), 8, 193), ASSETS), "required_apks", list(BASE, CONFIG));
            UpdatePolicy.Release r = UpdatePolicy.release(m); // self-consistent, so only the signed copy can catch it
            UpdatePolicy.requireEmbedded(UpdatePolicy.identity(identity(tag(8), 8, 193), true), r, BASE);
        });
    }

    // ------------------------------------------------------------------ fixtures

    private static String tag(int build) {
        return "toe3-v0.7.64-b193-mod-" + build;
    }

    private static Map<String, Object> identity(String tag, int build, int versionCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        m.put("repository", REPO);
        m.put("package", PKG);
        m.put("abi", "arm64-v8a");
        m.put("tag", tag);
        m.put("mod_build", build);
        m.put("mod_revision", REV);
        m.put("version_code", versionCode);
        m.put("version_name", "0.7.64");
        m.put("required_apks", new ArrayList<Object>(APKS));
        return m;
    }

    private static Map<String, Object> manifest(String tag, int build, int versionCode) {
        Map<String, Object> m = identity(tag, build, versionCode);
        m.put("base_apk", BASE);
        m.put("signing_cert_sha256", CERT);
        List<Object> apks = new ArrayList<>();
        for (Map.Entry<String, Integer> e : SIZES.entrySet()) apks.add(apk(e.getKey(), e.getValue()));
        m.put("apks", apks);
        return m;
    }

    private static Map<String, Object> apk(String name, long size) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", name);
        a.put("size", size);
        a.put("sha256", sha(name));
        return a;
    }

    private static Map<String, Object> github(String tag, Map<String, Object> manifest) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tag_name", tag);
        r.put("draft", false);
        r.put("prerelease", false);
        List<Object> assets = new ArrayList<>();
        assets.add(asset(tag, UpdatePolicy.MANIFEST_NAME, 2048));
        assets.add(asset(tag, "SHA256SUMS", 400));
        for (Object o : (List<?>) manifest.get("apks")) {
            Map<?, ?> a = (Map<?, ?>) o;
            assets.add(asset(tag, (String) a.get("name"), ((Number) a.get("size")).longValue()));
        }
        r.put("assets", assets);
        return r;
    }

    private static Map<String, Object> asset(String tag, String name, long size) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", name);
        a.put("state", "uploaded");
        a.put("size", size);
        a.put("browser_download_url", "https://github.com/" + REPO + "/releases/download/" + tag + "/" + name);
        return a;
    }

    private static UpdatePolicy.Identity installed(int build) throws UpdateException {
        return installed(tag(build), build);
    }

    private static UpdatePolicy.Identity installed(String tag, int build) throws UpdateException {
        return UpdatePolicy.identity(identity(tag, build, 193), false);
    }

    /** The identity a published APK carries. */
    private static UpdatePolicy.Identity signed(String tag, int build) throws UpdateException {
        return UpdatePolicy.identity(identity(tag, build, 193), true);
    }

    private static UpdatePolicy.Verdict decide(UpdatePolicy.Identity installed, Map<String, Object> manifest) throws UpdateException {
        return UpdatePolicy.decide(installed, CERT, UpdatePolicy.release(manifest));
    }

    private static List<UpdatePolicy.Download> plan(Map<String, Object> github, Map<String, Object> manifest) throws UpdateException {
        return UpdatePolicy.plan(UpdatePolicy.release(manifest), UpdatePolicy.githubRelease(github, REPO));
    }

    private static Map<String, Object> with(Map<String, Object> m, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, Object> without(Map<String, Object> m, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.remove(key);
        return copy;
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /** Copy of {@code m} whose list {@code key} has the entry named {@code name} removed or one field set. */
    private static Map<String, Object> editEntry(Map<String, Object> m, String key, String name, String field, Object value,
            boolean remove) {
        List<Object> out = new ArrayList<>();
        for (Object o : (List<?>) m.get(key)) {
            Map<?, ?> entry = (Map<?, ?>) o;
            if (!name.equals(entry.get("name"))) {
                out.add(entry);
            } else if (!remove) {
                Map<Object, Object> changed = new LinkedHashMap<>(entry);
                changed.put(field, value);
                out.add(changed);
            }
        }
        return with(m, key, out);
    }

    private static Map<String, Object> withoutApk(Map<String, Object> m, String name) {
        return editEntry(m, "apks", name, null, null, true);
    }

    private static Map<String, Object> withApkField(Map<String, Object> m, String name, String field, Object value) {
        return editEntry(m, "apks", name, field, value, false);
    }

    private static Map<String, Object> withoutAsset(Map<String, Object> gh, String name) {
        return editEntry(gh, "assets", name, null, null, true);
    }

    private static Map<String, Object> withAssetField(Map<String, Object> gh, String name, String field, Object value) {
        return editEntry(gh, "assets", name, field, value, false);
    }

    private static Map<String, Object> withAssetUrl(Map<String, Object> gh, String name, String url) {
        return withAssetField(gh, name, "browser_download_url", url);
    }

    private static String sha(String s) {
        return UpdatePolicy.sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(File f, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static File zip(File f, String entry, byte[] data) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            z.write(new byte[] {3, 0, 8, 0});
            z.closeEntry();
            z.putNextEntry(new ZipEntry(entry));
            z.write(data);
            z.closeEntry();
        }
        return f;
    }

    /** Only newlines and displayable characters, with every surrogate pair intact. */
    private static void displayOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean control = (c < 0x20 && c != '\n') || (c >= 0x7F && c <= 0x9F) || c == '\u200E' || c == '\u200F'
                    || (c >= '\u2028' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069');
            yes(!control, "control character U+" + Integer.toHexString(c) + " at " + i);
            if (Character.isHighSurrogate(c)) yes(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)), "split pair at " + i);
            if (Character.isLowSurrogate(c)) yes(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)), "split pair at " + i);
        }
    }

    // ------------------------------------------------------------------ harness

    private static void check(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("ok    " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + ": " + t);
        }
    }

    private static void rejects(String name, Body body) {
        check("rejects " + name, () -> expectRejected(body));
    }

    private static void expectRejected(Body body) throws Exception {
        try {
            body.run();
        } catch (UpdateException expected) {
            return;
        }
        throw new AssertionError("accepted");
    }

    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void yes(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
