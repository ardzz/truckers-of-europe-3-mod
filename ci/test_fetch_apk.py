"""Offline regression tests for ci/fetch_apk.py (synthetic fixtures, no network)."""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import shutil
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

import check_apks
import fetch_apk
from fetch_apk import FetchError, ProtoError, Target, Variant, VariantError

PACKAGE = "com.WandaSoftware.TruckersofEurope3"
CERT = "5e" * 20  # synthetic signer digest; real values live only in compatibility.json
ASSET_PACK = "UnityDataAssetPack"
TARGET = Target(PACKAGE, CERT, (ASSET_PACK,))
TOKEN = "SECRETTOKEN"
URL = f"https://download.pureapk.com/b/XAPK/abc?_fn=x&k={TOKEN}"
ALT_URL = f"https://data.winudf.com/XAPK/abc?k={TOKEN}"


def baseline_doc(**overrides) -> dict:
    """A minimal valid check_apks baseline with synthetic values."""
    doc = {
        "schema": 1,
        "package": PACKAGE,
        "signer_cert_sha1": CERT,
        "signer_cert_sha256": "5e" * 32,
        "reviewed_build": {"version_name": "0.7.64", "version_code": 193},
        "pinned_entries": {
            check_apks.NATIVE_LIB: {"sha256": "11" * 32, "size": 1},
            check_apks.METADATA: {"sha256": "22" * 32, "size": 1},
        },
        "required_splits": {ASSET_PACK: ["assets/aa/catalog.bundle"]},
        "notes": ["synthetic"],
    }
    doc.update(overrides)
    return doc


# --- protobuf fixtures --------------------------------------------------------

def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        low, n = n & 0x7F, n >> 7
        if n:
            out.append(low | 0x80)
        else:
            out.append(low)
            return bytes(out)


def field(number: int, value: int | str | bytes) -> bytes:
    if isinstance(value, int):
        return varint(number << 3) + varint(value)
    if isinstance(value, str):
        value = value.encode()
    return varint(number << 3 | 2) + varint(len(value)) + value


def app_entry(code, name, *, package=PACKAGE, cert=CERT, sha1="ab" * 20, size=1000, kind="XAPK", urls=(URL, ALT_URL)):
    download = field(3, sha1) + field(4, size) + field(8, kind)
    download += b"".join(field(number, url) for number, url in zip((9, 10), urls))
    app = field(4, package) + field(5, str(code)) + field(6, name) + field(7, cert) + field(24, download)
    return field(2, field(1, "version_list") + field(3, field(2, app)))


def response(*entries: bytes, status: bool = True) -> bytes:
    data = field(1, field(7, field(1, b"\x10\x01") + b"".join(entries)))
    if status:
        data += field(2, field(2, "INVALID_COMMAND") + field(3, "SUCCESS"))
    return data


def variant(index, code, name, kind="XAPK", sha1="ab" * 20, size=1000) -> Variant:
    return Variant(index, PACKAGE, name, code, CERT, kind, sha1, size, (URL,))


# --- ZIP fixtures -------------------------------------------------------------

def apk_bytes(*entries: str) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as apk:
        apk.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00synthetic")
        for name in entries:
            apk.writestr(name, b"synthetic " + name.encode())
    return buf.getvalue()


BASE = apk_bytes("classes.dex", "assets/bin/Data/Managed/Metadata/global-metadata.dat")
BASE_ARM64 = apk_bytes("classes.dex", "lib/arm64-v8a/libil2cpp.so")
ARM64 = apk_bytes("lib/arm64-v8a/libil2cpp.so", "lib/arm64-v8a/libunity.so")
ARMV7 = apk_bytes("lib/armeabi-v7a/libil2cpp.so")
ASSETS = apk_bytes("assets/aa/catalog.bundle")
ASSETS_WITH_LIB = apk_bytes("assets/aa/catalog.bundle", "lib/arm64-v8a/libil2cpp.so")
DENSITY = apk_bytes("res/drawable-xxhdpi/icon.png")
BASE_LEAF = f"{PACKAGE}.apk"
ASSET_LEAF = f"{ASSET_PACK}.apk"


def manifest(*splits: tuple[str, str], **overrides) -> dict:
    data = {
        "xapk_version": 2,
        "package_name": PACKAGE,
        "version_code": "193",
        "version_name": "0.7.64",
        "split_apks": [{"file": leaf, "id": split_id} for split_id, leaf in splits],
    }
    data.update(overrides)
    return data


def write_xapk(path: Path, members, manifest_data, compression=zipfile.ZIP_STORED) -> Path:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")  # duplicate-name fixtures
        with zipfile.ZipFile(path, "w", compression) as archive:
            if manifest_data is not None:
                archive.writestr("manifest.json", json.dumps(manifest_data))
            for name, data in members:
                archive.writestr(name, data)
    return path


def variant_for(path: Path, index=0, kind="XAPK", code=193, name="0.7.64") -> Variant:
    data = path.read_bytes()
    return Variant(index, PACKAGE, name, code, CERT, kind, hashlib.sha1(data).hexdigest(), len(data), (URL,))


STANDARD_SPLITS = (("base", BASE_LEAF), (ASSET_PACK, ASSET_LEAF))
ARM64_SPLITS = STANDARD_SPLITS + (("config.arm64_v8a", "config.arm64_v8a.apk"),)


def standard_members(*extra):
    return [(BASE_LEAF, BASE), (ASSET_LEAF, ASSETS), *extra]


def complete_xapk(path: Path) -> Path:
    return write_xapk(path, standard_members(("config.arm64_v8a.apk", ARM64)), manifest(*ARM64_SPLITS))


class FakeCurl:
    """Stands in for subprocess.run: serves files by URL, records argv."""

    def __init__(self, routes):
        self.routes = routes  # url -> (returncode, write_out, stderr, body or None)
        self.calls = []

    def __call__(self, cmd, **kwargs):
        self.calls.append((list(cmd), kwargs))
        url = cmd[cmd.index("--url") + 1]
        returncode, write_out, stderr, body = self.routes[url]
        if body is not None:
            Path(cmd[cmd.index("--output") + 1]).write_bytes(body)
        return subprocess.CompletedProcess(cmd, returncode, write_out, stderr)


class Case(unittest.TestCase):
    def setUp(self):
        self.stderr = io.StringIO()
        redirect = contextlib.redirect_stderr(self.stderr)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)
        self.tmp = Path(tempfile.mkdtemp(prefix="test_fetch_apk-"))
        self.addCleanup(shutil.rmtree, self.tmp)


# --- tests --------------------------------------------------------------------

class TargetTest(Case):
    def test_invalid_baseline_is_fetch_error(self):
        path = self.tmp / "compatibility.json"
        doc = baseline_doc()
        del doc["required_splits"]
        path.write_text(json.dumps(doc))
        with self.assertRaisesRegex(FetchError, "required_splits"):
            fetch_apk.load_target(path)


class ParseVersionsTest(Case):
    def test_reads_variant_metadata(self):
        [parsed] = fetch_apk.parse_versions(response(app_entry(193, "0.7.64", sha1="AB" * 20, size=753661909)), TARGET)
        self.assertEqual(parsed.package, PACKAGE)
        self.assertEqual(parsed.version_code, 193)
        self.assertEqual(parsed.version_name, "0.7.64")
        self.assertEqual(parsed.archive_type, "XAPK")
        self.assertEqual(parsed.archive_sha1, "ab" * 20)
        self.assertEqual(parsed.size, 753661909)
        self.assertEqual(parsed.urls, (URL, ALT_URL))
        self.assertNotIn(TOKEN, repr(parsed))

    def test_every_truncation_is_rejected(self):
        data = response(app_entry(193, "0.7.64"), app_entry(182, "0.7.1"), status=False)
        for cut in range(len(data)):
            with self.subTest(cut=cut), self.assertRaises(ProtoError):
                fetch_apk.parse_versions(data[:cut], TARGET)

    def test_overlong_varint_rejected(self):
        with self.assertRaises(ProtoError):
            fetch_apk.parse_message(b"\xff" * 11 + b"\x00")

    def test_group_wire_type_rejected(self):
        with self.assertRaises(ProtoError):
            fetch_apk.parse_message(bytes([1 << 3 | 3]))

    def test_non_decimal_version_code_rejected(self):
        for code in ("19a", "-193", "\uff11\uff19\uff13", ""):
            with self.subTest(code=code), self.assertRaises(ProtoError):
                fetch_apk.parse_versions(response(app_entry(code, "0.7.64")), TARGET)

    def test_unsafe_version_name_rejected(self):
        with self.assertRaises(ProtoError):
            fetch_apk.parse_versions(response(app_entry(193, "0.7.64; rm -rf /")), TARGET)

    def test_wrong_package_ignored_even_with_higher_version_code(self):
        data = response(
            app_entry(999, "9.9.9", package=PACKAGE + ".evil"),
            app_entry(500, "5.0.0", package="com.other.game"),
            app_entry(193, "0.7.64"),
        )
        variants = fetch_apk.parse_versions(data, TARGET)
        self.assertEqual([(v.package, v.version_code) for v in variants], [(PACKAGE, 193)])
        self.assertEqual(fetch_apk.select_version(variants)[0].version_code, 193)

    def test_foreign_certificate_ignored(self):
        data = response(app_entry(194, "0.7.65", cert="00" * 20), app_entry(193, "0.7.64"))
        self.assertEqual([v.version_code for v in fetch_apk.parse_versions(data, TARGET)], [193])

    def test_disallowed_download_urls_dropped(self):
        urls = (f"http://download.pureapk.com/x?k={TOKEN}", f"https://127.0.0.1/x?k={TOKEN}")
        [parsed] = fetch_apk.parse_versions(response(app_entry(193, "0.7.64", urls=urls)), TARGET)
        self.assertEqual(parsed.urls, ())
        self.assertNotIn(TOKEN, self.stderr.getvalue())

    def test_missing_download_object_rejected(self):
        app = field(4, PACKAGE) + field(5, "193") + field(6, "0.7.64") + field(7, CERT)
        entry = field(2, field(1, "version_list") + field(3, field(2, app)))
        with self.assertRaises(ProtoError):
            fetch_apk.parse_versions(response(entry), TARGET)


class SelectVersionTest(Case):
    def test_highest_integer_version_code_wins_over_lexical_order(self):
        variants = [variant(0, 99, "0.9.9"), variant(1, 100, "0.10.0"), variant(2, 98, "0.9.8")]
        chosen = fetch_apk.select_version(variants)
        self.assertEqual([(v.version_code, v.version_name) for v in chosen], [(100, "0.10.0")])

    def test_keeps_every_variant_of_latest_code_in_api_order(self):
        variants = [variant(3, 193, "0.7.64"), variant(1, 182, "0.7.1"), variant(0, 193, "0.7.64"), variant(2, 193, "0.7.64")]
        self.assertEqual([v.index for v in fetch_apk.select_version(variants)], [0, 2, 3])

    def test_exact_version_name(self):
        variants = [variant(0, 100, "0.10.0"), variant(1, 99, "0.9.9")]
        self.assertEqual(fetch_apk.select_version(variants, "0.9.9")[0].version_code, 99)
        with self.assertRaisesRegex(FetchError, "0.9.10"):
            fetch_apk.select_version(variants, "0.9.10")

    def test_conflicting_names_for_one_code_rejected(self):
        with self.assertRaisesRegex(FetchError, "several versionNames"):
            fetch_apk.select_version([variant(0, 193, "0.7.64"), variant(1, 193, "0.7.64b")])

    def test_list_records_are_token_free_and_numeric(self):
        records = fetch_apk.list_records([variant(0, 99, "0.9.9"), variant(1, 100, "0.10.0")])
        text = json.dumps(records)
        self.assertNotIn(TOKEN, text)
        self.assertNotIn("http", text)
        self.assertEqual(max(records, key=lambda r: r["version_code"])["version_name"], "0.10.0")
        self.assertEqual([r["latest"] for r in records], [True, False])
        with self.assertRaises(FetchError):
            fetch_apk.list_records([variant(0, 99, "0.9.9")], "1.0")


class CheckUrlTest(Case):
    def test_accepts_apkpure_hosts(self):
        self.assertEqual(fetch_apk.check_url(URL), "download.pureapk.com")
        self.assertEqual(fetch_apk.check_url(ALT_URL), "data.winudf.com")
        self.assertEqual(fetch_apk.check_url("https://DATA.winudf.com:443/x"), "data.winudf.com")

    def test_rejects_unexpected_targets_without_echoing_tokens(self):
        for url in (
            f"http://download.pureapk.com/x?k={TOKEN}",
            f"https://evilpureapk.com/x?k={TOKEN}",
            f"https://pureapk.com.evil.net/x?k={TOKEN}",
            f"https://user:pw@data.winudf.com/x?k={TOKEN}",
            f"https://127.0.0.1/x?k={TOKEN}",
            f"https://[::1]/x?k={TOKEN}",
            f"https://localhost/x?k={TOKEN}",
            f"https://data.winudf.com:8443/x?k={TOKEN}",
            f"https://data.winudf.com/a b?k={TOKEN}",
            f"https://evil.com%2f.winudf.com/x?k={TOKEN}",
            f"file:///etc/passwd?k={TOKEN}",
        ):
            with self.subTest(url=url), self.assertRaises(FetchError) as caught:
                fetch_apk.check_url(url)
            self.assertNotIn(TOKEN, str(caught.exception))


class CurlFetchTest(Case):
    def test_follows_redirect_to_permitted_host(self):
        curl = FakeCurl({
            URL: (0, f"302 {ALT_URL}", "", b"<html>moved</html>"),
            ALT_URL: (0, "200 ", "", b"payload"),
        })
        dest = self.tmp / "out.bin"
        fetch_apk.curl_fetch(URL, dest, resolves=["api.pureapk.com:443:104.20.24.85"], max_time=60, runner=curl)
        self.assertEqual(dest.read_bytes(), b"payload")
        self.assertNotIn(TOKEN, self.stderr.getvalue())

    def test_refuses_redirect_to_private_host_before_requesting_it(self):
        curl = FakeCurl({URL: (0, f"302 https://127.0.0.1/steal?k={TOKEN}", "", b"")})
        with self.assertRaises(FetchError) as caught:
            fetch_apk.curl_fetch(URL, self.tmp / "out.bin", max_time=60, runner=curl)
        self.assertEqual(len(curl.calls), 1)
        self.assertNotIn(TOKEN, str(caught.exception))

    def test_refuses_redirect_to_plain_http(self):
        curl = FakeCurl({URL: (0, "301 http://data.winudf.com/x", "", b"")})
        with self.assertRaises(FetchError):
            fetch_apk.curl_fetch(URL, self.tmp / "out.bin", max_time=60, runner=curl)
        self.assertEqual(len(curl.calls), 1)

    def test_curl_failure_message_redacts_signed_query(self):
        curl = FakeCurl({URL: (22, "403 ", f"curl: (22) The requested URL returned error: 403 {URL}", None)})
        with self.assertRaises(FetchError) as caught:
            fetch_apk.curl_fetch(URL, self.tmp / "out.bin", max_time=60, runner=curl)
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertIn("exit 22", str(caught.exception))

    def test_non_200_final_status_rejected(self):
        curl = FakeCurl({URL: (0, "206 ", "", b"partial")})
        with self.assertRaisesRegex(FetchError, "206"):
            fetch_apk.curl_fetch(URL, self.tmp / "out.bin", max_time=60, runner=curl)

    def test_download_falls_back_to_alternative_url(self):
        curl = FakeCurl({URL: (22, "404 ", "curl: (22) error", None), ALT_URL: (0, "200 ", "", b"archive")})
        target = Variant(0, PACKAGE, "0.7.64", 193, CERT, "XAPK", "ab" * 20, 7, (URL, ALT_URL))
        fetch_apk.download_variant(target, self.tmp / "a", [], runner=curl)
        self.assertEqual((self.tmp / "a").read_bytes(), b"archive")

    def test_download_without_working_url_is_variant_error(self):
        curl = FakeCurl({URL: (6, "000 ", "curl: (6) Could not resolve host", None)})
        with self.assertRaises(VariantError):
            fetch_apk.download_variant(variant(0, 193, "0.7.64"), self.tmp / "a", [], runner=curl)
        with self.assertRaises(VariantError):
            fetch_apk.download_variant(
                Variant(0, PACKAGE, "0.7.64", 193, CERT, "XAPK", "ab" * 20, 7, ()), self.tmp / "b", [], runner=curl
            )


class PrepareVariantTest(Case):
    def setUp(self):
        super().setUp()
        self.stage = self.tmp / "stage"
        self.stage.mkdir()

    def prepare(self, members, manifest_data, required=TARGET.required_splits, **kwargs):
        path = write_xapk(self.tmp / "v.xapk", members, manifest_data, kwargs.pop("compression", zipfile.ZIP_STORED))
        return fetch_apk.prepare_variant(path, variant_for(path, **kwargs), self.stage, required)

    def assertRejected(self, pattern, members, manifest_data, **kwargs):
        with self.assertRaisesRegex(VariantError, pattern):
            self.prepare(members, manifest_data, **kwargs)

    def test_normalises_names_and_drops_foreign_abi_splits(self):
        members = standard_members(
            ("config.arm64_v8a.apk", ARM64), ("config.armeabi_v7a.apk", ARMV7),
            ("config.xxhdpi.apk", DENSITY), ("icon.png", b"png"),
        )
        splits = ARM64_SPLITS + (
            ("config.armeabi_v7a", "config.armeabi_v7a.apk"), ("config.xxhdpi", "config.xxhdpi.apk"),
        )
        files, dropped = self.prepare(members, manifest(*splits), compression=zipfile.ZIP_DEFLATED)
        expected = {
            "base.apk": BASE,
            f"split_{ASSET_PACK}.apk": ASSETS,
            "split_config.arm64_v8a.apk": ARM64,
            "split_config.xxhdpi.apk": DENSITY,
        }
        self.assertEqual(dropped, ["config.armeabi_v7a"])
        self.assertEqual(files[0]["name"], "base.apk")
        self.assertEqual({f["name"] for f in files}, set(expected))
        self.assertEqual(sorted(p.name for p in self.stage.iterdir()), sorted(expected))
        for record in files:
            data = (self.stage / record["name"]).read_bytes()
            self.assertEqual(data, expected[record["name"]])
            self.assertEqual(record["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(record["size"], len(data))

    def test_missing_required_asset_pack_rejected_before_extraction(self):
        self.assertRejected(
            f"required split.*{ASSET_PACK}",
            [(BASE_LEAF, BASE), ("config.arm64_v8a.apk", ARM64)],
            manifest(("base", BASE_LEAF), ("config.arm64_v8a", "config.arm64_v8a.apk")),
        )
        self.assertEqual(list(self.stage.iterdir()), [])

    def test_required_splits_come_from_the_caller(self):
        members = standard_members(("config.arm64_v8a.apk", ARM64))
        self.assertRejected("OtherPack", members, manifest(*ARM64_SPLITS), required=(ASSET_PACK, "OtherPack"))
        files, _ = self.prepare(members, manifest(*ARM64_SPLITS), required=())
        self.assertIn(f"split_{ASSET_PACK}.apk", [f["name"] for f in files])

    def test_variant_without_arm64_rejected_before_asset_pack_extraction(self):
        self.assertRejected(
            "arm64", standard_members(("config.armeabi_v7a.apk", ARMV7)),
            manifest(*STANDARD_SPLITS, ("config.armeabi_v7a", "config.armeabi_v7a.apk")),
        )
        self.assertEqual([p.name for p in self.stage.iterdir()], ["base.apk"])

    def test_arm64_library_in_asset_pack_does_not_count(self):
        members = [(BASE_LEAF, BASE), (ASSET_LEAF, ASSETS_WITH_LIB)]
        self.assertRejected("arm64", members, manifest(*STANDARD_SPLITS))

    def test_arm64_library_in_base_accepted(self):
        files, _ = self.prepare([(BASE_LEAF, BASE_ARM64), (ASSET_LEAF, ASSETS)], manifest(*STANDARD_SPLITS))
        self.assertEqual([f["name"] for f in files], ["base.apk", f"split_{ASSET_PACK}.apk"])

    def test_manifest_path_traversal_rejected(self):
        escape = self.tmp / "evil.apk"
        self.assertRejected(
            "unsafe APK name", standard_members(("../evil.apk", ARM64)),
            manifest(*STANDARD_SPLITS, ("config.arm64_v8a", "../evil.apk")),
        )
        self.assertRejected(
            "unsafe split id", standard_members(("config.arm64_v8a.apk", ARM64)),
            manifest(*STANDARD_SPLITS, ("../../evil", "config.arm64_v8a.apk")),
        )
        self.assertFalse(escape.exists())
        self.assertEqual(list(self.stage.iterdir()), [])

    def test_apks_outside_manifest_rejected(self):
        for name in ("sub/../../evil.apk", "nested/config.arm64_v8a.apk", "extra.apk"):
            with self.subTest(name=name):
                self.assertRejected(
                    "not described by manifest.json",
                    standard_members(("config.arm64_v8a.apk", ARM64), (name, ARM64)),
                    manifest(*ARM64_SPLITS),
                )

    def test_obb_expansions_rejected(self):
        obb = f"Android/obb/{PACKAGE}/main.193.{PACKAGE}.obb"
        expansions = [{"file": obb, "install_location": "EXTERNAL_STORAGE", "install_path": obb}]
        complete = standard_members(("config.arm64_v8a.apk", ARM64))
        self.assertRejected("OBB", complete + [(obb, b"obb")], manifest(*ARM64_SPLITS, expansions=expansions))
        self.assertRejected("OBB", complete + [(obb, b"obb")], manifest(*ARM64_SPLITS))
        self.assertRejected("OBB", complete, manifest(*ARM64_SPLITS, expansions=expansions))

    def test_archive_integrity_mismatch_rejected(self):
        path = complete_xapk(self.tmp / "v.xapk")
        good = variant_for(path)
        wrong_size = Variant(0, PACKAGE, "0.7.64", 193, CERT, "XAPK", good.archive_sha1, good.size + 1, ())
        wrong_sha1 = Variant(0, PACKAGE, "0.7.64", 193, CERT, "XAPK", "00" * 20, good.size, ())
        with self.assertRaisesRegex(VariantError, "bytes"):
            fetch_apk.prepare_variant(path, wrong_size, self.stage, TARGET.required_splits)
        with self.assertRaisesRegex(VariantError, "SHA-1"):
            fetch_apk.prepare_variant(path, wrong_sha1, self.stage, TARGET.required_splits)
        self.assertEqual(list(self.stage.iterdir()), [])

    def test_manifest_identity_mismatch_rejected(self):
        members = standard_members(("config.arm64_v8a.apk", ARM64))
        self.assertRejected("package", members, manifest(*ARM64_SPLITS, package_name="com.other.game"))
        self.assertRejected("version_code", members, manifest(*ARM64_SPLITS, version_code="192"))
        self.assertRejected("version_name", members, manifest(*ARM64_SPLITS, version_name="0.7.63"))
        self.assertRejected("manifest.json", members, None)
        self.assertRejected("split_apks", members, manifest(split_apks=None))

    def test_ambiguous_layouts_rejected(self):
        self.assertRejected(
            "duplicate entry", standard_members((BASE_LEAF, BASE_ARM64)), manifest(*STANDARD_SPLITS)
        )
        self.assertRejected(
            "duplicate splits", standard_members(("other.apk", BASE)),
            manifest(*STANDARD_SPLITS, ("base", "other.apk")),
        )
        self.assertRejected("exactly one base", [(BASE_LEAF, BASE_ARM64), (ASSET_LEAF, ASSETS)],
                            manifest(("main", BASE_LEAF), (ASSET_PACK, ASSET_LEAF)))
        self.assertRejected("missing from archive", standard_members(), manifest(*STANDARD_SPLITS, ("x", "absent.apk")))
        self.assertRejected("not a valid APK", [(BASE_LEAF, b"not a zip"), (ASSET_LEAF, ASSETS)],
                            manifest(*STANDARD_SPLITS))

    def test_non_xapk_variant_rejected(self):
        path = self.tmp / "universal.apk"
        path.write_bytes(BASE_ARM64)
        for kind in ("APK", "OBB"):
            with self.subTest(kind=kind), self.assertRaisesRegex(VariantError, "only XAPK"):
                fetch_apk.prepare_variant(path, variant_for(path, kind=kind), self.stage, TARGET.required_splits)
        self.assertEqual(list(self.stage.iterdir()), [])


class FetchIntoTest(Case):
    def setUp(self):
        super().setUp()
        self.out = self.tmp / "dist" / "toe3"
        self.armv7 = write_xapk(
            self.tmp / "armv7.xapk", standard_members(("config.armeabi_v7a.apk", ARMV7)),
            manifest(*STANDARD_SPLITS, ("config.armeabi_v7a", "config.armeabi_v7a.apk")),
        )
        self.no_pack = write_xapk(
            self.tmp / "no_pack.xapk", [(BASE_LEAF, BASE), ("config.arm64_v8a.apk", ARM64)],
            manifest(("base", BASE_LEAF), ("config.arm64_v8a", "config.arm64_v8a.apk")),
        )
        self.arm64 = complete_xapk(self.tmp / "arm64.xapk")
        self.downloads = []

    def downloader(self, sources):
        def download(target: Variant, dest: Path) -> None:
            self.downloads.append(target.index)
            shutil.copyfile(sources[target.index], dest)
        return download

    def leftovers(self):
        return [p.name for p in self.out.parent.iterdir() if p.name.startswith(".fetch_apk-")]

    def assertPublished(self, expected: Variant, record: dict):
        self.assertEqual(
            sorted(p.name for p in self.out.iterdir()),
            ["base.apk", "source.json", f"split_{ASSET_PACK}.apk", "split_config.arm64_v8a.apk"],
        )
        source = json.loads((self.out / "source.json").read_text())
        self.assertEqual(source, record)
        self.assertEqual((source["package"], source["version_name"], source["version_code"]), (PACKAGE, "0.7.64", 193))
        self.assertEqual(source["source"], "apkpure")
        self.assertEqual(source["source_api"], TARGET.api_url)
        self.assertEqual(source["archive"]["sha1"], expected.archive_sha1)
        for entry in source["files"]:
            self.assertEqual(entry["sha256"], hashlib.sha256((self.out / entry["name"]).read_bytes()).hexdigest())
        self.assertNotIn(TOKEN, (self.out / "source.json").read_text())
        self.assertNotIn(TOKEN, self.stderr.getvalue())
        self.assertEqual(self.leftovers(), [])

    def test_tries_next_variant_of_same_code_when_first_lacks_arm64(self):
        variants = [variant_for(self.armv7, index=0), variant_for(self.arm64, index=1)]
        record = fetch_apk.fetch_into(variants, self.out, self.downloader({0: self.armv7, 1: self.arm64}), TARGET)
        self.assertEqual(self.downloads, [0, 1])
        self.assertPublished(variants[1], record)

    def test_skips_standalone_and_pack_less_candidates_for_complete_set(self):
        standalone = Variant(0, PACKAGE, "0.7.64", 193, CERT, "APK", "cd" * 20, 4096, (URL,))
        variants = [standalone, variant_for(self.no_pack, index=1), variant_for(self.arm64, index=2)]
        record = fetch_apk.fetch_into(variants, self.out, self.downloader({1: self.no_pack, 2: self.arm64}), TARGET)
        self.assertEqual(self.downloads, [1, 2])  # the standalone APK is never downloaded
        self.assertPublished(variants[2], record)

    def test_failure_publishes_nothing(self):
        variants = [variant_for(self.armv7, index=0), variant_for(self.no_pack, index=1)]
        with self.assertRaisesRegex(FetchError, "no usable arm64 variant for 0.7.64/193") as caught:
            fetch_apk.fetch_into(variants, self.out, self.downloader({0: self.armv7, 1: self.no_pack}), TARGET)
        self.assertIn(ASSET_PACK, str(caught.exception))
        self.assertFalse(self.out.exists())
        self.assertEqual(self.leftovers(), [])

    def test_nonempty_output_rejected_before_download(self):
        self.out.mkdir(parents=True)
        (self.out / "stale.apk").write_bytes(b"old")
        with self.assertRaisesRegex(FetchError, "must not exist"):
            fetch_apk.fetch_into([variant_for(self.arm64)], self.out, self.downloader({0: self.arm64}), TARGET)
        self.assertEqual(self.downloads, [])

    def test_empty_output_directory_is_filled(self):
        self.out.mkdir(parents=True)
        fetch_apk.fetch_into([variant_for(self.arm64)], self.out, self.downloader({0: self.arm64}), TARGET)
        self.assertTrue((self.out / "source.json").is_file())


class MainTest(Case):
    def setUp(self):
        super().setUp()
        self.baseline = self.tmp / "compatibility.json"
        self.baseline.write_text(json.dumps(baseline_doc()))

    def run_main(self, argv, routes):
        stdout = io.StringIO()
        curl = FakeCurl(routes)
        with contextlib.redirect_stdout(stdout):
            code = fetch_apk.main(argv, runner=curl, baseline_path=self.baseline)
        return code, stdout.getvalue(), curl

    def api_routes(self, *entries):
        return {TARGET.api_url: (0, "200 ", "", response(*entries))}

    def test_list_prints_token_free_numeric_json(self):
        routes = self.api_routes(
            app_entry(99, "0.9.9"), app_entry(100, "0.10.0"), app_entry(999, "9.9.9", package="com.other.game")
        )
        code, stdout, curl = self.run_main(["--list", "--resolve", "api.pureapk.com:443:104.20.24.85"], routes)
        self.assertEqual(code, 0)
        records = json.loads(stdout)
        latest = max(records, key=lambda r: r["version_code"])
        self.assertEqual((latest["version_name"], latest["version_code"]), ("0.10.0", 100))
        self.assertNotIn(TOKEN, stdout + self.stderr.getvalue())
        self.assertNotIn("http", stdout)

    def test_list_with_version_filters_and_unknown_version_fails(self):
        routes = self.api_routes(app_entry(99, "0.9.9"), app_entry(100, "0.10.0"))
        code, stdout, _ = self.run_main(["--list", "--version", "0.9.9"], routes)
        self.assertEqual((code, [r["version_code"] for r in json.loads(stdout)]), (0, [99]))
        code, stdout, _ = self.run_main(["--list", "--version", "0.9.10"], routes)
        self.assertEqual((code, stdout), (1, ""))

    def test_invalid_baseline_fails_before_network(self):
        self.baseline.write_text(json.dumps(baseline_doc(required_splits={})))
        code, stdout, curl = self.run_main(["--list"], {})
        self.assertEqual((code, stdout, curl.calls), (1, "", []))
        self.assertIn("required_splits", self.stderr.getvalue())

    def test_rejects_invalid_arguments(self):
        for argv in (
            [],
            ["--list", "--output", str(self.tmp / "o")],
            ["--list", "--version", "1; rm -rf /"],
            ["--list", "--resolve", "api.pureapk.com:80:104.20.24.85"],
        ):
            with self.subTest(argv=argv), self.assertRaises(SystemExit) as caught:
                self.run_main(argv, {})
            self.assertEqual(caught.exception.code, 2)

    def test_fetches_requested_version_end_to_end(self):
        no_pack = write_xapk(
            self.tmp / "no_pack.xapk", [(BASE_LEAF, BASE), ("config.arm64_v8a.apk", ARM64)],
            manifest(("base", BASE_LEAF), ("config.arm64_v8a", "config.arm64_v8a.apk")),
        )
        arm64 = complete_xapk(self.tmp / "arm64.xapk")
        incomplete, complete = variant_for(no_pack), variant_for(arm64)
        urls = {name: f"https://download.pureapk.com/b/XAPK/{name}?_fn=f&k={TOKEN}" for name in ("a", "b")}
        cdn = f"https://data.winudf.com/XAPK/b?k={TOKEN}"
        routes = self.api_routes(
            app_entry(194, "0.7.65", urls=()),
            app_entry(193, "0.7.64", sha1=incomplete.archive_sha1, size=incomplete.size, urls=(urls["a"],)),
            app_entry(193, "0.7.64", sha1=complete.archive_sha1, size=complete.size, urls=(urls["b"],)),
        )
        routes[urls["a"]] = (0, "200 ", "", no_pack.read_bytes())
        routes[urls["b"]] = (0, f"302 {cdn}", "", b"")
        routes[cdn] = (0, "200 ", "", arm64.read_bytes())
        out = self.tmp / "release"
        code, stdout, _ = self.run_main(["--output", str(out), "--version", "0.7.64"], routes)
        self.assertEqual(code, 0, self.stderr.getvalue())
        record = json.loads(stdout)
        self.assertEqual(record, json.loads((out / "source.json").read_text()))
        self.assertEqual((record["version_code"], record["archive"]["sha1"]), (193, complete.archive_sha1))
        self.assertNotIn(TOKEN, stdout + self.stderr.getvalue())
        self.assertTrue((out / f"split_{ASSET_PACK}.apk").is_file())


if __name__ == "__main__":
    unittest.main()
