#!/usr/bin/env bash
# Build the installable, re-signed APK set from an original (Play-signed) APK set.
#
#   KS_ALIAS=... KS_PASS=... bash work/mod/build_release.sh   ->   work/mod/out/release/*.apk
#
# 1. ci/check_apks.py runs first: nothing is built unless libil2cpp.so, global-metadata.dat and the
#    other pinned files are byte-identical to the reviewed build and every APK is Play-signed.
# 2. build_native.sh (libtoemod.so) and build_base.sh (menu injected into the base APK).
# 3. Every input APK is rewritten: the old v1 signature files and stamp-cert-sha256 (both invalid
#    after re-signing) are dropped; every other entry keeps its bytes and its original compression.
#    lib/arm64-v8a/libtoemod.so is added to the APK that holds libil2cpp.so (a split or the base),
#    compressed like libil2cpp.so. libil2cpp.so itself is never modified.
# 4. zipalign + apksigner, then verified: signature, signer == the keystore certificate, alignment,
#    badging unchanged, pinned entries unchanged. out/release is replaced only after all of it passed.
#
# Env: PULLED_DIR  original APK set (default: <repo>/com.WandaSoftware.TruckersofEurope3)
#      KEYSTORE    existing keystore (default: <repo>/build/mod.keystore); never created here
#      KS_ALIAS    key alias (required)
#      KS_PASS     keystore password (required); reaches keytool/apksigner only via the environment
#      KEY_PASS    key password (default: KS_PASS)
#      BUILD_TOOLS build-tools dir (default: newest in ANDROID_HOME / ANDROID_SDK_ROOT / ~/Android/Sdk)
#      ANDROID_NDK_HOME (build_native.sh), ANDROID_JAR, APKTOOL (build_base.sh)
set -euo pipefail

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$MOD_DIR/../.." && pwd)"
PULLED_DIR="${PULLED_DIR:-$ROOT_DIR/com.WandaSoftware.TruckersofEurope3}"
KEYSTORE="${KEYSTORE:-$ROOT_DIR/build/mod.keystore}"
OUT_ROOT="$MOD_DIR/out"
OUT="$OUT_ROOT/release"
LIB="$OUT_ROOT/lib/arm64-v8a/libtoemod.so"
BASE_BUILT="$OUT_ROOT/base-unsigned.apk"

die() { echo "build_release: ERROR: $*" >&2; exit 1; }
log() { printf '\n== %s\n' "$*" >&2; }
inside() { case "$(realpath -m "$1")/" in "$(realpath -m "$2")"/*) return 0 ;; esac; return 1; }

# ---------------------------------------------------------------- tools
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${BUILD_TOOLS:-$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)}"
for t in aapt2 apksigner zipalign; do
    [[ -n $BT && -x $BT/$t ]] || die "$t not found in build-tools '$BT' (set BUILD_TOOLS or ANDROID_HOME)"
done
export BUILD_TOOLS="$BT"
for c in python3 keytool realpath; do command -v "$c" >/dev/null || die "$c not on PATH"; done

# ---------------------------------------------------------------- signing key (never created here)
[[ -n ${KS_ALIAS:-} ]] || die "KS_ALIAS is not set (alias of the signing key in $KEYSTORE)"
[[ -n ${KS_PASS:-} ]] || die "KS_PASS is not set (keystore password; there is no default)"
if [[ ! -f $KEYSTORE ]]; then
    die "keystore not found: $KEYSTORE
  This script never creates a signing key: the key decides which builds can update each other.
  Point KEYSTORE at your existing keystore, or create one deliberately, e.g.
    keytool -genkeypair -keystore '$KEYSTORE' -alias \"\$KS_ALIAS\" -keyalg RSA -keysize 4096 -validity 10000 -dname CN=toemod"
fi
export KS_PASS KEY_PASS="${KEY_PASS:-$KS_PASS}"
KEY_CERT_SHA256="$(keytool -exportcert -rfc -keystore "$KEYSTORE" -alias "$KS_ALIAS" -storepass:env KS_PASS 2>/dev/null \
    | python3 -c 'import base64, hashlib, re, sys
m = re.search(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", sys.stdin.read(), re.S)
sys.exit(1) if m is None else print(hashlib.sha256(base64.b64decode("".join(m.group(1).split()))).hexdigest())')" \
    || die "cannot read certificate '$KS_ALIAS' from $KEYSTORE (wrong KS_ALIAS or KS_PASS?)"

[[ -d $PULLED_DIR ]] || die "PULLED_DIR is not a directory: $PULLED_DIR"
! inside "$PULLED_DIR" "$OUT_ROOT" || die "PULLED_DIR must not be inside $OUT_ROOT (build output)"
! inside "$KEYSTORE" "$OUT_ROOT" || die "KEYSTORE must not be inside $OUT_ROOT (build output)"

# ---------------------------------------------------------------- compatibility guard (before any build)
log "compatibility guard: $PULLED_DIR"
GUARD="$(python3 "$ROOT_DIR/ci/check_apks.py" "$PULLED_DIR" --build-tools "$BT")" \
    || die "input is not the reviewed build; nothing was built (reason above)"
echo "$GUARD" >&2
field() {
    python3 -c 'import json, sys
v = json.loads(sys.argv[1])[sys.argv[2]]
print("\n".join(v) if isinstance(v, list) else v)' "$GUARD" "$1"
}
BASE_APK="$(field base_apk)"
NATIVE_APK="$(field native_apk)"
APK_LIST="$(field apks)"
mapfile -t APKS <<< "$APK_LIST"

# ---------------------------------------------------------------- builds
log "build_native.sh"
bash "$MOD_DIR/build_native.sh" >&2
[[ -f $LIB ]] || die "build_native.sh produced no $LIB"
log "build_base.sh ($BASE_APK)"
SRC_APK="$PULLED_DIR/$BASE_APK" bash "$MOD_DIR/build_base.sh" >&2
[[ -f $BASE_BUILT ]] || die "build_base.sh produced no $BASE_BUILT"

# repack ORIGINAL SOURCE OUT [LIB]: copy SOURCE (ORIGINAL or the menu-injected base built from it)
#   without old signature/stamp files, with ORIGINAL's compression; optionally add LIB.
# verify ORIGINAL_DIR STAGE NATIVE_APK LIB CERT_SHA256 BUILD_TOOLS APK...: check the signed set.
release_py() {
    python3 - "$ROOT_DIR/ci" "$@" <<'PY'
import hashlib, re, shutil, sys, zipfile, zlib
from pathlib import Path

sys.path.insert(0, sys.argv[1])
import check_apks as ca

STRIP = re.compile(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)|META-INF/MANIFEST\.MF|stamp-cert-sha256", re.I)
DEX = re.compile(r"classes\d*\.dex")
MOD_LIB = "lib/arm64-v8a/" + ca.MOD_LIB_NAME


def fail(msg):
    sys.exit(f"build_release: ERROR: {msg}")


def infos(zf, label):
    ca.entry_names(zf, label)
    return {i.filename: i for i in zf.infolist()}


def file_crc(path):
    crc, size = 0, 0
    with open(path, "rb") as f:
        while chunk := f.read(ca.CHUNK):
            crc, size = zlib.crc32(chunk, crc), size + len(chunk)
    return crc, size


def repack(original, source, out, lib=None):
    injected_base = Path(source).resolve() != Path(original).resolve()
    with zipfile.ZipFile(original) as zr:
        ref = infos(zr, original)
    ref_kept = {n for n in ref if not STRIP.fullmatch(n)}
    with zipfile.ZipFile(source) as zs, zipfile.ZipFile(out, "x") as zo:
        src = infos(zs, source)
        kept = [n for n in src if not STRIP.fullmatch(n)]
        missing, added = ref_kept - set(kept), set(kept) - ref_kept
        if missing:
            fail(f"{source} lost entries of {original}: {sorted(missing)}")
        if added and not (injected_base and len(added) == 1 and all(DEX.fullmatch(n) for n in added)):
            fail(f"{source} has unexpected new entries: {sorted(added)}")
        for n in ref_kept:
            same = (src[n].CRC, src[n].file_size) == (ref[n].CRC, ref[n].file_size)
            if not same and not (injected_base and DEX.fullmatch(n)):
                fail(f"{source}: {n} differs from {original}")
        if lib and (MOD_LIB in src or ca.NATIVE_LIB not in ref):
            fail(f"{original}: cannot add {MOD_LIB} next to {ca.NATIVE_LIB}")
        expected = {}
        for n in kept:
            meta = ref.get(n, src[n])
            info = zipfile.ZipInfo(n, meta.date_time)
            info.compress_type, info.external_attr, info.file_size = meta.compress_type, meta.external_attr, src[n].file_size
            with zs.open(src[n]) as r, zo.open(info, "w") as w:
                shutil.copyfileobj(r, w, ca.CHUNK)
            expected[n] = (src[n].CRC, src[n].file_size, meta.compress_type)
        if lib:
            like = ref[ca.NATIVE_LIB]
            info = zipfile.ZipInfo(MOD_LIB, like.date_time)
            info.compress_type, info.external_attr = like.compress_type, like.external_attr
            with open(lib, "rb") as r, zo.open(info, "w") as w:
                shutil.copyfileobj(r, w, ca.CHUNK)
            expected[MOD_LIB] = (*file_crc(lib), like.compress_type)
    with zipfile.ZipFile(out) as zo:
        got = infos(zo, out)
        if list(got) != list(expected):
            fail(f"{out}: entry list differs from the intended one")
        for n, want in expected.items():
            if (got[n].CRC, got[n].file_size, got[n].compress_type) != want:
                fail(f"{out}: {n} was not copied unchanged")


def verify(original_dir, stage, native_apk, lib, cert_sha256, build_tools, *apks):
    tools = ca.find_build_tools(build_tools)
    entries = {}
    for apk in apks:
        out = Path(stage, apk)
        _, sha256 = ca.verified_signers(tools, out)
        if sha256 != {cert_sha256}:
            fail(f"{apk}: signed by {sorted(sha256)}, expected the keystore certificate {cert_sha256}")
        original, rebuilt = (ca.run_tool([tools.aapt2, "dump", "badging", str(p)])[:2]
                             for p in (Path(original_dir, apk), out))
        if original[0] != 0 or original != rebuilt:
            fail(f"{apk}: aapt2 badging differs from the original")
        with ca.open_apk(out) as zf:
            entries[apk] = ca.entry_names(zf, apk)
        leftovers = [n for n in entries[apk] if n == "stamp-cert-sha256"]
        if leftovers:
            fail(f"{apk}: still contains {leftovers}")
    if ca.check_pins(Path(stage), entries, ca.load_baseline()) != native_apk:
        fail(f"{ca.NATIVE_LIB} moved out of {native_apk}")
    holders = [apk for apk, names in entries.items() if MOD_LIB in names]
    if holders != [native_apk]:
        fail(f"{MOD_LIB} expected only in {native_apk}, found in {holders}")
    with ca.open_apk(Path(stage, native_apk)) as zf:
        packed = ca.sha256_entry(zf, MOD_LIB, native_apk)
    built = hashlib.sha256(Path(lib).read_bytes()).hexdigest(), Path(lib).stat().st_size
    if packed != built:
        fail(f"{native_apk}: {MOD_LIB} differs from {lib}")


try:
    {"repack": repack, "verify": verify}[sys.argv[2]](*sys.argv[3:])
except ca.Incompatible as e:
    fail(str(e))
PY
}

# ---------------------------------------------------------------- repack, align, sign (staging dir)
mkdir -p "$OUT_ROOT"
STAGE="$(mktemp -d "$OUT_ROOT/release.tmp.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

for apk in "${APKS[@]}"; do
    src="$PULLED_DIR/$apk"
    if [[ $apk == "$BASE_APK" ]]; then src="$BASE_BUILT"; fi
    lib=()
    if [[ $apk == "$NATIVE_APK" ]]; then lib=("$LIB"); fi
    log "repack $apk${lib:+ (+ libtoemod.so)}"
    release_py repack "$PULLED_DIR/$apk" "$src" "$STAGE/$apk.unsigned" "${lib[@]}"
    "$BT/zipalign" -f -P 16 4 "$STAGE/$apk.unsigned" "$STAGE/$apk"
    rm "$STAGE/$apk.unsigned"
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
        --v4-signing-enabled false "$STAGE/$apk"
    "$BT/zipalign" -c -P 16 4 "$STAGE/$apk" >/dev/null || die "$apk is not aligned after signing"
done

log "verify signed set (signer $KEY_CERT_SHA256)"
release_py verify "$PULLED_DIR" "$STAGE" "$NATIVE_APK" "$LIB" "$KEY_CERT_SHA256" "$BT" "${APKS[@]}"

chmod 755 "$STAGE"
rm -rf "$OUT"
mv "$STAGE" "$OUT"
trap - EXIT

echo "Install with:"
printf '  adb install-multiple -r'
printf ' %q' "${APKS[@]/#/$OUT/}"
echo
