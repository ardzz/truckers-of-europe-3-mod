#!/usr/bin/env bash
# Build the full installable split set:
#   base.apk                      <- menu injected (build_base.sh)
#   split_config.arm64_v8a.apk    <- original + lib/arm64-v8a/libtoemod.so (libil2cpp.so untouched)
#   split_UnityDataAssetPack.apk  <- original, re-signed
# All three are zipaligned and signed with the same key.
#
# Env: PULLED_DIR (dir with the original pulled splits), KEYSTORE, KS_PASS, KS_ALIAS
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
PULLED_DIR="${PULLED_DIR:-$ROOT/com.WandaSoftware.TruckersofEurope3}"
KEYSTORE="${KEYSTORE:-$ROOT/build/mod.keystore}"
KS_PASS="${KS_PASS:-modpass}"
KS_ALIAS="${KS_ALIAS:-mod}"
BT="${BUILD_TOOLS:-$(ls -d "$HOME"/Android/Sdk/build-tools/* | sort -V | tail -1)}"
OUT=out/release
rm -rf "$OUT" && mkdir -p "$OUT"

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" -dname CN=toemod >/dev/null
fi

bash build_native.sh >/dev/null
SRC_APK="$PULLED_DIR/base.apk" bash build_base.sh >/dev/null

python3 - "$PULLED_DIR/split_config.arm64_v8a.apk" out/lib/arm64-v8a/libtoemod.so "$OUT/cfg.unsigned.apk" <<'EOF'
import sys, zipfile
src, lib, dst = sys.argv[1:]
sig = ('.SF', '.RSA', '.EC', '.DSA', 'MANIFEST.MF')
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, 'w') as zo:
    for it in zi.infolist():
        if it.filename.startswith('META-INF/') and it.filename.upper().endswith(sig):
            continue
        zo.writestr(it, zi.read(it), compress_type=it.compress_type)
    zo.write(lib, 'lib/arm64-v8a/libtoemod.so', compress_type=zipfile.ZIP_DEFLATED)
EOF

cp out/base-unsigned.apk "$OUT/base.unsigned.apk"
cp "$PULLED_DIR/split_UnityDataAssetPack.apk" "$OUT/asset.unsigned.apk"

sign() {  # $1 unsigned, $2 final name
    "$BT/zipalign" -f -P 16 4 "$OUT/$1" "$OUT/$2"
    rm "$OUT/$1"
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
        --ks-key-alias "$KS_ALIAS" --v4-signing-enabled false "$OUT/$2"
    "$BT/apksigner" verify "$OUT/$2" 2>/dev/null
}
sign base.unsigned.apk base.apk
sign cfg.unsigned.apk split_config.arm64_v8a.apk
sign asset.unsigned.apk split_UnityDataAssetPack.apk

echo "Install with:"
echo "  adb install-multiple -r $PWD/$OUT/base.apk $PWD/$OUT/split_UnityDataAssetPack.apk $PWD/$OUT/split_config.arm64_v8a.apk"
