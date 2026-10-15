#!/usr/bin/env python3
"""Signed build identity (assets/toemod/build.json) and the public OTA manifest (update.json).

    python3 ci/update_manifest.py build-info --base-apk ORIGINAL/base.apk [--build-tools PATH] --output build.json
    python3 ci/update_manifest.py release --release-dir DIR --report compatibility.json [--build-tools PATH]

build-info runs the source guard (check_apks) on the directory of --base-apk, which must be that set's
base APK, and writes the identity the mod build signs into every APK it outputs:
    schema, repository, package, abi, tag, mod_build, mod_revision, version_code, version_name, required_apks
package / version_* / required_apks (every APK file name of the guarded set) come from the actual
APKs; the rest from the environment:
    MOD_RELEASE_TAG   default local-<first 12 hex of the revision>
    MOD_BUILD_NUMBER  default 0 (a local build); CI passes the workflow run number
    MOD_REVISION      default `git rev-parse HEAD` of this repository (40 lowercase hex)
    MOD_REPOSITORY    default $GITHUB_REPOSITORY, else DEFAULT_REPOSITORY
A variable that is set must be valid; an empty value is an error, never a fallback to the default.
Public builds (mod_build > 0) must be tagged vMAJOR.MINOR.PATCH, the mod's own semantic version
(independent of the game version); local builds (mod_build 0) must not use that form.

release checks the signed output set in DIR against the source guard report (the APK file names, the
base APK, package and version), verifies every APK's signature (one and the same certificate for all,
not the Play certificate), requires the identical build.json in every APK, consistent with the actual
manifests, the report and the file list, and only then writes DIR/update.json atomically: the identity
plus base_apk, signing_cert_sha256 and the name, size and SHA-256 of every APK.

Errors: one line on stderr, exit status 1; nothing is written.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_apks as ca  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = 1
ABI = "arm64-v8a"
DEFAULT_REPOSITORY = "ardzz/truckers-of-europe-3-mod"
BUILD_INFO = "assets/toemod/build.json"
UPDATE_MANIFEST = "update.json"
MOD_LIB = f"lib/{ABI}/{ca.MOD_LIB_NAME}"
IDENTITY_KEYS = ("schema", "repository", "package", "abi", "tag", "mod_build", "mod_revision",
                 "version_code", "version_name", "required_apks")
MAX_BUILD = 2 ** 31 - 1
MAX_BUILD_INFO_SIZE = 8 * 1024
MAX_APKS = 16
MAX_APK_SIZE = 4 << 30
MAX_TOTAL_SIZE = 8 << 30

_REPOSITORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/(?!\.\.?$)[A-Za-z0-9._-]{1,100}")
_TAG = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
_SEMANTIC_TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
_REVISION = re.compile(r"[0-9a-f]{40}")
_BUILD_NUMBER = re.compile(r"0|[1-9][0-9]{0,9}")
_APK_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,123}\.apk")
_VERSION_NAME = re.compile(r"[\x21-\x7E](?:[\x20-\x7E]{0,98}[\x21-\x7E])?")
_REPORT_KEYS = {"package", "version_name", "version_code", "base_apk", "native_apk", "apks", "compatible"}


class MetadataError(ca.Incompatible):
    """Build identity, report or release set is invalid or inconsistent."""


def _bad(msg):
    raise MetadataError(msg)


def semantic_version(tag):
    """(major, minor, patch) of a public release tag vMAJOR.MINOR.PATCH (stable SemVer core without leading
    zeros, within the tag length limit), else None."""
    if not (isinstance(tag, str) and _TAG.fullmatch(tag)):
        return None
    match = _SEMANTIC_TAG.fullmatch(tag)
    return tuple(int(part) for part in match.groups()) if match else None


def validate_identity(doc, package=None, version_code=None, version_name=None, apks=None) -> dict:
    """doc as a build identity; optional arguments are the actual values it must agree with."""
    if not isinstance(doc, dict) or set(doc) != set(IDENTITY_KEYS):
        keys = sorted(doc) if isinstance(doc, dict) else type(doc).__name__
        _bad(f"build identity must have exactly the keys {list(IDENTITY_KEYS)}, has {keys}")
    if not ca._is_int(doc["schema"]) or doc["schema"] != SCHEMA:
        _bad(f"build identity schema must be {SCHEMA}")
    checks = (
        ("repository", lambda v: isinstance(v, str) and _REPOSITORY.fullmatch(v)),
        ("package", lambda v: isinstance(v, str) and ca._PACKAGE.fullmatch(v)),
        ("abi", lambda v: v == ABI),
        ("tag", lambda v: isinstance(v, str) and _TAG.fullmatch(v)),
        ("mod_build", lambda v: ca._is_int(v) and 0 <= v <= MAX_BUILD),
        ("mod_revision", lambda v: isinstance(v, str) and _REVISION.fullmatch(v)),
        ("version_code", lambda v: ca._is_int(v) and 0 < v <= MAX_BUILD),
        ("version_name", lambda v: isinstance(v, str) and _VERSION_NAME.fullmatch(v)),
        ("required_apks", lambda v: isinstance(v, list) and 1 <= len(v) <= MAX_APKS
         and all(isinstance(n, str) and _APK_NAME.fullmatch(n) for n in v) and v == sorted(set(v))),
    )
    for key, ok in checks:
        if not ok(doc[key]):
            _bad(f"build identity {key} {doc[key]!r} is invalid")
    public = semantic_version(doc["tag"]) is not None
    if public != (doc["mod_build"] > 0):
        _bad(f"build identity tag {doc['tag']!r} does not fit mod_build {doc['mod_build']} "
             f"(public builds need a vMAJOR.MINOR.PATCH tag, local builds mod_build 0 and another tag)")
    for key, actual in (("package", package), ("version_code", version_code), ("version_name", version_name),
                        ("required_apks", None if apks is None else sorted(apks))):
        if actual is not None and doc[key] != actual:
            _bad(f"build identity {key} {doc[key]!r} differs from the actual {actual!r}")
    return {key: doc[key] for key in IDENTITY_KEYS}


def encode_identity(doc) -> bytes:
    return (json.dumps({key: doc[key] for key in IDENTITY_KEYS}, separators=(",", ":"), ensure_ascii=True)
            + "\n").encode("ascii")


def parse_json(data, label, limit):
    if len(data) > limit:
        _bad(f"{label} is larger than {limit} bytes")

    def unique(pairs):
        keys = [k for k, _ in pairs]
        if len(keys) != len(set(keys)):
            _bad(f"{label} has duplicate keys")
        return dict(pairs)

    def constant(name):
        _bad(f"{label} contains {name}")

    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique, parse_constant=constant)
    except (UnicodeDecodeError, ValueError) as e:
        _bad(f"{label} is not valid JSON: {e}")


def read_build_info(zf, label) -> bytes:
    """Raw bytes of the single build.json entry of an opened APK."""
    infos = [i for i in zf.infolist() if i.filename == BUILD_INFO]
    if len(infos) != 1:
        _bad(f"{label}: expected one {BUILD_INFO}, found {len(infos)}")
    if infos[0].file_size > MAX_BUILD_INFO_SIZE:
        _bad(f"{label}: {BUILD_INFO} is larger than {MAX_BUILD_INFO_SIZE} bytes")
    try:
        return zf.read(infos[0])
    except ca._ZIP_ERRORS as e:
        _bad(f"{label}: cannot read {BUILD_INFO}: {e}")


def git_head():
    try:
        p = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--verify", "HEAD"], capture_output=True,
                           text=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired) as e:
        _bad(f"MOD_REVISION is not set and git could not run: {e}")
    if p.returncode != 0:
        _bad(f"MOD_REVISION is not set and git HEAD is unreadable: {ca._first_line(p.stderr, p.stdout)}")
    return p.stdout.strip()


def _env(env, name, default):
    value = env.get(name)
    if value is None:
        return default()
    if not value:
        _bad(f"{name} is set but empty")
    return value


def identity_from_env(env, package, version_code, version_name, apks, head=git_head) -> dict:
    revision = _env(env, "MOD_REVISION", head)
    if not _REVISION.fullmatch(revision):
        _bad(f"MOD_REVISION {revision!r} is not a 40-digit lowercase hex commit")
    build = _env(env, "MOD_BUILD_NUMBER", lambda: "0")
    if not _BUILD_NUMBER.fullmatch(build) or int(build) > MAX_BUILD:
        _bad(f"MOD_BUILD_NUMBER {build!r} is not an integer from 0 to {MAX_BUILD}")
    doc = {
        "schema": SCHEMA,
        "repository": _env(env, "MOD_REPOSITORY", lambda: env.get("GITHUB_REPOSITORY") or DEFAULT_REPOSITORY),
        "package": package,
        "abi": ABI,
        "tag": _env(env, "MOD_RELEASE_TAG", lambda: f"local-{revision[:12]}"),
        "mod_build": int(build),
        "mod_revision": revision,
        "version_code": version_code,
        "version_name": version_name,
        "required_apks": sorted(apks),
    }
    return validate_identity(doc)


def build_info(base_apk, tools, env, baseline, run=ca.run_tool, head=git_head) -> dict:
    """Identity of a mod build of the guarded APK set that base_apk belongs to."""
    base_apk = Path(base_apk)
    summary = ca.check(base_apk.parent, baseline, tools, run)
    if summary["base_apk"] != base_apk.name:
        _bad(f"{base_apk.name} is not the base APK of {base_apk.parent} (that is {summary['base_apk']})")
    for name in summary["apks"]:
        with ca.open_apk(base_apk.parent / name) as zf:
            if BUILD_INFO in ca.entry_names(zf, name):
                _bad(f"{name} already contains {BUILD_INFO}; input is already modded")
    return identity_from_env(env, summary["package"], summary["version_code"], summary["version_name"],
                             summary["apks"], head)


def load_report(path, baseline) -> dict:
    try:
        data = Path(path).read_bytes()
    except OSError as e:
        _bad(f"cannot read report {path}: {e}")
    report = parse_json(data, f"report {path}", 1 << 20)
    if not isinstance(report, dict) or set(report) != _REPORT_KEYS or report["compatible"] is not True:
        _bad(f"report {path} is not a successful check_apks report")
    apks = report["apks"]
    if (not isinstance(apks, list) or not apks or not all(isinstance(n, str) and _APK_NAME.fullmatch(n) for n in apks)
            or len(set(apks)) != len(apks)):
        _bad(f"report {path}: apks must be unique APK file names")
    if report["base_apk"] not in apks or report["native_apk"] not in apks:
        _bad(f"report {path}: base_apk / native_apk are not in apks")
    if report["package"] != baseline.package:
        _bad(f"report {path}: package {report['package']!r} is not {baseline.package}")
    if (not ca._is_int(report["version_code"]) or report["version_code"] <= 0
            or not isinstance(report["version_name"], str) or not report["version_name"]):
        _bad(f"report {path}: version_code / version_name are invalid")
    return report


def file_digest(path):
    digest, size = hashlib.sha256(), 0
    try:
        with open(path, "rb") as f:
            while chunk := f.read(ca.CHUNK):
                digest.update(chunk)
                size += len(chunk)
    except OSError as e:
        _bad(f"cannot read {Path(path).name}: {e}")
    return digest.hexdigest(), size


def update_manifest(release_dir, report, tools, baseline, run=ca.run_tool) -> dict:
    """The update.json document for the signed APK set in release_dir (validated, not written)."""
    release_dir = Path(release_dir)
    if not release_dir.is_dir():
        _bad(f"not a directory: {release_dir}")
    paths = sorted(p for p in release_dir.iterdir() if p.name.endswith(".apk"))
    names = [p.name for p in paths]
    for p in paths:
        if p.is_symlink() or not p.is_file() or not _APK_NAME.fullmatch(p.name):
            _bad(f"{p.name} is not a regular, safely named APK file")
    if names != sorted(report["apks"]):
        _bad(f"release APKs {names} differ from the report's {sorted(report['apks'])}")

    base, version_name, version_code, _ = ca._check_identity(paths, baseline, tools, run)
    actual = (base, version_name, version_code)
    if actual != (report["base_apk"], report["version_name"], report["version_code"]):
        _bad(f"release base/version {actual} differ from the report's "
             f"{(report['base_apk'], report['version_name'], report['version_code'])}")

    certs = set()
    for p in paths:
        _, sha256 = ca.verified_signers(tools, p, run)
        if len(sha256) != 1:
            _bad(f"{p.name}: expected one signer certificate, found {sorted(sha256)}")
        certs |= sha256
    if len(certs) != 1:
        _bad(f"release APKs are signed by different certificates: {sorted(certs)}")
    cert = certs.pop()
    if cert == baseline.signer_sha256:
        _bad("release APKs carry the original Play signature; they are not the signed mod build")

    with ca.open_apk(release_dir / report["native_apk"]) as zf:
        entries = set(ca.entry_names(zf, report["native_apk"]))
    if not {ca.NATIVE_LIB, MOD_LIB} <= entries:
        _bad(f"{report['native_apk']} lacks {ca.NATIVE_LIB} or {MOD_LIB}")

    blobs = {}
    for p in paths:
        with ca.open_apk(p) as zf:
            blobs[p.name] = read_build_info(zf, p.name)
    stale = sorted(n for n, blob in blobs.items() if blob != blobs[base])
    if stale:
        _bad(f"{BUILD_INFO} of {stale} differs from the one in {base}")
    identity = validate_identity(parse_json(blobs[base], f"{base}:{BUILD_INFO}", MAX_BUILD_INFO_SIZE),
                                 report["package"], version_code, version_name, names)
    if identity["mod_build"] == 0:
        _bad(f"{base} is a local build (mod_build 0, tag {identity['tag']!r}); it cannot be published")

    apks, total = [], 0
    for p in paths:
        sha256, size = file_digest(p)
        if not 0 < size <= MAX_APK_SIZE:
            _bad(f"{p.name}: size {size} exceeds the Android updater's per-APK limit {MAX_APK_SIZE}")
        total += size
        if total > MAX_TOTAL_SIZE:
            _bad(f"APK set size {total} exceeds the Android updater's total limit {MAX_TOTAL_SIZE}")
        apks.append({"name": p.name, "size": size, "sha256": sha256})
    return {**identity, "base_apk": base, "signing_cert_sha256": cert, "apks": apks}


def write_atomic(path, data: bytes):
    path = Path(path)
    fd, tmp = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def main(argv=None, run=ca.run_tool, env=None, baseline_path=ca.BASELINE_PATH, head=git_head) -> int:
    parser = argparse.ArgumentParser(description="Mod build identity and OTA update manifest.")
    sub = parser.add_subparsers(dest="command", required=True)
    p_info = sub.add_parser("build-info", help="write the build identity for a mod build of an original set")
    p_info.add_argument("--base-apk", required=True, type=Path)
    p_info.add_argument("--output", required=True, type=Path)
    p_release = sub.add_parser("release", help="write update.json for a signed release directory")
    p_release.add_argument("--release-dir", required=True, type=Path)
    p_release.add_argument("--report", required=True, type=Path)
    for p in (p_info, p_release):
        p.add_argument("--build-tools", metavar="PATH")
    args = parser.parse_args(argv)
    env = os.environ if env is None else env
    try:
        baseline = ca.load_baseline(baseline_path)
        tools = ca.find_build_tools(args.build_tools, env)
        if args.command == "build-info":
            data = encode_identity(build_info(args.base_apk, tools, env, baseline, run, head))
            write_atomic(args.output, data)
        else:
            doc = update_manifest(args.release_dir, load_report(args.report, baseline), tools, baseline, run)
            data = (json.dumps(doc, indent=2, ensure_ascii=True) + "\n").encode("ascii")
            write_atomic(args.release_dir / UPDATE_MANIFEST, data)
    except (ca.Incompatible, OSError) as e:
        print(f"update_manifest: ERROR: {e}", file=sys.stderr)
        return 1
    sys.stdout.write(data.decode("ascii"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
