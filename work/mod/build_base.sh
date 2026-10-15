#!/usr/bin/env bash
# Inject the toemod floating menu into the ORIGINAL base.apk.
#
#   bash work/mod/build_base.sh   ->   work/mod/out/base-unsigned.apk   (unsigned, not zipaligned)
#
# Steps: javac (Java 8 bytecode) -> d8 (min-api 23) -> apktool d -r (smali for main dex only,
# resources kept raw) -> call Menu.start(this) right after super.onCreate() in
# UnityPlayerActivity -> menu dex added as the next raw classesN.dex -> apktool b -> verify.
#
# Overrides: SRC_APK, APKTOOL (path or command, default: apktool on PATH), BUILD_TOOLS (default: newest
# build-tools), ANDROID_JAR (default: newest platforms/*/android.jar), ANDROID_HOME/ANDROID_SDK_ROOT,
# KEEP_BUILD=1 (keep decoded tree).
set -euo pipefail

MOD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$MOD_DIR/../.." && pwd)"
SRC_APK="${SRC_APK:-$ROOT_DIR/com.WandaSoftware.TruckersofEurope3/base.apk}"
OUT_DIR="$MOD_DIR/out"
OUT_APK="$OUT_DIR/base-unsigned.apk"
BUILD="$OUT_DIR/build_base"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="${BUILD_TOOLS:-$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)}"
ANDROID_JAR="${ANDROID_JAR:-$(find "$SDK/platforms" -mindepth 2 -maxdepth 2 -name android.jar 2>/dev/null | sort -V | tail -n 1 || true)}"
APKTOOL_REQ="${APKTOOL:-apktool}"
APKTOOL="$(command -v "$APKTOOL_REQ" || true)"

die() { echo "build_base: ERROR: $*" >&2; exit 1; }
log() { printf '\n== %s\n' "$*"; }
pick() {
    local f
    for f in "$@"; do [[ -e $f ]] && { echo "$f"; return 0; }; done
    die "none of these exist: $*"
}

[[ -n $BT && -d $BT ]] || die "Android build-tools not found (set BUILD_TOOLS or ANDROID_HOME)"
[[ -f $ANDROID_JAR ]] || die "android.jar not found (set ANDROID_JAR or install a platform under $SDK/platforms)"
D8="$(pick "$BT/d8" "$SDK/cmdline-tools/latest/bin/d8")"
AAPT2="$(pick "$BT/aapt2")"
DEXDUMP="$(pick "$BT/dexdump")"
[[ -n $APKTOOL && -x $APKTOOL ]] || die "apktool not found: $APKTOOL_REQ (set APKTOOL or put apktool on PATH)"
[[ -f $SRC_APK ]] || die "source APK not found: $SRC_APK"
case "$(realpath -m "$SRC_APK")" in
    "$(realpath -m "$OUT_DIR")"/*) die "SRC_APK must not be inside $OUT_DIR (build output)" ;;
esac
command -v javac >/dev/null || die "javac not on PATH"
command -v unzip >/dev/null || die "unzip not on PATH"

rm -rf "$BUILD" "$OUT_APK"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/verify"

# ---------------------------------------------------------------- menu classes -> dex
log "javac ($ANDROID_JAR)"
mapfile -t JAVA_SRCS < <(find "$MOD_DIR/java" -name '*.java' | sort)
(( ${#JAVA_SRCS[@]} > 0 )) || die "no Java sources under $MOD_DIR/java"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -encoding UTF-8 \
      -Xlint:all,-options -Werror -d "$BUILD/classes" "${JAVA_SRCS[@]}"

log "d8 ($D8)"
mapfile -t CLASS_FILES < <(find "$BUILD/classes" -name '*.class' | sort)
"$D8" --release --min-api 23 --lib "$ANDROID_JAR" --output "$BUILD/dex" "${CLASS_FILES[@]}"
[[ -f $BUILD/dex/classes.dex ]] || die "d8 produced no classes.dex"

# ---------------------------------------------------------------- decode
log "apktool d -r --only-main-classes $SRC_APK"
"$APKTOOL" d -r --only-main-classes -f -o "$BUILD/apk" "$SRC_APK"
APK_DIR="$BUILD/apk"

# ---------------------------------------------------------------- patch UnityPlayerActivity.onCreate
shopt -s nullglob
ACTS=("$APK_DIR"/smali*/com/unity3d/player/UnityPlayerActivity.smali)
shopt -u nullglob
(( ${#ACTS[@]} == 1 )) || die "expected exactly one UnityPlayerActivity.smali, found ${#ACTS[@]}"
ACT="${ACTS[0]}"
! grep -q 'Lcom/toemod/' "$ACT" || die "$ACT already references com.toemod"
log "patching ${ACT#"$APK_DIR"/}"

# Insert right after the first invoke-super ...->onCreate(Bundle) in onCreate(Bundle). The
# receiver of that invoke-super must be p0, so p0 is guaranteed to hold `this` at that point.
awk '
    /^\.method .*[ ]onCreate\(Landroid\/os\/Bundle;\)V$/ { in_m = 1 }
    { print }
    in_m && !done && /^[ \t]*invoke-super(\/range)? \{p0[ ,}].*;->onCreate\(Landroid\/os\/Bundle;\)V$/ {
        print ""
        print "    invoke-static {p0}, Lcom/toemod/Menu;->start(Landroid/app/Activity;)V"
        done = 1
    }
    in_m && /^\.end method/ { in_m = 0 }
    END { if (!done) exit 3 }
' "$ACT" > "$ACT.new" || die "onCreate/invoke-super pattern not found in $ACT"
mv "$ACT.new" "$ACT"
[[ $(grep -c 'Lcom/toemod/Menu;->start(Landroid/app/Activity;)V' "$ACT") == 1 ]] || die "injection count != 1"

# ---------------------------------------------------------------- keep untouched dex files raw
# Only the dex holding UnityPlayerActivity goes through the smali round trip; every other dex is
# restored byte-for-byte from the source APK (apktool b copies root-level classes*.dex raw).
dex_of() { local s="${1##*/}"; if [[ $s == smali ]]; then echo classes.dex; else echo "${s#smali_}.dex"; fi; }
ACT_SMALI_DIR="${ACT#"$APK_DIR"/}"
ACT_SMALI_DIR="${ACT_SMALI_DIR%%/*}"
PATCHED_DEX="$(dex_of "$ACT_SMALI_DIR")"
RAW_DEX=()
for d in "$APK_DIR"/smali "$APK_DIR"/smali_classes*; do
    [[ -d $d && ${d##*/} != "$ACT_SMALI_DIR" ]] || continue
    dex="$(dex_of "$d")"
    rm -rf "$d"
    unzip -q -o "$SRC_APK" "$dex" -d "$APK_DIR"
    RAW_DEX+=("$dex")
done
echo "re-assembled from smali: $PATCHED_DEX; kept raw: ${RAW_DEX[*]}"

# ---------------------------------------------------------------- add menu dex
# apktool b copies root-level *.dex (other than classes.dex) into the APK unchanged.
n=2
while [[ -e $APK_DIR/smali_classes$n || -e $APK_DIR/classes$n.dex ]]; do n=$((n + 1)); done
MENU_DEX="classes$n.dex"
cp "$BUILD/dex/classes.dex" "$APK_DIR/$MENU_DEX"
log "menu dex -> $MENU_DEX"

# ---------------------------------------------------------------- build
log "apktool b -> $OUT_APK"
"$APKTOOL" b -f -o "$OUT_APK" "$APK_DIR"
[[ -f $OUT_APK ]] || die "apktool b produced no APK"

# ---------------------------------------------------------------- verify
log "verify: aapt2 dump badging (must be identical to the original)"
"$AAPT2" dump badging "$SRC_APK" > "$BUILD/verify/badging.orig"
"$AAPT2" dump badging "$OUT_APK" > "$BUILD/verify/badging.new" || die "aapt2 dump badging failed on output"
diff -u "$BUILD/verify/badging.orig" "$BUILD/verify/badging.new" || die "badging differs from original"
grep -E "^(package|launchable-activity|minSdkVersion|targetSdkVersion):" "$BUILD/verify/badging.new"

log "verify: zip entries (original vs output)"
unzip -Z1 "$SRC_APK" | sort > "$BUILD/verify/entries.orig"
unzip -Z1 "$OUT_APK" | sort > "$BUILD/verify/entries.new"
comm -23 "$BUILD/verify/entries.orig" "$BUILD/verify/entries.new" > "$BUILD/verify/entries.removed"
comm -13 "$BUILD/verify/entries.orig" "$BUILD/verify/entries.new" > "$BUILD/verify/entries.added"
echo "removed: $(tr '\n' ' ' < "$BUILD/verify/entries.removed")"
echo "added:   $(tr '\n' ' ' < "$BUILD/verify/entries.added")"
# Only signature artifacts may disappear (old v1 files + Play source stamp; both are invalid once
# the APK is re-signed); only the menu dex may appear.
! grep -qv -E '^(META-INF/[^/]+\.(SF|RSA|DSA|EC|MF)|stamp-cert-sha256)$' "$BUILD/verify/entries.removed" \
    || die "unexpected entries removed"
[[ $(cat "$BUILD/verify/entries.added") == "$MENU_DEX" ]] || die "unexpected entries added"

log "verify: entries that must stay uncompressed (targetSdk >= 30 needs stored resources.arsc)"
unzip -v "$OUT_APK" > "$BUILD/verify/zipinfo"
for e in resources.arsc "$MENU_DEX"; do
    method="$(awk -v e="$e" '$8 == e {print $2}' "$BUILD/verify/zipinfo")"
    echo "  $e: $method"
    [[ $method == Stored ]] || die "$e is not stored uncompressed"
done

log "verify: classes in $MENU_DEX (from the output APK)"
unzip -q -o "$OUT_APK" 'classes*.dex' -d "$BUILD/verify/dex"
cmp -s "$BUILD/verify/dex/$MENU_DEX" "$BUILD/dex/classes.dex" || die "$MENU_DEX in APK differs from d8 output"
"$DEXDUMP" "$BUILD/verify/dex/$MENU_DEX" > "$BUILD/verify/menu.dump"
grep "Class descriptor" "$BUILD/verify/menu.dump" | sed "s/.*: '\(.*\)'/  \1/"
for c in 'Lcom/toemod/Menu;' 'Lcom/toemod/Native;' 'Lcom/toemod/Feature;'; do
    grep -q "Class descriptor  : '$c'" "$BUILD/verify/menu.dump" || die "class $c missing"
done
awk '/Class descriptor  : .Lcom\/toemod\/Menu;./ {c = 1} c && /name +: .start./ {m = 1} m && /type +:/ {print "  Menu.start type: " $NF; exit}' \
    "$BUILD/verify/menu.dump" | tee "$BUILD/verify/start.sig"
grep -q "'(Landroid/app/Activity;)V'" "$BUILD/verify/start.sig" || die "Menu.start(Activity) missing"

log "verify: untouched dex files are byte-identical to the original"
for dex in "${RAW_DEX[@]}"; do
    unzip -p "$SRC_APK" "$dex" > "$BUILD/verify/orig.dex"
    cmp -s "$BUILD/verify/orig.dex" "$BUILD/verify/dex/$dex" || die "$dex differs from the original"
    echo "  $dex: identical"
done

log "verify: compiled UnityPlayerActivity.onCreate in the output APK (dexdump -d)"
found=0
for d in "$BUILD"/verify/dex/classes*.dex; do
    "$DEXDUMP" -d "$d" | awk '
        /\|\[[0-9a-f]+\] com\.unity3d\.player\.UnityPlayerActivity\.onCreate:\(Landroid\/os\/Bundle;\)V/ {p = 1}
        p && /^ +catches/ {p = 0}
        p {print}
    ' > "$BUILD/verify/oncreate.dump"
    if [[ -s $BUILD/verify/oncreate.dump ]]; then
        echo "  (${d##*/})"
        sed 's/^[0-9a-f]*: [0-9a-f ]*|/    /' "$BUILD/verify/oncreate.dump"
        awk '/invoke-super .*Landroid\/app\/Activity;\.onCreate:/ {s = NR} /invoke-static .*Lcom\/toemod\/Menu;\.start:\(Landroid\/app\/Activity;\)V/ {if (s && NR == s + 1) ok = 1} END {exit !ok}' \
            "$BUILD/verify/oncreate.dump" || die "Menu.start is not the instruction right after super.onCreate"
        found=$((found + 1))
    fi
done
(( found == 1 )) || die "UnityPlayerActivity.onCreate found in $found dex files (expected 1)"

log "patched onCreate smali (${ACT#"$APK_DIR"/})"
sed -n '/^\.method .* onCreate(Landroid\/os\/Bundle;)V$/,/^\.end method/p' "$ACT"

if [[ ${KEEP_BUILD:-0} != 1 ]]; then rm -rf "$BUILD"; fi

log "OK"
echo "$OUT_APK"
