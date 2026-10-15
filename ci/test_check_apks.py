"""Regression tests for ci/check_apks.py (stdlib only, no game files needed).

    python3 -m unittest discover -s ci -p 'test_*.py' -v

APK sets are synthetic ZIPs checked against a synthetic baseline; aapt2/apksigner are replaced by a
stub runner that answers in the real tools' output format, so every rejection path is exercised
deterministically. The committed ci/compatibility.json is only checked for being a valid baseline.
"""

import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_apks as ca  # noqa: E402

PKG = "com.example.trucks"
SIGNER_SHA1 = "1f" * 20
SIGNER_SHA256 = "2e" * 32
OTHER_SHA1 = "0123456789abcdef0123456789abcdef01234567"
OTHER_SHA256 = "ab" * 32
UNITY = "lib/arm64-v8a/libunity.so"
REVIEWED = {
    ca.NATIVE_LIB: b"reviewed il2cpp code",
    ca.METADATA: b"reviewed il2cpp metadata",
    UNITY: b"reviewed unity engine",
}
ASSET_PACK = "UnityDataAssetPack"
GAME_DATA = ("assets/bin/Data/level1", "assets/aa/settings.json")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def baseline_doc():
    return {
        "schema": 1,
        "package": PKG,
        "signer_cert_sha1": SIGNER_SHA1,
        "signer_cert_sha256": SIGNER_SHA256,
        "reviewed_build": {"version_name": "1.0", "version_code": 10},
        "pinned_entries": {p: {"sha256": sha256(b), "size": len(b)} for p, b in REVIEWED.items()},
        "required_splits": {ASSET_PACK: list(GAME_DATA)},
        "notes": ["synthetic"],
    }


def badging(split=None, version_code="10", version_name="1.0", package=PKG):
    attrs = f"name='{package}' versionCode='{version_code}' versionName='{version_name}'"
    if split is not None:
        attrs += f" split='{split}'"
    return (
        f"package: {attrs} platformBuildVersionName='15' platformBuildVersionCode='35'\n"
        "minSdkVersion:'23'\n"
        "application: label='' icon=''\n"
    )


def certs(signers=((SIGNER_SHA1, SIGNER_SHA256),)):
    """apksigner verify --print-certs output, including a Play source stamp signer."""
    lines = []
    for sha1, sha2 in signers:
        lines += [
            "V3.0 Signer: certificate DN: CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US",
            f"V3.0 Signer: certificate SHA-256 digest: {sha2}",
            f"V3.0 Signer: certificate SHA-1 digest: {sha1}",
            "V3.0 Signer: certificate MD5 digest: " + "5c" * 16,
        ]
    lines += [
        "Source Stamp Signer: certificate DN: CN=Android, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US",
        "Source Stamp Signer: certificate SHA-256 digest: " + "33" * 32,
        "Source Stamp Signer: certificate SHA-1 digest: " + "44" * 20,
    ]
    return "\n".join(lines) + "\n"


class StubRunner:
    """Answers aapt2 / apksigner invocations per APK file name."""

    def __init__(self):
        self.badging = {}
        self.certs = {}
        self.unverifiable = set()
        self.calls = []

    def __call__(self, argv):
        self.calls.append(list(argv))
        name = Path(argv[-1]).name
        if argv[1:3] == ["dump", "badging"]:
            if name not in self.badging:
                return 1, "", f"ERROR: {argv[-1]}: failed opening zip: Invalid file.\n"
            return 0, self.badging[name], ""
        if argv[1:3] == ["verify", "--print-certs"]:
            if name in self.unverifiable:
                return 1, "", "DOES NOT VERIFY\nERROR: APK Signature Scheme v3 signer #1: APK integrity check failed\n"
            return 0, self.certs.get(name, certs()), ""
        raise AssertionError(f"unexpected command {argv}")


def write_zip(path, entries):
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")  # duplicate-name fixtures trigger a UserWarning
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
            for name, data in entries:
                z.writestr(name, data)


MANIFEST = ("AndroidManifest.xml", b"<manifest/>")
ARM64_LIBS = [(ca.NATIVE_LIB, REVIEWED[ca.NATIVE_LIB]), (UNITY, REVIEWED[UNITY])]
GAME_DATA_ENTRIES = [(entry, b"scene data") for entry in GAME_DATA]


class ApkSetCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.dir = self.tmp / "set"
        self.dir.mkdir()
        self.baseline = ca.validate_baseline(baseline_doc())
        self.tools = ca.Tools(aapt2="/sdk/build-tools/aapt2", apksigner="/sdk/build-tools/apksigner")
        self.runner = StubRunner()

    def add_apk(self, name, entries, split=None, **badging_kw):
        write_zip(self.dir / name, entries)
        self.runner.badging[name] = badging(split, **badging_kw)

    def add_base(self, extra=(), **badging_kw):
        entries = [MANIFEST, ("classes.dex", b"dex\n035\0plain game code"), (ca.METADATA, REVIEWED[ca.METADATA])]
        self.add_apk("base.apk", entries + list(extra), **badging_kw)

    def add_arm64_split(self, libil2cpp=REVIEWED[ca.NATIVE_LIB], extra=(), **badging_kw):
        badging_kw.setdefault("version_name", "")
        entries = [MANIFEST, (ca.NATIVE_LIB, libil2cpp), (UNITY, REVIEWED[UNITY])]
        self.add_apk("split_config.arm64_v8a.apk", entries + list(extra), split="config.arm64_v8a", **badging_kw)

    def add_asset_pack(self, entries=None, split=ASSET_PACK, **badging_kw):
        badging_kw.setdefault("version_name", "")
        if entries is None:
            entries = [MANIFEST, *GAME_DATA_ENTRIES, ("assets/world_assets_all_0f1e.bundle", b"bundle")]
        self.add_apk("split_UnityDataAssetPack.apk", entries, split=split, **badging_kw)

    def add_play_split_set(self):
        self.add_base()
        self.add_arm64_split()
        self.add_asset_pack()
        # Downloader metadata is never trusted, however optimistic it is.
        (self.dir / "source.json").write_text(json.dumps(
            {"package": PKG, "version_name": "1.0", "version_code": 10, "signer_sha1": SIGNER_SHA1}))

    def check(self):
        return ca.check(self.dir, self.baseline, self.tools, self.runner)

    def assertRejected(self, *fragments):
        with self.assertRaises(ca.Incompatible) as cm:
            self.check()
        for fragment in fragments:
            self.assertIn(fragment, str(cm.exception))
        return str(cm.exception)


class AcceptsCompatibleSets(ApkSetCase):
    def test_play_split_set_reports_arm64_split_as_native_apk(self):
        self.add_play_split_set()
        self.assertEqual(self.check(), {
            "package": PKG,
            "version_name": "1.0",
            "version_code": 10,
            "base_apk": "base.apk",
            "native_apk": "split_config.arm64_v8a.apk",
            "apks": ["base.apk", "split_UnityDataAssetPack.apk", "split_config.arm64_v8a.apk"],
            "compatible": True,
        })

    def test_base_carrying_arm64_libs_next_to_asset_pack_is_the_native_apk(self):
        self.add_base(extra=ARM64_LIBS)
        self.add_asset_pack()
        summary = self.check()
        self.assertEqual(summary["native_apk"], "base.apk")
        self.assertEqual(summary["apks"], ["base.apk", "split_UnityDataAssetPack.apk"])

    def test_new_version_with_byte_identical_pinned_entries_is_accepted(self):
        self.add_base(version_code="11", version_name="1.1")
        self.add_arm64_split(version_code="11")
        self.add_asset_pack(version_code="11")
        summary = self.check()
        self.assertEqual((summary["version_name"], summary["version_code"]), ("1.1", 11))

    def test_additional_non_arm64_libs_do_not_change_native_apk(self):
        self.add_base(extra=ARM64_LIBS + [("lib/armeabi-v7a/libil2cpp.so", b"32-bit build")])
        self.add_asset_pack()
        self.assertEqual(self.check()["native_apk"], "base.apk")

    def test_split_version_name_equal_to_base_is_accepted(self):
        self.add_base()
        self.add_arm64_split(version_name="1.0")
        self.add_asset_pack(version_name="1.0")
        self.assertTrue(self.check()["compatible"])


class RequiresAssetPack(ApkSetCase):
    def test_base_and_arm64_split_without_asset_pack(self):
        self.add_base()
        self.add_arm64_split()
        self.assertRejected(ASSET_PACK, "missing")

    def test_standalone_apk_with_reviewed_native_code(self):
        self.add_base(extra=ARM64_LIBS)
        self.assertRejected(ASSET_PACK, "missing")

    def test_standalone_apk_carrying_the_game_data_itself(self):
        self.add_base(extra=ARM64_LIBS + GAME_DATA_ENTRIES)
        self.assertRejected(ASSET_PACK, "missing")

    def test_asset_pack_under_another_split_name(self):
        self.add_base()
        self.add_arm64_split()
        self.add_asset_pack(split="UnityDataAssetPack2")
        self.assertRejected(ASSET_PACK, "missing")

    def test_asset_pack_lacking_game_data(self):
        self.add_base()
        self.add_arm64_split()
        self.add_asset_pack(entries=[MANIFEST, (GAME_DATA[0], b"scene data")])
        self.assertRejected(GAME_DATA[1], "missing")

    def test_asset_pack_with_empty_game_data(self):
        self.add_base()
        self.add_arm64_split()
        self.add_asset_pack(entries=[MANIFEST, (GAME_DATA[0], b"scene data"), (GAME_DATA[1], b"")])
        self.assertRejected(GAME_DATA[1], "empty")

    def test_game_data_moved_into_base(self):
        self.add_base(extra=[(GAME_DATA[1], b"scene data")])
        self.add_arm64_split()
        self.add_asset_pack(entries=[MANIFEST, (GAME_DATA[0], b"scene data")])
        self.assertRejected(GAME_DATA[1], "base.apk")

    def test_game_data_duplicated_in_base(self):
        self.add_base(extra=[(GAME_DATA[0], b"other scene")])
        self.add_arm64_split()
        self.add_asset_pack()
        self.assertRejected(GAME_DATA[0], "multiple APKs")


class RejectsChangedNativeCode(ApkSetCase):
    def test_changed_libil2cpp(self):
        self.add_base()
        self.add_arm64_split(libil2cpp=b"recompiled il2cpp code")
        self.add_asset_pack()
        self.assertRejected(ca.NATIVE_LIB, "split_config.arm64_v8a.apk", "reviewed")

    def test_same_size_libil2cpp_with_different_bytes(self):
        original = REVIEWED[ca.NATIVE_LIB]
        self.add_base()
        self.add_arm64_split(libil2cpp=original[:-1] + bytes([original[-1] ^ 1]))
        self.add_asset_pack()
        self.assertRejected(ca.NATIVE_LIB)

    def test_changed_global_metadata(self):
        self.add_apk("base.apk", [MANIFEST, ("classes.dex", b"dex"), (ca.METADATA, b"new metadata")])
        self.add_arm64_split()
        self.add_asset_pack()
        self.assertRejected(ca.METADATA)

    def test_changed_libunity(self):
        self.add_base()
        self.add_apk("split_config.arm64_v8a.apk",
                     [MANIFEST, (ca.NATIVE_LIB, REVIEWED[ca.NATIVE_LIB]), (UNITY, b"newer engine")],
                     split="config.arm64_v8a", version_name="")
        self.add_asset_pack()
        self.assertRejected(UNITY)

    def test_missing_global_metadata(self):
        self.add_apk("base.apk", [MANIFEST, ("classes.dex", b"dex")])
        self.add_arm64_split()
        self.add_asset_pack()
        self.assertRejected(ca.METADATA, "not found")

    def test_only_32bit_libil2cpp(self):
        self.add_base()
        self.add_apk("split_config.armeabi_v7a.apk",
                     [MANIFEST, ("lib/armeabi-v7a/libil2cpp.so", REVIEWED[ca.NATIVE_LIB]),
                      ("lib/armeabi-v7a/libunity.so", REVIEWED[UNITY])],
                     split="config.armeabi_v7a", version_name="")
        self.add_asset_pack()
        self.assertRejected("arm64-v8a", "lib/armeabi-v7a/libil2cpp.so")

    def test_libil2cpp_in_two_apks(self):
        self.add_base(extra=[(ca.NATIVE_LIB, REVIEWED[ca.NATIVE_LIB])])
        self.add_arm64_split()
        self.add_asset_pack()
        self.assertRejected(ca.NATIVE_LIB, "base.apk", "split_config.arm64_v8a.apk")

    def test_duplicate_entry_inside_one_apk(self):
        self.add_base()
        self.add_arm64_split(extra=[(ca.NATIVE_LIB, b"shadow copy")])
        self.add_asset_pack()
        self.assertRejected("duplicate", ca.NATIVE_LIB)


class RejectsBrokenOrModdedApks(ApkSetCase):
    def test_truncated_apk(self):
        self.add_play_split_set()
        path = self.dir / "split_config.arm64_v8a.apk"
        path.write_bytes(path.read_bytes()[: path.stat().st_size // 2])
        self.assertRejected("split_config.arm64_v8a.apk")

    def test_entry_with_corrupt_data_fails_crc(self):
        self.add_base()
        self.add_asset_pack()
        data = REVIEWED[ca.NATIVE_LIB]
        with zipfile.ZipFile(self.dir / "split_config.arm64_v8a.apk", "w", zipfile.ZIP_STORED) as z:
            z.writestr(ca.NATIVE_LIB, data)
            z.writestr(UNITY, REVIEWED[UNITY])
        self.runner.badging["split_config.arm64_v8a.apk"] = badging("config.arm64_v8a", version_name="")
        raw = (self.dir / "split_config.arm64_v8a.apk").read_bytes()
        at = raw.index(data)
        (self.dir / "split_config.arm64_v8a.apk").write_bytes(raw[:at] + b"X" + raw[at + 1:])
        self.assertRejected("split_config.arm64_v8a.apk", ca.NATIVE_LIB)

    def test_apk_aapt2_cannot_read(self):
        self.add_play_split_set()
        del self.runner.badging["split_UnityDataAssetPack.apk"]
        self.assertRejected("split_UnityDataAssetPack.apk", "aapt2")

    def test_already_injected_native_lib(self):
        self.add_play_split_set()
        self.add_arm64_split(extra=[("lib/arm64-v8a/libtoemod.so", b"\x7fELF mod")])
        self.assertRejected("libtoemod.so")

    def test_already_injected_menu_dex(self):
        self.add_base(extra=[("classes5.dex", b"dex\n035\0" + b"Lcom/toemod/Menu;")])
        self.add_arm64_split()
        self.add_asset_pack()
        self.assertRejected("classes5.dex", "com/toemod")

    def test_menu_marker_split_across_read_chunks(self):
        dex = b"dex\n035\0".ljust(ca.CHUNK - 5, b"\0") + b"Lcom/toemod/Menu;"
        self.add_base(extra=[("classes2.dex", dex)])
        self.add_arm64_split()
        self.add_asset_pack()
        self.assertRejected("classes2.dex")

    def test_empty_directory(self):
        self.assertRejected("no *.apk")

    def test_missing_directory(self):
        self.dir = self.tmp / "absent"
        self.assertRejected("absent")


class RejectsInconsistentIdentity(ApkSetCase):
    def test_split_with_other_version_code(self):
        self.add_play_split_set()
        self.add_arm64_split(version_code="9")
        self.assertRejected("versionCode", "split_config.arm64_v8a.apk")

    def test_split_with_other_version_name(self):
        self.add_play_split_set()
        self.add_arm64_split(version_name="0.9")
        self.assertRejected("versionName", "split_config.arm64_v8a.apk")

    def test_base_without_version_name(self):
        self.add_play_split_set()
        self.add_base(version_name="")
        self.assertRejected("versionName", "base.apk")

    def test_zero_version_code(self):
        self.add_base(version_code="0")
        self.add_arm64_split(version_code="0")
        self.add_asset_pack(version_code="0")
        self.assertRejected("versionCode")

    def test_non_numeric_version_code(self):
        self.add_base(version_code="10a")
        self.add_arm64_split(version_code="10a")
        self.add_asset_pack(version_code="10a")
        self.assertRejected("versionCode")

    def test_other_package(self):
        self.add_base(package="com.example.clone")
        self.add_arm64_split(package="com.example.clone")
        self.add_asset_pack(package="com.example.clone")
        self.assertRejected("com.example.clone")

    def test_split_of_other_package(self):
        self.add_play_split_set()
        self.add_arm64_split(package="com.example.clone")
        self.assertRejected("com.example.clone", "split_config.arm64_v8a.apk")

    def test_duplicate_split_name(self):
        self.add_play_split_set()
        self.add_apk("split_config.arm64_v8a.copy.apk", [MANIFEST], split="config.arm64_v8a", version_name="")
        self.assertRejected("config.arm64_v8a", "split_config.arm64_v8a.copy.apk")

    def test_no_base(self):
        self.add_arm64_split()
        self.add_asset_pack()
        self.assertRejected("base")

    def test_two_bases(self):
        self.add_play_split_set()
        self.add_apk("other.apk", [MANIFEST])
        self.assertRejected("base.apk", "other.apk")


class RejectsUnpinnedSigners(ApkSetCase):
    def test_resigned_split(self):
        self.add_play_split_set()
        self.runner.certs["split_UnityDataAssetPack.apk"] = certs(((OTHER_SHA1, OTHER_SHA256),))
        self.assertRejected("split_UnityDataAssetPack.apk", OTHER_SHA1)

    def test_pinned_sha1_with_other_sha256(self):
        self.add_play_split_set()
        self.runner.certs["base.apk"] = certs(((SIGNER_SHA1, OTHER_SHA256),))
        self.assertRejected("base.apk", OTHER_SHA256)

    def test_additional_signer_next_to_pinned_one(self):
        self.add_play_split_set()
        self.runner.certs["base.apk"] = certs(((SIGNER_SHA1, SIGNER_SHA256), (OTHER_SHA1, OTHER_SHA256)))
        self.assertRejected("base.apk", OTHER_SHA1)

    def test_signature_that_does_not_verify(self):
        self.add_play_split_set()
        self.runner.unverifiable.add("split_config.arm64_v8a.apk")
        self.assertRejected("split_config.arm64_v8a.apk", "signature")

    def test_no_signer_reported(self):
        self.add_play_split_set()
        self.runner.certs["base.apk"] = "Verifies\n"
        self.assertRejected("base.apk", "signer")

    def test_every_apk_is_verified_with_apksigner(self):
        self.add_play_split_set()
        self.check()
        verified = {Path(c[-1]).name for c in self.runner.calls if c[1:3] == ["verify", "--print-certs"]}
        self.assertEqual(verified, {"base.apk", "split_UnityDataAssetPack.apk", "split_config.arm64_v8a.apk"})


class ParsesToolOutput(unittest.TestCase):
    def test_signer_digests_skip_source_stamp(self):
        self.assertEqual(ca.parse_signer_digests(certs(), "SHA-1"), [SIGNER_SHA1])
        self.assertEqual(ca.parse_signer_digests(certs(), "SHA-256"), [SIGNER_SHA256])

    def test_signer_digests_in_numbered_signer_format(self):
        text = (f"Signer #1 certificate DN: CN=toemod\n"
                f"Signer #1 certificate SHA-256 digest: {OTHER_SHA256.upper()}\n"
                f"Signer #1 certificate SHA-1 digest: {OTHER_SHA1}\n")
        self.assertEqual(ca.parse_signer_digests(text, "SHA-256"), [OTHER_SHA256])

    def test_badging_split_attributes(self):
        attrs = ca.parse_badging(
            f"package: name='{PKG}' versionCode='10' versionName='' split='config.arm64_v8a'\n"
            "application: label='' icon=''\nnative-code: 'arm64-v8a'\n")
        self.assertEqual(attrs["name"], PKG)
        self.assertEqual(attrs["versionCode"], "10")
        self.assertEqual(attrs["versionName"], "")
        self.assertEqual(attrs["split"], "config.arm64_v8a")

    def test_badging_without_package_line(self):
        with self.assertRaises(ca.Incompatible):
            ca.parse_badging("application: label='' icon=''\n")


class ValidatesBaseline(unittest.TestCase):
    def assertInvalid(self, doc, fragment):
        with self.assertRaises(ca.Incompatible) as cm:
            ca.validate_baseline(doc)
        self.assertIn(fragment, str(cm.exception))

    def test_malformed_hash(self):
        doc = baseline_doc()
        doc["pinned_entries"][ca.NATIVE_LIB]["sha256"] = "3B3A"
        self.assertInvalid(doc, ca.NATIVE_LIB)

    def test_missing_metadata_pin(self):
        doc = baseline_doc()
        del doc["pinned_entries"][ca.METADATA]
        self.assertInvalid(doc, ca.METADATA)

    def test_unknown_key(self):
        doc = baseline_doc()
        doc["allow_any_version"] = True
        self.assertInvalid(doc, "allow_any_version")

    def test_uppercase_signer(self):
        doc = baseline_doc()
        doc["signer_cert_sha1"] = SIGNER_SHA1.upper()
        self.assertInvalid(doc, "signer_cert_sha1")

    def test_boolean_size(self):
        doc = baseline_doc()
        doc["pinned_entries"][UNITY]["size"] = True
        self.assertInvalid(doc, UNITY)

    def test_path_escaping_entry_name(self):
        doc = baseline_doc()
        doc["pinned_entries"]["../lib/arm64-v8a/libunity.so"] = doc["pinned_entries"].pop(UNITY)
        self.assertInvalid(doc, "../lib/arm64-v8a/libunity.so")

    def test_missing_required_splits(self):
        doc = baseline_doc()
        del doc["required_splits"]
        self.assertInvalid(doc, "required_splits")

    def test_required_split_without_game_data(self):
        doc = baseline_doc()
        doc["required_splits"][ASSET_PACK] = []
        self.assertInvalid(doc, ASSET_PACK)

    def test_required_split_with_repeated_entry(self):
        doc = baseline_doc()
        doc["required_splits"][ASSET_PACK] = [GAME_DATA[0], GAME_DATA[0]]
        self.assertInvalid(doc, ASSET_PACK)


class FindsBuildTools(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)

    def make_tools(self, directory, names=("aapt2", "apksigner")):
        directory.mkdir(parents=True)
        for name in names:
            (directory / name).write_text("#!/bin/sh\nexit 0\n")
            (directory / name).chmod(0o755)
        return directory

    def test_sdk_build_tools_sorted_numerically(self):
        sdk = self.tmp / "sdk"
        self.make_tools(sdk / "build-tools" / "9.0.0")
        newest = self.make_tools(sdk / "build-tools" / "37.0.0")
        tools = ca.find_build_tools(None, env={"ANDROID_SDK_ROOT": str(sdk)})
        self.assertEqual(tools.apksigner, str(newest / "apksigner"))

    def test_build_tools_env_wins_over_sdk(self):
        sdk = self.tmp / "sdk"
        self.make_tools(sdk / "build-tools" / "37.0.0")
        pinned = self.make_tools(self.tmp / "bt-36")
        tools = ca.find_build_tools(None, env={"ANDROID_HOME": str(sdk), "BUILD_TOOLS": str(pinned)})
        self.assertEqual(tools.aapt2, str(pinned / "aapt2"))

    def test_explicit_directory_without_apksigner_does_not_fall_back(self):
        sdk = self.tmp / "sdk"
        self.make_tools(sdk / "build-tools" / "37.0.0")
        partial = self.make_tools(self.tmp / "partial", names=("aapt2",))
        with self.assertRaises(ca.Incompatible) as cm:
            ca.find_build_tools(str(partial), env={"ANDROID_HOME": str(sdk)})
        self.assertIn("apksigner", str(cm.exception))


class CommandLine(ApkSetCase):
    def run_main(self):
        bt = self.tmp / "bt"
        bt.mkdir()
        for name in ("aapt2", "apksigner"):
            (bt / name).write_text("#!/bin/sh\nexit 0\n")
            (bt / name).chmod(0o755)
        baseline_path = self.tmp / "compatibility.json"
        baseline_path.write_text(json.dumps(baseline_doc()))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = ca.main([str(self.dir), "--build-tools", str(bt)], run=self.runner, baseline_path=baseline_path)
        return code, out.getvalue(), err.getvalue()

    def test_success_prints_one_json_summary_line(self):
        self.add_play_split_set()
        code, out, err = self.run_main()
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(out.count("\n"), 1)
        summary = json.loads(out)
        self.assertIs(summary["compatible"], True)
        self.assertEqual(summary["native_apk"], "split_config.arm64_v8a.apk")

    def test_failure_exits_nonzero_with_reason_on_stderr_only(self):
        self.add_base()
        self.add_arm64_split(libil2cpp=b"recompiled il2cpp code")
        self.add_asset_pack()
        code, out, err = self.run_main()
        self.assertNotEqual(code, 0)
        self.assertEqual(out, "")
        self.assertIn(ca.NATIVE_LIB, err)
        self.assertEqual(err.count("\n"), 1)


if __name__ == "__main__":
    unittest.main()
