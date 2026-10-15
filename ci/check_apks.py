#!/usr/bin/env python3
"""Fail-closed compatibility guard for Truckers of Europe 3 APK sets.

The mod hard-codes addresses inside libil2cpp.so, so it may only be built for an APK set whose
native code and IL2CPP metadata are byte-identical to the reviewed baseline (compatibility.json).
Nothing is ported automatically: a changed binary is rejected.

    python3 ci/check_apks.py DIRECTORY [--build-tools PATH]

Every *.apk directly inside DIRECTORY is checked (the base plus its splits); other files, such as a
downloader's source.json, are ignored and never trusted. Checks, all on the actual files:
  * ZIP structure: readable, no duplicate entry names, entry CRCs of the pinned files;
  * not already modded (no libtoemod.so, no dex referencing com/toemod);
  * aapt2 badging: expected package everywhere, exactly one base, unique split names, one positive
    versionCode shared by all APKs, base versionName set and repeated (or empty) in the splits;
  * required splits (UnityDataAssetPack): present, each holding its listed game data entries
    (non-empty, nowhere else in the set). The game data ships only in that asset pack, so a
    standalone APK or a set without the pack is rejected;
  * pinned entries (libil2cpp.so, global-metadata.dat, ...): exactly one copy in the whole set,
    SHA-256 and size equal to the baseline; arm64-v8a libil2cpp.so must exist (in the base or a split);
  * apksigner verify --print-certs: verifies, and every signer certificate is the pinned one.

Success: one JSON line on stdout, exit 0. Otherwise: one line on stderr, exit 1. No side effects.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

BASELINE_PATH = Path(__file__).resolve().with_name("compatibility.json")
NATIVE_LIB = "lib/arm64-v8a/libil2cpp.so"
METADATA = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
MOD_LIB_NAME = "libtoemod.so"
MOD_DEX_MARKER = b"Lcom/toemod/"
CHUNK = 1 << 20
TOOL_TIMEOUT = 1800

_DEX = re.compile(r"classes\d*\.dex")
_ANY_ABI_IL2CPP = re.compile(r"lib/[^/]+/libil2cpp\.so")
_BADGING_ATTR = re.compile(r"(\w+)='((?:[^'\\]|\\.)*)'")
_CERT_DIGEST = re.compile(r"(?P<signer>.*?)\bcertificate (?P<algo>SHA-1|SHA-256|MD5) digest: (?P<digest>[0-9A-Fa-f]+)")
_VERSION_CODE = re.compile(r"[1-9][0-9]*")
_SHA1 = re.compile(r"[0-9a-f]{40}")
_SHA256 = re.compile(r"[0-9a-f]{64}")
_PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+")
_SPLIT_NAME = re.compile(r"[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*")
_ZIP_ERRORS = (zipfile.BadZipFile, zipfile.LargeZipFile, OSError, EOFError, ValueError, NotImplementedError, zlib.error)


class Incompatible(Exception):
    """The input (or the baseline) must not be used; the message is the one-line reason."""


@dataclass(frozen=True)
class Pin:
    sha256: str
    size: int


@dataclass(frozen=True)
class Baseline:
    package: str
    signer_sha1: str
    signer_sha256: str
    pins: dict
    required_splits: dict


@dataclass(frozen=True)
class Tools:
    aapt2: str
    apksigner: str


# ---------------------------------------------------------------- baseline

_BASELINE_KEYS = {"schema", "package", "signer_cert_sha1", "signer_cert_sha256", "reviewed_build",
                  "pinned_entries", "required_splits", "notes"}


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _valid_entry_path(path):
    parts = path.split("/")
    return bool(path) and "\\" not in path and all(p not in ("", ".", "..") for p in parts)


def validate_baseline(doc) -> Baseline:
    def bad(msg):
        raise Incompatible(f"invalid baseline: {msg}")

    if not isinstance(doc, dict):
        bad("not a JSON object")
    if set(doc) != _BASELINE_KEYS:
        extra, missing = sorted(set(doc) - _BASELINE_KEYS), sorted(_BASELINE_KEYS - set(doc))
        bad(f"unexpected keys {extra}, missing keys {missing}")
    if doc["schema"] != 1 or not _is_int(doc["schema"]):
        bad("schema must be 1")
    if not isinstance(doc["package"], str) or not _PACKAGE.fullmatch(doc["package"]):
        bad("package is not a package name")
    for key, pattern in (("signer_cert_sha1", _SHA1), ("signer_cert_sha256", _SHA256)):
        if not isinstance(doc[key], str) or not pattern.fullmatch(doc[key]):
            bad(f"{key} must be lowercase hex")
    reviewed = doc["reviewed_build"]
    if (not isinstance(reviewed, dict) or set(reviewed) != {"version_name", "version_code"}
            or not isinstance(reviewed["version_name"], str) or not reviewed["version_name"]
            or not _is_int(reviewed["version_code"]) or reviewed["version_code"] <= 0):
        bad("reviewed_build must be {version_name: non-empty string, version_code: positive integer}")
    if not isinstance(doc["notes"], list) or not all(isinstance(n, str) for n in doc["notes"]):
        bad("notes must be a list of strings")
    entries = doc["pinned_entries"]
    if not isinstance(entries, dict):
        bad("pinned_entries must be an object")
    pins = {}
    for path, pin in entries.items():
        if not _valid_entry_path(path):
            bad(f"pinned entry path {path!r} is not a plain relative ZIP path")
        if (not isinstance(pin, dict) or set(pin) != {"sha256", "size"}
                or not isinstance(pin["sha256"], str) or not _SHA256.fullmatch(pin["sha256"])
                or not _is_int(pin["size"]) or pin["size"] <= 0):
            bad(f"pinned entry {path} must be {{sha256: lowercase hex, size: positive integer}}")
        pins[path] = Pin(pin["sha256"], pin["size"])
    for required in (NATIVE_LIB, METADATA):
        if required not in pins:
            bad(f"{required} must be pinned")
    required_splits = doc["required_splits"]
    if not isinstance(required_splits, dict) or not required_splits:
        bad("required_splits must be a non-empty object")
    for split, paths in required_splits.items():
        if not _SPLIT_NAME.fullmatch(split):
            bad(f"required split name {split!r} is not a split name")
        if (not isinstance(paths, list) or not paths
                or not all(isinstance(p, str) and _valid_entry_path(p) for p in paths)
                or len(set(paths)) != len(paths)):
            bad(f"required split {split} must list unique, plain relative ZIP paths")
    return Baseline(doc["package"], doc["signer_cert_sha1"], doc["signer_cert_sha256"], pins,
                    {split: tuple(paths) for split, paths in required_splits.items()})


def load_baseline(path=BASELINE_PATH) -> Baseline:
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
    except (OSError, ValueError) as e:
        raise Incompatible(f"cannot read baseline {path}: {e}") from None
    return validate_baseline(doc)


# ---------------------------------------------------------------- external tools

def _version_key(path):
    return [int(n) for n in re.findall(r"\d+", path.name)], path.name


def _tools_in(directory):
    aapt2, apksigner = Path(directory, "aapt2"), Path(directory, "apksigner")
    if all(p.is_file() and os.access(p, os.X_OK) for p in (aapt2, apksigner)):
        return Tools(str(aapt2), str(apksigner))
    return None


def find_build_tools(explicit=None, env=None) -> Tools:
    """--build-tools, then $BUILD_TOOLS, then the newest build-tools of the SDK, then PATH."""
    env = os.environ if env is None else env
    for origin, directory in (("--build-tools", explicit), ("BUILD_TOOLS", env.get("BUILD_TOOLS"))):
        if directory:
            tools = _tools_in(directory)
            if tools is None:
                raise Incompatible(f"{origin} {directory} lacks an executable aapt2 and apksigner")
            return tools
    sdks = [env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT"), str(Path.home() / "Android" / "Sdk")]
    for sdk in filter(None, sdks):
        build_tools = Path(sdk, "build-tools")
        if build_tools.is_dir():
            for directory in sorted((d for d in build_tools.iterdir() if d.is_dir()), key=_version_key, reverse=True):
                tools = _tools_in(directory)
                if tools:
                    return tools
    aapt2, apksigner = shutil.which("aapt2"), shutil.which("apksigner")
    if aapt2 and apksigner:
        return Tools(aapt2, apksigner)
    raise Incompatible("Android build-tools (aapt2, apksigner) not found; pass --build-tools or set "
                       "BUILD_TOOLS / ANDROID_HOME")


def run_tool(argv):
    try:
        p = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True, text=True, errors="replace",
                           timeout=TOOL_TIMEOUT, check=False)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise Incompatible(f"{Path(argv[0]).name} could not run: {e}") from None
    return p.returncode, p.stdout, p.stderr


def _first_line(*texts):
    for text in texts:
        for line in text.splitlines():
            if line.strip():
                return line.strip()[:300]
    return "no output"


def parse_badging(text) -> dict:
    for line in text.splitlines():
        if line.startswith("package:"):
            return dict(_BADGING_ATTR.findall(line))
    raise Incompatible("aapt2 badging has no package line")


def read_badging(tools, apk, run=run_tool) -> dict:
    code, out, err = run([tools.aapt2, "dump", "badging", str(apk)])
    if code != 0:
        raise Incompatible(f"{Path(apk).name}: aapt2 dump badging failed: {_first_line(err, out)}")
    try:
        return parse_badging(out)
    except Incompatible as e:
        raise Incompatible(f"{Path(apk).name}: {e}") from None


def parse_signer_digests(text, algo) -> list:
    """Signer certificate digests from `apksigner verify --print-certs`, without the source stamp."""
    digests = []
    for line in text.splitlines():
        m = _CERT_DIGEST.fullmatch(line.strip())
        if m and m["algo"] == algo and not m["signer"].startswith("Source Stamp"):
            digests.append(m["digest"].lower())
    return digests


def verified_signers(tools, apk, run=run_tool):
    """(SHA-1 set, SHA-256 set) of the signer certificates; raises unless the APK verifies."""
    name = Path(apk).name
    code, out, err = run([tools.apksigner, "verify", "--print-certs", str(apk)])
    if code != 0:
        raise Incompatible(f"{name}: signature verification failed: {_first_line(err, out)}")
    sha1, sha256 = set(parse_signer_digests(out, "SHA-1")), set(parse_signer_digests(out, "SHA-256"))
    if not sha1 or not sha256:
        raise Incompatible(f"{name}: apksigner reported no signer certificate")
    return sha1, sha256


# ---------------------------------------------------------------- ZIP access

def open_apk(path) -> zipfile.ZipFile:
    try:
        return zipfile.ZipFile(path)
    except _ZIP_ERRORS as e:
        raise Incompatible(f"{Path(path).name}: not a readable APK: {e}") from None


def entry_names(zf, label) -> list:
    names, seen = [], set()
    for info in zf.infolist():
        if info.filename in seen:
            raise Incompatible(f"{label}: duplicate ZIP entry {info.filename}")
        seen.add(info.filename)
        names.append(info.filename)
    return names


def sha256_entry(zf, name, label):
    """(sha256, size) of one entry, streamed; the ZIP CRC is checked at the end of the stream."""
    digest, size = hashlib.sha256(), 0
    try:
        with zf.open(name) as f:
            while chunk := f.read(CHUNK):
                digest.update(chunk)
                size += len(chunk)
    except _ZIP_ERRORS as e:
        raise Incompatible(f"{label}: cannot read {name}: {e}") from None
    return digest.hexdigest(), size


def _entry_contains(zf, name, marker, label):
    keep = len(marker) - 1
    tail = b""
    try:
        with zf.open(name) as f:
            while chunk := f.read(CHUNK):
                window = tail + chunk
                if marker in window:
                    return True
                tail = window[-keep:]
    except _ZIP_ERRORS as e:
        raise Incompatible(f"{label}: cannot read {name}: {e}") from None
    return False


# ---------------------------------------------------------------- the check

def _check_structure(paths):
    """Readable ZIPs without duplicates and without the mod; returns {apk name: {entry: size}}."""
    entries = {}
    for path in paths:
        with open_apk(path) as zf:
            entry_names(zf, path.name)
            sizes = {info.filename: info.file_size for info in zf.infolist()}
            for name in sizes:
                if PurePosixPath(name).name == MOD_LIB_NAME:
                    raise Incompatible(f"{path.name}: contains {name}; input is already modded")
                if _DEX.fullmatch(name) and _entry_contains(zf, name, MOD_DEX_MARKER, path.name):
                    raise Incompatible(f"{path.name}: {name} references com/toemod classes; input is already modded")
        entries[path.name] = sizes
    return entries


def _check_identity(paths, baseline, tools, run):
    """Returns (base apk, version name, version code, {split name: apk}) from the actual manifests."""
    badgings = {path.name: read_badging(tools, path, run) for path in paths}
    for name, attrs in badgings.items():
        if attrs.get("name") != baseline.package:
            raise Incompatible(f"{name}: package {attrs.get('name')!r} is not {baseline.package}")
    bases = [name for name, attrs in badgings.items() if "split" not in attrs]
    if len(bases) != 1:
        raise Incompatible(f"expected exactly one base APK (no split name), found {bases or 'none'}")
    base = bases[0]
    split_owner = {}
    for name, attrs in badgings.items():
        split = attrs.get("split")
        if split is None:
            continue
        if not split or split in split_owner:
            raise Incompatible(f"split name {split!r} of {name} is empty or also used by {split_owner.get(split)}")
        split_owner[split] = name
    version_code = badgings[base].get("versionCode", "")
    if not _VERSION_CODE.fullmatch(version_code):
        raise Incompatible(f"{base}: versionCode {version_code!r} is not a positive integer")
    version_name = badgings[base].get("versionName", "")
    if not version_name:
        raise Incompatible(f"{base}: versionName is empty")
    for name, attrs in badgings.items():
        if attrs.get("versionCode") != version_code:
            raise Incompatible(f"{name}: versionCode {attrs.get('versionCode')!r} differs from base {version_code}")
        if attrs.get("versionName", "") not in ("", version_name):
            raise Incompatible(f"{name}: versionName {attrs.get('versionName')!r} differs from base {version_name!r}")
    return base, version_name, int(version_code), split_owner


def _locate(entry, entries):
    holders = [apk for apk, names in entries.items() if entry in names]
    if len(holders) > 1:
        raise Incompatible(f"{entry} present in multiple APKs: {', '.join(holders)}")
    return holders[0] if holders else None


def check_required_splits(splits, entries, baseline):
    """Each reviewed split is present and holds its game data entries (non-empty, nowhere else)."""
    for split, required in baseline.required_splits.items():
        apk = splits.get(split)
        if apk is None:
            raise Incompatible(f"required split {split} is missing; only the complete split set is supported")
        for entry in required:
            holder = _locate(entry, entries)
            if holder != apk:
                where = "missing" if holder is None else f"in {holder} instead"
                raise Incompatible(f"game data {entry} of required split {split} ({apk}) is {where}")
            if entries[apk][entry] <= 0:
                raise Incompatible(f"{apk}: game data {entry} of required split {split} is empty")


def check_pins(directory, entries, baseline):
    """Every pinned entry exists once with the reviewed bytes; returns the APK holding libil2cpp."""
    native_apk = _locate(NATIVE_LIB, entries)
    if native_apk is None:
        others = [f"{apk}:{n}" for apk, names in entries.items() for n in names if _ANY_ABI_IL2CPP.fullmatch(n)]
        found = f"; found only {', '.join(others)}" if others else ""
        raise Incompatible(f"{NATIVE_LIB} not found in any APK{found}; this mod supports arm64-v8a only")
    for entry, pin in baseline.pins.items():
        apk = _locate(entry, entries)
        if apk is None:
            raise Incompatible(f"{entry} not found in any APK")
        with open_apk(directory / apk) as zf:
            digest, size = sha256_entry(zf, entry, apk)
        if (digest, size) != (pin.sha256, pin.size):
            raise Incompatible(f"{apk}:{entry} is sha256 {digest} ({size} bytes), reviewed baseline is "
                               f"{pin.sha256} ({pin.size} bytes); changed game code needs a manual re-review")
    return native_apk


def _check_signers(paths, baseline, tools, run):
    for path in paths:
        sha1, sha256 = verified_signers(tools, path, run)
        if sha1 != {baseline.signer_sha1} or sha256 != {baseline.signer_sha256}:
            raise Incompatible(f"{path.name}: signed by SHA-1 {', '.join(sorted(sha1))} / SHA-256 "
                               f"{', '.join(sorted(sha256))}; only {baseline.signer_sha1} / "
                               f"{baseline.signer_sha256} is accepted")


def check(directory, baseline, tools, run=run_tool) -> dict:
    directory = Path(directory)
    if not directory.is_dir():
        raise Incompatible(f"not a directory: {directory}")
    candidates = sorted(p for p in directory.iterdir() if p.name.endswith(".apk"))
    for path in candidates:
        if not path.is_file():
            raise Incompatible(f"{path.name} is not a regular file")
    if not candidates:
        raise Incompatible(f"no *.apk files in {directory}")
    entries = _check_structure(candidates)
    base, version_name, version_code, splits = _check_identity(candidates, baseline, tools, run)
    check_required_splits(splits, entries, baseline)
    native_apk = check_pins(directory, entries, baseline)
    _check_signers(candidates, baseline, tools, run)
    return {
        "package": baseline.package,
        "version_name": version_name,
        "version_code": version_code,
        "base_apk": base,
        "native_apk": native_apk,
        "apks": [p.name for p in candidates],
        "compatible": True,
    }


def main(argv=None, run=run_tool, baseline_path=BASELINE_PATH) -> int:
    parser = argparse.ArgumentParser(description="Reject APK sets whose native code differs from the reviewed build.")
    parser.add_argument("directory", type=Path, help="directory with base.apk (+ split APKs)")
    parser.add_argument("--build-tools", metavar="PATH", help="Android build-tools dir with aapt2 and apksigner")
    args = parser.parse_args(argv)
    try:
        baseline = load_baseline(baseline_path)
        tools = find_build_tools(args.build_tools)
        summary = check(args.directory, baseline, tools, run)
    except Incompatible as e:
        print(f"check_apks: INCOMPATIBLE: {e}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
