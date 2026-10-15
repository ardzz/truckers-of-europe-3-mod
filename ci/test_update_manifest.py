"""Build identity and OTA release manifest boundaries, with synthetic APKs and tool output."""

import contextlib
import hashlib
import io
import json
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import check_apks as ca
import test_check_apks as fixture
import update_manifest as um

REVISION = "0123456789abcdef" * 2 + "01234567"
MOD_CERT = "ab" * 32
CI_ENV = {
    "MOD_BUILD_NUMBER": "7",
    "MOD_RELEASE_TAG": "v0.1.0",
    "MOD_REVISION": REVISION,
    "MOD_REPOSITORY": "owner/trucks-mod",
}
APK_NAMES = ["base.apk", "split_UnityDataAssetPack.apk", "split_config.arm64_v8a.apk"]


def identity():
    return um.identity_from_env(CI_ENV, fixture.PKG, 10, "1.0", APK_NAMES)


def rewrite(path, replace=None, remove=()):
    replace = dict(replace or {})
    with zipfile.ZipFile(path) as zf:
        entries = [(i.filename, zf.read(i)) for i in zf.infolist() if i.filename not in remove and i.filename not in replace]
    entries += list(replace.items())
    fixture.write_zip(path, entries)


class IdentityTests(unittest.TestCase):
    def test_semantic_public_tags_are_independent_of_the_game_version(self):
        for tag in ("v0.1.0", "v1.10.2"):
            with self.subTest(tag=tag):
                try:
                    doc = um.identity_from_env({**CI_ENV, "MOD_RELEASE_TAG": tag},
                                               fixture.PKG, 10, "1.0", APK_NAMES)
                except um.MetadataError as error:
                    self.fail(f"Valid semantic mod tag rejected: {error}")
                self.assertEqual(doc["tag"], tag)
                self.assertEqual(doc["version_name"], "1.0")
        for tag in ("v01.1.0", "v1.01.0", "v1.0.01", "v1.2", "v1.2.3.4"):
            with self.subTest(tag=tag), self.assertRaises(um.MetadataError):
                um.identity_from_env({**CI_ENV, "MOD_RELEASE_TAG": tag},
                                     fixture.PKG, 10, "1.0", APK_NAMES)

    def test_local_defaults_and_explicit_ci_identity(self):
        local = um.identity_from_env({}, fixture.PKG, 10, "1.0", reversed(APK_NAMES), head=lambda: REVISION)
        self.assertEqual(local["mod_build"], 0)
        self.assertEqual(local["tag"], "local-0123456789ab")
        self.assertEqual(local["repository"], um.DEFAULT_REPOSITORY)
        self.assertEqual(local["required_apks"], APK_NAMES)
        public = identity()
        self.assertEqual(public["mod_build"], 7)
        self.assertEqual(public["tag"], CI_ENV["MOD_RELEASE_TAG"])
        self.assertEqual(public["mod_revision"], REVISION)
        self.assertEqual(set(public), set(um.IDENTITY_KEYS))
        self.assertEqual(json.loads(um.encode_identity(public)), public)
        github = um.identity_from_env({"GITHUB_REPOSITORY": "someone/repo"}, fixture.PKG, 10, "1.0", APK_NAMES,
                                      head=lambda: REVISION)
        self.assertEqual(github["repository"], "someone/repo")

    def test_invalid_environment_is_not_silently_replaced(self):
        for key, values in {
            "MOD_BUILD_NUMBER": ("", "-1", "1.5", "01", "true", str(um.MAX_BUILD + 1)),
            "MOD_REVISION": ("", "a" * 39, "A" * 40, "z" * 40),
            "MOD_REPOSITORY": ("", "owner", "owner/repo/extra", "../repo", "owner/repo?token=x"),
            "MOD_RELEASE_TAG": ("", "a/b", ".", "..", "https://example.com/anything", "wrong-version",
                                "toe3-v1.0-b10-mod-0123456", "v1.0.0-rc.1", "v1.0.0+1", "V1.0.0", "1.0.0",
                                "v0.0." + "1" * 124),
        }.items():
            for value in values:
                with self.subTest(key=key, value=value), self.assertRaises(um.MetadataError):
                    um.identity_from_env({**CI_ENV, key: value}, fixture.PKG, 10, "1.0", APK_NAMES)
        with self.assertRaises(um.MetadataError):
            um.identity_from_env({"MOD_RELEASE_TAG": "v0.1.0"}, fixture.PKG, 10, "1.0", APK_NAMES,
                                 head=lambda: REVISION)

    def test_wrong_schema_types_and_signed_set_are_rejected(self):
        invalid = {
            "schema": (True, 2), "mod_build": (True, -1), "abi": ("x86",),
            "version_code": (True, 0), "version_name": ("",),
            "required_apks": ([], ["../base.apk"], ["base.apk", "base.apk"], list(reversed(APK_NAMES))),
        }
        for key, values in invalid.items():
            for value in values:
                with self.subTest(key=key, value=value), self.assertRaises(um.MetadataError):
                    um.validate_identity({**identity(), key: value})
        for doc in ({**identity(), "extra": 1}, {k: v for k, v in identity().items() if k != "tag"}):
            with self.assertRaises(um.MetadataError):
                um.validate_identity(doc)
        with self.assertRaises(um.MetadataError):
            um.validate_identity(identity(), apks=["base.apk", "split_config.arm64_v8a.apk"])
        with self.assertRaises(um.MetadataError):
            um.validate_identity(identity(), version_code=11)
        with self.assertRaises(um.MetadataError):
            um.validate_identity(identity(), package="another.package")

    def test_producer_identity_respects_android_consumers_filename_and_list_bounds(self):
        longest = "a" * 124 + ".apk"
        self.assertEqual(len(longest), 128)
        self.assertEqual(um.validate_identity({**identity(), "required_apks": [longest]})["required_apks"], [longest])
        for names in (["a" + longest], sorted(f"part{i:02d}.apk" for i in range(17))):
            with self.subTest(names=names), self.assertRaises(um.MetadataError):
                um.validate_identity({**identity(), "required_apks": names})
        for version in (" leading", "trailing ", "é", "a" * 101):
            with self.subTest(version=version), self.assertRaises(um.MetadataError):
                um.validate_identity({**identity(), "version_name": version})

    def test_json_duplicate_keys_and_non_json_constants_rejected(self):
        for raw in (b'{"a":1,"a":2}', b'{"n":NaN}', b'{"n":Infinity}', b'\xff', b'{} extra'):
            with self.subTest(raw=raw), self.assertRaises(um.MetadataError):
                um.parse_json(raw, "metadata", 100)
        with self.assertRaises(um.MetadataError):
            um.parse_json(b" " * 101, "metadata", 100)


class BuildInfoTests(fixture.ApkSetCase):
    def setUp(self):
        super().setUp()
        self.add_play_split_set()

    def test_source_guard_determines_package_version_and_complete_file_list(self):
        doc = um.build_info(self.dir / "base.apk", self.tools, CI_ENV, self.baseline, self.runner)
        self.assertEqual(doc, identity())

    def test_missing_asset_pack_changed_native_or_wrong_base_prevents_identity(self):
        with self.assertRaises(ca.Incompatible):
            um.build_info(self.dir / "split_config.arm64_v8a.apk", self.tools, CI_ENV, self.baseline, self.runner)
        rewrite(self.dir / "split_config.arm64_v8a.apk", {ca.NATIVE_LIB: b"different game native code"})
        with self.assertRaises(ca.Incompatible):
            um.build_info(self.dir / "base.apk", self.tools, CI_ENV, self.baseline, self.runner)
        (self.dir / "split_UnityDataAssetPack.apk").unlink()
        with self.assertRaises(ca.Incompatible):
            um.build_info(self.dir / "base.apk", self.tools, CI_ENV, self.baseline, self.runner)

    def test_source_with_preexisting_signed_metadata_is_rejected(self):
        rewrite(self.dir / "split_UnityDataAssetPack.apk", {um.BUILD_INFO: um.encode_identity(identity())})
        with self.assertRaises(um.MetadataError):
            um.build_info(self.dir / "base.apk", self.tools, CI_ENV, self.baseline, self.runner)

    def test_cli_build_info_writes_exact_identity_only_on_success(self):
        baseline = self.tmp / "baseline.json"
        baseline.write_text(json.dumps(fixture.baseline_doc()))
        dest = self.tmp / "build.json"
        args = ["build-info", "--base-apk", str(self.dir / "base.apk"), "--output", str(dest)]
        with patch.object(ca, "find_build_tools", return_value=self.tools), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(um.main(args, self.runner, CI_ENV, baseline), 0)
        self.assertEqual(dest.read_bytes(), um.encode_identity(identity()))
        original = dest.read_bytes()
        with patch.object(ca, "find_build_tools", return_value=self.tools), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(um.main(args, self.runner, {**CI_ENV, "MOD_BUILD_NUMBER": "bad"}, baseline), 1)
        self.assertEqual(dest.read_bytes(), original)


class ReleaseTests(fixture.ApkSetCase):
    def setUp(self):
        super().setUp()
        self.add_play_split_set()
        self.report = ca.check(self.dir, self.baseline, self.tools, self.runner)
        self.report_path = self.tmp / "compatibility.json"
        self.report_path.write_text(json.dumps(self.report))
        for name in APK_NAMES:
            updates = {um.BUILD_INFO: um.encode_identity(identity())}
            if name == self.report["native_apk"]:
                updates[um.MOD_LIB] = b"actual native mod payload"
            rewrite(self.dir / name, updates)
            self.runner.certs[name] = fixture.certs((("cd" * 20, MOD_CERT),))

    def release(self):
        return um.update_manifest(self.dir, self.report, self.tools, self.baseline, self.runner)

    def test_manifest_uses_signed_identity_actual_signer_and_actual_apk_bytes(self):
        doc = self.release()
        self.assertEqual({k: doc[k] for k in um.IDENTITY_KEYS}, identity())
        self.assertEqual(doc["base_apk"], "base.apk")
        self.assertEqual(doc["signing_cert_sha256"], MOD_CERT)
        self.assertEqual([a["name"] for a in doc["apks"]], APK_NAMES)
        for item in doc["apks"]:
            raw = (self.dir / item["name"]).read_bytes()
            self.assertEqual(item, {"name": item["name"], "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
        self.assertEqual(set(doc), {*um.IDENTITY_KEYS, "base_apk", "signing_cert_sha256", "apks"})

    def test_missing_or_stale_split_identity_is_rejected(self):
        for name in ("split_UnityDataAssetPack.apk", "split_config.arm64_v8a.apk"):
            original = (self.dir / name).read_bytes()
            with self.subTest(name=name, issue="missing"):
                rewrite(self.dir / name, remove=(um.BUILD_INFO,))
                with self.assertRaises(um.MetadataError):
                    self.release()
                (self.dir / name).write_bytes(original)
            with self.subTest(name=name, issue="older mod, same game version"):
                rewrite(self.dir / name, {um.BUILD_INFO: um.encode_identity({**identity(), "mod_build": 6})})
                with self.assertRaises(um.MetadataError):
                    self.release()
                (self.dir / name).write_bytes(original)

    def test_signed_identity_cannot_omit_assetpack_or_forge_manifest_version(self):
        for changed in ({"required_apks": ["base.apk", "split_config.arm64_v8a.apk"]},
                        {"version_code": 11}, {"package": "other.package"}):
            with self.subTest(changed=changed):
                blob = um.encode_identity({**identity(), **changed})
                for name in APK_NAMES:
                    rewrite(self.dir / name, {um.BUILD_INFO: blob})
                with self.assertRaises(um.MetadataError):
                    self.release()

    def test_extra_or_missing_output_apks_are_rejected(self):
        extra = self.dir / "extra.apk"
        extra.write_bytes(b"unexpected APK")
        with self.assertRaises(um.MetadataError):
            self.release()
        extra.unlink()
        (self.dir / "split_UnityDataAssetPack.apk").unlink()
        with self.assertRaises(um.MetadataError):
            self.release()

    def test_wrong_output_identity_and_report_are_rejected(self):
        self.runner.badging["split_config.arm64_v8a.apk"] = fixture.badging("config.arm64_v8a", version_code="11")
        with self.assertRaises(ca.Incompatible):
            self.release()
        self.runner.badging["split_config.arm64_v8a.apk"] = fixture.badging("config.arm64_v8a", version_name="")
        for key, value in (("base_apk", "split_config.arm64_v8a.apk"), ("version_code", 11), ("version_name", "1.1")):
            old = self.report[key]
            self.report[key] = value
            with self.subTest(key=key), self.assertRaises(um.MetadataError):
                self.release()
            self.report[key] = old

    def test_all_parts_need_same_single_verified_non_play_signer(self):
        self.runner.certs["base.apk"] = fixture.certs((("ef" * 20, "ef" * 32),))
        with self.assertRaises(um.MetadataError):
            self.release()
        self.runner.certs["base.apk"] = fixture.certs((("cd" * 20, MOD_CERT), ("ef" * 20, "ef" * 32)))
        with self.assertRaises(um.MetadataError):
            self.release()
        for name in APK_NAMES:
            self.runner.certs[name] = fixture.certs()
        with self.assertRaises(um.MetadataError):
            self.release()
        self.runner.unverifiable.add("base.apk")
        with self.assertRaises(ca.Incompatible):
            self.release()

    def test_malformed_report_or_duplicate_apks_rejected(self):
        for bad in ({**self.report, "compatible": False}, {**self.report, "apks": ["../base.apk"]},
                    {**self.report, "apks": ["base.apk", "base.apk"]}, {**self.report, "version_code": True}):
            with self.subTest(report=bad):
                self.report_path.write_text(json.dumps(bad))
                with self.assertRaises(um.MetadataError):
                    um.load_report(self.report_path, self.baseline)

    def test_local_build_cannot_be_published(self):
        doc = um.identity_from_env({}, fixture.PKG, 10, "1.0", APK_NAMES, head=lambda: REVISION)
        for name in APK_NAMES:
            rewrite(self.dir / name, {um.BUILD_INFO: um.encode_identity(doc)})
        with self.assertRaises(um.MetadataError):
            self.release()

    def test_cli_release_is_atomic_and_does_not_rewrite_failed_manifest(self):
        baseline = self.tmp / "baseline.json"
        baseline.write_text(json.dumps(fixture.baseline_doc()))
        args = ["release", "--release-dir", str(self.dir), "--report", str(self.report_path)]
        with patch.object(ca, "find_build_tools", return_value=self.tools), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(um.main(args, self.runner, {}, baseline), 0)
        output = self.dir / um.UPDATE_MANIFEST
        raw = output.read_bytes()
        self.assertEqual(json.loads(raw), self.release())
        rewrite(self.dir / "split_UnityDataAssetPack.apk", remove=(um.BUILD_INFO,))
        with patch.object(ca, "find_build_tools", return_value=self.tools), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(um.main(args, self.runner, {}, baseline), 1)
        self.assertEqual(output.read_bytes(), raw)
        self.assertEqual(sorted(p.name for p in self.dir.iterdir() if p.name.startswith(".update.json")), [])

    def test_release_refuses_sizes_beyond_android_consumers_file_and_total_limits(self):
        with patch.object(um, "MAX_APK_SIZE", 32), self.assertRaises(um.MetadataError):
            self.release()
        with patch.object(um, "MAX_TOTAL_SIZE", 32), self.assertRaises(um.MetadataError):
            self.release()


if __name__ == "__main__":
    unittest.main()
