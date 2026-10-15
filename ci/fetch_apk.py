#!/usr/bin/env python3
"""Fetch a genuine Truckers of Europe 3 arm64 split set from APKPure.

    python3 ci/fetch_apk.py --list [--version VERSION] [--resolve HOST:443:IP ...]
    python3 ci/fetch_apk.py --output DIR [--version VERSION] [--resolve HOST:443:IP ...]

Package, signer certificate and required split IDs come from the shared baseline
(compatibility.json via check_apks.load_baseline). --list prints a JSON array of
advertised variants (no download URLs) on stdout. Otherwise the highest integer
versionCode (or the exact --version name) is downloaded variant by variant until
one XAPK yields an arm64 set holding every required split; plain APK variants and
incomplete XAPKs are skipped in favour of the next variant of that versionCode.
DIR then holds base.apk, split_<id>.apk and source.json; DIR is published
atomically by renaming a fully prepared staging directory. The archive SHA-1 and
size are checked against the API metadata, which is only an integrity check:
signer and compatibility are verified by check_apks.py. Progress goes to stderr.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import zlib
from pathlib import Path
from typing import Callable, Iterable
from urllib.parse import urlsplit

from check_apks import BASELINE_PATH, Incompatible, load_baseline
from check_apks import NATIVE_LIB as ARM64_LIB

API_URL_TEMPLATE = "https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name={package}"
API_HEADERS = (
    "x-cv: 3172501",
    "x-sv: 29",
    "x-abis: arm64-v8a,armeabi-v7a,armeabi,x86,x86_64",
    "x-gp: 1",
)
ALLOWED_HOST_SUFFIXES = ("pureapk.com", "winudf.com")
ARM64_SPLIT = "config.arm64_v8a"
ABI_SPLITS = frozenset(
    f"config.{abi}"
    for abi in ("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64", "riscv64")
)

MAX_REDIRECTS = 5
MAX_API_BYTES = 32 << 20
MAX_MANIFEST_BYTES = 4 << 20
CHUNK = 1 << 20

VERSION_NAME_RE = re.compile(r"[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")
VERSION_CODE_RE = re.compile(r"[0-9]{1,10}")
SHA1_RE = re.compile(r"[0-9a-f]{40}")
HOST_RE = re.compile(r"[a-z0-9.-]+")
APK_LEAF_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}\.apk")
SPLIT_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
RESOLVE_RE = re.compile(r"[A-Za-z0-9.-]+:443:[0-9A-Fa-f.:\[\]]+")


class FetchError(Exception):
    """Fatal error: nothing is published."""


class ProtoError(FetchError):
    """API response is not the expected protobuf structure."""


class VariantError(FetchError):
    """This variant is unusable; the next variant of the same versionCode may be tried."""


def log(message: str) -> None:
    print(f"fetch_apk: {message}", file=sys.stderr, flush=True)


@dataclasses.dataclass(frozen=True)
class Target:
    """What to fetch, taken from the shared compatibility baseline."""

    package: str
    cert_sha1: str
    required_splits: tuple[str, ...]

    @property
    def api_url(self) -> str:
        return API_URL_TEMPLATE.format(package=self.package)


def load_target(path: Path = BASELINE_PATH) -> Target:
    try:
        baseline = load_baseline(path)
    except Incompatible as exc:
        raise FetchError(str(exc)) from None
    return Target(baseline.package, baseline.signer_sha1, tuple(sorted(baseline.required_splits)))


# --- protobuf wire format -------------------------------------------------

def _varint(buf: bytes, pos: int) -> tuple[int, int]:
    result = 0
    for shift in range(0, 70, 7):
        if pos >= len(buf):
            raise ProtoError("truncated varint")
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
    raise ProtoError("varint longer than 10 bytes")


def parse_message(buf: bytes) -> dict[int, list[int | bytes]]:
    """Decode one protobuf message into {field number: [values]} (no schema)."""
    fields: dict[int, list[int | bytes]] = {}
    pos = 0
    while pos < len(buf):
        key, pos = _varint(buf, pos)
        number, wire = key >> 3, key & 7
        if number == 0:
            raise ProtoError("field number 0")
        if wire == 0:
            value, pos = _varint(buf, pos)
        elif wire in (1, 2, 5):
            if wire == 2:
                length, pos = _varint(buf, pos)
            else:
                length = 8 if wire == 1 else 4
            end = pos + length
            if end > len(buf):
                raise ProtoError(f"field {number} truncated ({length} bytes declared, {len(buf) - pos} left)")
            value, pos = buf[pos:end], end
        else:
            raise ProtoError(f"unsupported wire type {wire} for field {number}")
        fields.setdefault(number, []).append(value)
    return fields


def _one(fields: dict, number: int, what: str) -> int | bytes:
    values = fields.get(number, [])
    if len(values) != 1:
        raise ProtoError(f"{what}: expected one field {number}, found {len(values)}")
    return values[0]


def _bytes(fields: dict, number: int, what: str) -> bytes:
    value = _one(fields, number, what)
    if not isinstance(value, bytes):
        raise ProtoError(f"{what}: field {number} is not length-delimited")
    return value


def _str(fields: dict, number: int, what: str) -> str:
    try:
        return _bytes(fields, number, what).decode("utf-8")
    except UnicodeDecodeError:
        raise ProtoError(f"{what}: field {number} is not UTF-8") from None


def _msg(fields: dict, number: int, what: str) -> dict:
    return parse_message(_bytes(fields, number, what))


def _int(fields: dict, number: int, what: str) -> int:
    value = _one(fields, number, what)
    if not isinstance(value, int):
        raise ProtoError(f"{what}: field {number} is not a varint")
    return value


# --- API metadata ---------------------------------------------------------

@dataclasses.dataclass(frozen=True)
class Variant:
    index: int
    package: str
    version_name: str
    version_code: int
    cert_sha1: str
    archive_type: str
    archive_sha1: str
    size: int
    # Signed download URLs carry tokens: keep them out of repr/logs/JSON.
    urls: tuple[str, ...] = dataclasses.field(repr=False, compare=False)

    def describe(self) -> str:
        return f"#{self.index} {self.archive_type} {self.size} bytes sha1 {self.archive_sha1}"


def parse_versions(data: bytes, target: Target) -> list[Variant]:
    """Extract the target package's variants from an app_version API response."""
    root = parse_message(data)
    listing = _msg(_msg(root, 1, "response"), 7, "response.data")
    variants: list[Variant] = []
    for index, raw_entry in enumerate(listing.get(2, [])):
        if not isinstance(raw_entry, bytes):
            raise ProtoError(f"entry {index}: not length-delimited")
        entry = parse_message(raw_entry)
        if 3 not in entry:
            continue
        body = _msg(entry, 3, f"entry {index}")
        if 2 not in body:
            continue
        app = _msg(body, 2, f"entry {index} app")
        where = f"entry {index}"
        if 4 not in app:
            log(f"ignoring {where}: no package name")
            continue
        package = _str(app, 4, where)
        if package != target.package:
            log(f"ignoring {where}: package {package!r}")
            continue
        code = _str(app, 5, where)
        if not VERSION_CODE_RE.fullmatch(code):
            raise ProtoError(f"{where}: versionCode {code!r} is not a decimal integer")
        name = _str(app, 6, where)
        if not VERSION_NAME_RE.fullmatch(name):
            raise ProtoError(f"{where}: unsafe versionName {name!r}")
        cert = _str(app, 7, where).lower()
        download = _msg(app, 24, f"{where} download")
        sha1 = _str(download, 3, f"{where} download").lower()
        if not SHA1_RE.fullmatch(sha1):
            raise ProtoError(f"{where}: archive SHA-1 {sha1!r} malformed")
        size = _int(download, 4, f"{where} download")
        if size <= 0:
            raise ProtoError(f"{where}: archive size {size}")
        archive_type = _str(download, 8, f"{where} download")
        if cert != target.cert_sha1:
            log(f"ignoring {where} {name}/{code}: advertised certificate {cert!r} is not the baseline signer")
            continue
        urls = []
        for number in (9, 10):
            if number not in download:
                continue
            url = _str(download, number, f"{where} download")
            try:
                check_url(url)
            except FetchError as exc:
                log(f"{where}: dropping download URL: {exc}")
                continue
            urls.append(url)
        variants.append(Variant(index, package, name, int(code), cert, archive_type, sha1, size, tuple(urls)))
    return variants


def _pool(variants: Iterable[Variant], version: str | None) -> list[Variant]:
    pool = [v for v in variants if version is None or v.version_name == version]
    if not pool:
        if version is None:
            raise FetchError("API offers no variants of the baseline package")
        raise FetchError(f"API offers no variant with versionName {version!r}")
    return pool


def select_version(variants: Iterable[Variant], version: str | None = None) -> list[Variant]:
    """Variants of the highest integer versionCode (optionally of an exact versionName), API order."""
    pool = _pool(variants, version)
    code = max(v.version_code for v in pool)
    chosen = sorted((v for v in pool if v.version_code == code), key=lambda v: v.index)
    names = sorted({v.version_name for v in chosen})
    if len(names) != 1:
        raise FetchError(f"versionCode {code} advertised with several versionNames: {names}")
    return chosen


def list_records(variants: Iterable[Variant], version: str | None = None) -> list[dict]:
    pool = _pool(variants, version)
    latest = max(v.version_code for v in pool)
    return [
        {
            "package": v.package,
            "version_name": v.version_name,
            "version_code": v.version_code,
            "type": v.archive_type,
            "size": v.size,
            "sha1": v.archive_sha1,
            "latest": v.version_code == latest,
        }
        for v in sorted(pool, key=lambda v: (-v.version_code, v.index))
    ]


# --- HTTPS via curl ---------------------------------------------------------

def check_url(url: str) -> str:
    """Return the host of an acceptable APKPure HTTPS URL; never echoes the URL itself."""
    if any(ord(c) <= 0x20 or ord(c) == 0x7F or c == "\\" for c in url):
        raise FetchError("malformed URL")
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        raise FetchError("malformed URL") from None
    if parts.scheme != "https":
        raise FetchError(f"refusing non-HTTPS URL (scheme {parts.scheme!r})")
    host = parts.hostname or ""
    if parts.username is not None or parts.password is not None:
        raise FetchError(f"refusing URL with credentials for host {host!r}")
    if port not in (None, 443):
        raise FetchError(f"refusing port {port} for host {host!r}")
    if not HOST_RE.fullmatch(host) or not any(
        host == suffix or host.endswith("." + suffix) for suffix in ALLOWED_HOST_SUFFIXES
    ):
        raise FetchError(f"refusing host {host!r}: not an APKPure host")
    return host


def redact(text: str) -> str:
    return re.sub(r"(https?://[^\s?#'\"]*)[?#][^\s'\"]*", r"\1?<redacted>", text)


Runner = Callable[..., subprocess.CompletedProcess]


def curl_fetch(
    url: str,
    dest: Path,
    *,
    resolves: Iterable[str] = (),
    headers: Iterable[str] = (),
    max_time: int,
    max_bytes: int | None = None,
    runner: Runner = subprocess.run,
) -> None:
    """Download url to dest, following redirects only to permitted APKPure hosts."""
    for hop in range(MAX_REDIRECTS + 1):
        host = check_url(url)
        if hop:
            log(f"following redirect to {host}")
        cmd = [
            "curl", "--disable", "--silent", "--show-error", "--fail", "--globoff",
            "--proto", "=https", "--tlsv1.2",
            "--connect-timeout", "30", "--max-time", str(max_time),
            "--speed-limit", "51200", "--speed-time", "300",
            "--retry", "2", "--retry-delay", "5",
            "--output", str(dest), "--write-out", "%{http_code} %{redirect_url}",
        ]
        if max_bytes is not None:
            cmd += ["--max-filesize", str(max_bytes)]
        for resolve in resolves:
            cmd += ["--resolve", resolve]
        for header in headers:
            cmd += ["--header", header]
        cmd += ["--url", url]
        proc = runner(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
        if proc.returncode != 0:
            raise FetchError(f"curl exit {proc.returncode} for host {host}: {redact(proc.stderr.strip())}")
        code, _, location = proc.stdout.strip().partition(" ")
        if code.startswith("3") and location:
            url = location
            continue
        if code != "200":
            raise FetchError(f"unexpected HTTP status {code!r} from {host}")
        return
    raise FetchError(f"more than {MAX_REDIRECTS} redirects")


def fetch_api(target: Target, resolves: Iterable[str], runner: Runner = subprocess.run) -> bytes:
    with tempfile.TemporaryDirectory(prefix="fetch_apk-api-") as tmp:
        dest = Path(tmp, "app_version.bin")
        curl_fetch(target.api_url, dest, resolves=resolves, headers=API_HEADERS,
                   max_time=120, max_bytes=MAX_API_BYTES, runner=runner)
        return dest.read_bytes()


def download_variant(variant: Variant, dest: Path, resolves: Iterable[str], runner: Runner = subprocess.run) -> None:
    if not variant.urls:
        raise VariantError("no permitted download URL advertised")
    errors = []
    for url in variant.urls:
        log(f"downloading variant {variant.describe()} from {check_url(url)}")
        try:
            curl_fetch(url, dest, resolves=resolves, max_time=3600, runner=runner)
            return
        except FetchError as exc:
            errors.append(str(exc))
            log(f"download failed: {exc}")
            dest.unlink(missing_ok=True)
    raise VariantError("all download URLs failed: " + "; ".join(errors))


# --- archive validation and normalisation ------------------------------------

def _sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with open(path, "rb") as fh:
        while chunk := fh.read(CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


def _apk_names(path: Path, label: str) -> set[str]:
    try:
        with zipfile.ZipFile(path) as apk:
            names = set(apk.namelist())
    except (zipfile.BadZipFile, OSError, ValueError) as exc:
        raise VariantError(f"{label} is not a valid APK: {exc}") from None
    if "AndroidManifest.xml" not in names:
        raise VariantError(f"{label} has no AndroidManifest.xml")
    return names


def _copy_member(archive: zipfile.ZipFile, info: zipfile.ZipInfo, target: Path) -> str:
    if info.flag_bits & 0x1:
        raise VariantError(f"{info.filename} is encrypted")
    sha256 = hashlib.sha256()
    written = 0
    try:
        with archive.open(info) as src, open(target, "xb") as dst:
            while chunk := src.read(CHUNK):
                written += len(chunk)
                if written > info.file_size:
                    raise VariantError(f"{info.filename} exceeds its declared size")
                sha256.update(chunk)
                dst.write(chunk)
    except (zipfile.BadZipFile, EOFError, NotImplementedError, zlib.error) as exc:
        raise VariantError(f"cannot read {info.filename}: {exc}") from None
    if written != info.file_size:
        raise VariantError(f"{info.filename} is shorter than declared")
    return sha256.hexdigest()


def _read_xapk_manifest(
    archive: zipfile.ZipFile, by_name: dict[str, zipfile.ZipInfo], variant: Variant, required_splits: Iterable[str]
) -> list[tuple[str, str]]:
    info = by_name.get("manifest.json")
    if info is None:
        raise VariantError("XAPK has no manifest.json")
    if info.file_size > MAX_MANIFEST_BYTES or info.flag_bits & 0x1:
        raise VariantError("manifest.json is oversized or encrypted")
    try:
        manifest = json.loads(archive.read(info).decode("utf-8-sig"))
    except (ValueError, zipfile.BadZipFile, EOFError, NotImplementedError, zlib.error) as exc:
        raise VariantError(f"manifest.json unreadable: {exc}") from None
    if not isinstance(manifest, dict):
        raise VariantError("manifest.json is not an object")
    if manifest.get("package_name") != variant.package:
        raise VariantError(f"manifest.json package {manifest.get('package_name')!r} != {variant.package}")
    if str(manifest.get("version_code", "")).strip() != str(variant.version_code):
        raise VariantError(f"manifest.json version_code {manifest.get('version_code')!r} != {variant.version_code}")
    if str(manifest.get("version_name", "")).strip() != variant.version_name:
        raise VariantError(f"manifest.json version_name {manifest.get('version_name')!r} != {variant.version_name}")
    if manifest.get("expansions"):
        raise VariantError("XAPK carries OBB expansion files, which are unsupported")

    splits = manifest.get("split_apks")
    if not isinstance(splits, list) or not splits:
        raise VariantError("manifest.json has no split_apks list")
    entries: list[tuple[str, str]] = []
    for split in splits:
        if not isinstance(split, dict) or not isinstance(split.get("file"), str) or not isinstance(split.get("id"), str):
            raise VariantError("manifest.json split_apks entry malformed")
        leaf, split_id = split["file"], split["id"]
        if not APK_LEAF_RE.fullmatch(leaf) or ".." in leaf:
            raise VariantError(f"unsafe APK name {leaf!r} in manifest.json")
        if not SPLIT_ID_RE.fullmatch(split_id):
            raise VariantError(f"unsafe split id {split_id!r} in manifest.json")
        if leaf not in by_name:
            raise VariantError(f"manifest.json lists {leaf!r}, missing from archive")
        entries.append((split_id, leaf))
    ids = [split_id for split_id, _ in entries]
    leaves = [leaf for _, leaf in entries]
    if len(set(ids)) != len(ids) or len(set(leaves)) != len(leaves):
        raise VariantError("manifest.json lists duplicate splits")
    if ids.count("base") != 1:
        raise VariantError("manifest.json does not identify exactly one base APK")
    missing = sorted(set(required_splits) - set(ids))
    if missing:
        raise VariantError(f"XAPK lacks required split(s) {missing}")
    return entries


def require_xapk(variant: Variant) -> None:
    if variant.archive_type != "XAPK":
        raise VariantError(f"archive type {variant.archive_type!r} unsupported: only XAPK carries the required splits")


def prepare_variant(
    archive_path: Path, variant: Variant, stage: Path, required_splits: Iterable[str]
) -> tuple[list[dict], list[str]]:
    """Verify a downloaded XAPK and write base.apk/split_<id>.apk into stage.

    Returns (file records, dropped split ids). Raises VariantError when unusable.
    """
    require_xapk(variant)
    actual_size = archive_path.stat().st_size
    if actual_size != variant.size:
        raise VariantError(f"archive is {actual_size} bytes, API advertised {variant.size}")
    sha1 = _sha1(archive_path)
    if sha1 != variant.archive_sha1:
        raise VariantError(f"archive SHA-1 {sha1} != advertised {variant.archive_sha1}")
    log("archive SHA-1 and size match API metadata")

    try:
        archive = zipfile.ZipFile(archive_path)
    except (zipfile.BadZipFile, OSError, ValueError) as exc:
        raise VariantError(f"XAPK is not a valid ZIP: {exc}") from None
    with archive:
        infos = archive.infolist()
        by_name = {info.filename: info for info in infos}
        if len(by_name) != len(infos):
            raise VariantError("XAPK contains duplicate entry names")
        if any(info.filename.lower().endswith(".obb") for info in infos):
            raise VariantError("XAPK carries OBB expansion files, which are unsupported")
        entries = _read_xapk_manifest(archive, by_name, variant, required_splits)
        listed = {leaf for _, leaf in entries}
        unlisted = sorted(
            info.filename for info in infos if info.filename.lower().endswith(".apk") and info.filename not in listed
        )
        if unlisted:
            raise VariantError(f"XAPK contains APKs not described by manifest.json: {unlisted}")

        records: list[dict] = []
        dropped: list[str] = []
        has_arm64 = False
        # Code-bearing APKs (base, config.*) first, so a variant without arm64
        # code is rejected before its large asset packs are extracted.
        for split_id, leaf in sorted(entries, key=lambda e: (e[0] != "base", not e[0].startswith("config."), e[0])):
            if split_id in ABI_SPLITS and split_id != ARM64_SPLIT:
                log(f"dropping non-arm64 ABI split {split_id}")
                dropped.append(split_id)
                continue
            carries_code = split_id == "base" or split_id.startswith("config.")
            if not carries_code and not has_arm64:
                break
            name = "base.apk" if split_id == "base" else f"split_{split_id}.apk"
            dest = stage / name
            sha256 = _copy_member(archive, by_name[leaf], dest)
            names = _apk_names(dest, name)
            if carries_code and ARM64_LIB in names:
                has_arm64 = True
            records.append({"name": name, "sha256": sha256, "size": by_name[leaf].file_size})
    if not has_arm64:
        raise VariantError(f"no {ARM64_LIB} in the base APK or config splits")
    return records, dropped


# --- orchestration --------------------------------------------------------

def check_output(output: Path) -> None:
    if output.is_symlink() or (output.exists() and (not output.is_dir() or any(output.iterdir()))):
        raise FetchError(f"output {output} must not exist or be an empty directory")


def fetch_into(
    variants: list[Variant],
    output: Path,
    download: Callable[[Variant, Path], None],
    target: Target,
) -> dict:
    """Try variants in order; atomically publish the first complete arm64 set at output."""
    if not variants:
        raise FetchError("no variants to fetch")
    check_output(output)
    parent = output.absolute().parent
    parent.mkdir(parents=True, exist_ok=True)
    failures = []
    with tempfile.TemporaryDirectory(prefix=".fetch_apk-", dir=parent) as tmp:
        for attempt, variant in enumerate(variants):
            stage = Path(tmp, f"stage{attempt}")
            stage.mkdir()
            archive = Path(tmp, f"archive{attempt}")
            try:
                require_xapk(variant)
                download(variant, archive)
                files, dropped = prepare_variant(archive, variant, stage, target.required_splits)
            except VariantError as exc:
                log(f"variant {variant.describe()} unusable: {exc}")
                failures.append(f"{variant.describe()}: {exc}")
                shutil.rmtree(stage)
                archive.unlink(missing_ok=True)
                continue
            archive.unlink(missing_ok=True)
            record = {
                "package": variant.package,
                "version_name": variant.version_name,
                "version_code": variant.version_code,
                "source": "apkpure",
                "source_api": target.api_url,
                "archive": {
                    "type": variant.archive_type,
                    "sha1": variant.archive_sha1,
                    "size": variant.size,
                    "advertised_cert_sha1": variant.cert_sha1,
                },
                "dropped_splits": dropped,
                "files": files,
            }
            (stage / "source.json").write_text(json.dumps(record, indent=2) + "\n")
            check_output(output)
            os.rename(stage, output)
            return record
    wanted = f"{variants[0].version_name}/{variants[0].version_code}"
    raise FetchError(f"no usable arm64 variant for {wanted}:\n  " + "\n  ".join(failures))


def _version_arg(value: str) -> str:
    if not VERSION_NAME_RE.fullmatch(value):
        raise argparse.ArgumentTypeError(f"invalid version name {value!r}")
    return value


def _resolve_arg(value: str) -> str:
    if not RESOLVE_RE.fullmatch(value):
        raise argparse.ArgumentTypeError(f"expected HOST:443:IP, got {value!r}")
    return value


def main(argv: list[str] | None = None, runner: Runner = subprocess.run, baseline_path: Path = BASELINE_PATH) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=Path, help="directory to publish (must not exist or be empty)")
    parser.add_argument("--version", type=_version_arg, help="exact versionName (default: highest versionCode)")
    parser.add_argument("--list", action="store_true", help="print advertised variants as JSON, download nothing")
    parser.add_argument("--resolve", action="append", default=[], type=_resolve_arg, metavar="HOST:443:IP",
                        help="forwarded to curl --resolve (local DNS workarounds only)")
    args = parser.parse_args(argv)
    if args.list == (args.output is not None):
        parser.error("exactly one of --list or --output is required")
    try:
        if args.output is not None:
            check_output(args.output)
        target = load_target(baseline_path)
        variants = parse_versions(fetch_api(target, args.resolve, runner), target)
        if args.list:
            print(json.dumps(list_records(variants, args.version), indent=2))
            return 0
        chosen = select_version(variants, args.version)
        log(f"selected {chosen[0].version_name}/{chosen[0].version_code} ({len(chosen)} variants)")
        record = fetch_into(
            chosen, args.output, lambda variant, dest: download_variant(variant, dest, args.resolve, runner), target
        )
    except FetchError as exc:
        log(f"error: {exc}")
        return 1
    log(f"published {args.output}")
    print(json.dumps(record, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
