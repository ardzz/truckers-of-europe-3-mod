#!/usr/bin/env bash
# Build the installable, re-signed APK set from an original (Play-signed) APK set.
#
#   KS_ALIAS=... KS_PASS=... bash work/mod/build_release.sh   ->   work/mod/out/release/*.apk
#
# 1. ci/check_apks.py runs first: nothing is built unless libil2cpp.so, global-metadata.dat and the
#    other pinned files are byte-identical to the reviewed build and every APK is Play-signed.
# 2. build_native.sh (libtoemod.so) and build_base.sh (menu injected into the base APK).
# 3. APKs retain their original entries/compression except invalid signature artifacts, patched base
#    DEX, exact verified launcher/installer manifest edits and icon PNGs. Every APK gets the same
#    signed assets/toemod/build.json identity; only the native holder gets libtoemod.so.
# 4. zipalign + apksigner, then verify signer == keystore, alignment, exact intended manifest/icon/
#    metadata changes and unchanged native pins. out/release is replaced only after all checks pass.
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

# repack ORIGINAL SOURCE OUT BUILD_INFO_BASE [LIB]: the only additions are the menu dex (base),
#   the same signed build identity (all APKs) and LIB (native holder). Branding is exact, not a wildcard.
# verify ORIGINAL_DIR STAGE BASE_APK NATIVE_APK LIB CERT_SHA256 BUILD_TOOLS APK...: check the signed set.
release_py() {
    python3 - "$ROOT_DIR/ci" "$@" <<'PY'
import hashlib, re, shutil, sys, zipfile, zlib
from pathlib import Path

sys.path.insert(0, sys.argv[1])
import check_apks as ca
import manifest_branding as mb
import update_manifest as um

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


def repack(original, source, out, build_info_base, lib=None):
    injected_base = Path(source).resolve() != Path(original).resolve()
    with zipfile.ZipFile(original) as zr:
        ref = infos(zr, original)
    ref_kept = {n for n in ref if not STRIP.fullmatch(n)}
    with zipfile.ZipFile(build_info_base) as zb:
        identity_bytes = um.read_build_info(zb, build_info_base)
    um.validate_identity(um.parse_json(identity_bytes, um.BUILD_INFO, um.MAX_BUILD_INFO_SIZE))
    branded = {}
    if injected_base:
        tools = ca.find_build_tools()
        branded = mb.icon_plan(tools, original)
        with zipfile.ZipFile(original) as zr:
            branded[mb.MANIFEST] = mb.patch_manifest(zr.read(mb.MANIFEST))
    with zipfile.ZipFile(original) as zip_ref, zipfile.ZipFile(source) as zs, zipfile.ZipFile(out, "x") as zo:
        src = infos(zs, source)
        kept = [n for n in src if not STRIP.fullmatch(n)]
        missing, added = ref_kept - set(kept), set(kept) - ref_kept
        if missing:
            fail(f"{source} lost entries of {original}: {sorted(missing)}")
        dex_added = {n for n in added if DEX.fullmatch(n)}
        expected_added = {um.BUILD_INFO, *dex_added} if injected_base else set()
        if added != expected_added or (injected_base and len(dex_added) != 1):
            fail(f"{source} has unexpected new entries: {sorted(added)}")
        if um.BUILD_INFO in ref:
            fail(f"{original} already contains signed mod build metadata")
        if injected_base and um.read_build_info(zs, source) != identity_bytes:
            fail(f"{source}: build metadata differs from {build_info_base}")
        for n in ref_kept:
            if n in branded:
                if zs.read(n) != branded[n]:
                    fail(f"{source}: {n} is not the exact intended branding edit")
            elif not (injected_base and DEX.fullmatch(n)):
                if ca.sha256_entry(zs, n, source) != ca.sha256_entry(zip_ref, n, original):
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
        if um.BUILD_INFO not in src:
            info = zipfile.ZipInfo(um.BUILD_INFO)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zo.writestr(info, identity_bytes)
            expected[um.BUILD_INFO] = (zlib.crc32(identity_bytes), len(identity_bytes), info.compress_type)
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


def verify(original_dir, stage, base_apk, native_apk, lib, cert_sha256, build_tools, *apks):
    tools = ca.find_build_tools(build_tools)
    entries = {}
    with ca.open_apk(Path(stage, base_apk)) as zf:
        identity_bytes = um.read_build_info(zf, base_apk)
    badging = ca.read_badging(tools, Path(stage, base_apk))
    um.validate_identity(um.parse_json(identity_bytes, um.BUILD_INFO, um.MAX_BUILD_INFO_SIZE),
                         badging["name"], int(badging["versionCode"]), badging["versionName"], list(apks))
    for apk in apks:
        out = Path(stage, apk)
        _, sha256 = ca.verified_signers(tools, out)
        if sha256 != {cert_sha256}:
            fail(f"{apk}: signed by {sorted(sha256)}, expected the keystore certificate {cert_sha256}")
        original, rebuilt = (ca.run_tool([tools.aapt2, "dump", "badging", str(p)])[:2]
                             for p in (Path(original_dir, apk), out))
        if apk == base_apk:
            mb.verify(tools, Path(original_dir, apk), out)
        elif original[0] != 0 or original != rebuilt:
            fail(f"{apk}: aapt2 badging differs from the original")
        with ca.open_apk(out) as zf:
            entries[apk] = ca.entry_names(zf, apk)
            if um.read_build_info(zf, apk) != identity_bytes:
                fail(f"{apk}: signed build identity differs from {base_apk}")
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
    release_py repack "$PULLED_DIR/$apk" "$src" "$STAGE/$apk.unsigned" "$BASE_BUILT" "${lib[@]}"
    "$BT/zipalign" -f -P 16 4 "$STAGE/$apk.unsigned" "$STAGE/$apk"
    rm "$STAGE/$apk.unsigned"
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
        --v4-signing-enabled false "$STAGE/$apk"
    "$BT/zipalign" -c -P 16 4 "$STAGE/$apk" >/dev/null || die "$apk is not aligned after signing"
done

log "verify signed set (signer $KEY_CERT_SHA256)"
release_py verify "$PULLED_DIR" "$STAGE" "$BASE_APK" "$NATIVE_APK" "$LIB" "$KEY_CERT_SHA256" "$BT" "${APKS[@]}"

chmod 755 "$STAGE"
rm -rf "$OUT"
mv "$STAGE" "$OUT"
trap - EXIT

echo "Install with:"
printf '  adb install-multiple -r'
printf ' %q' "${APKS[@]/#/$OUT/}"
echo
