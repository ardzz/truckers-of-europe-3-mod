#!/usr/bin/env bash
# Build libtoemod.so (arm64-v8a) from work/mod/src/*.cpp with the Android NDK.
# NDK: ANDROID_NDK_HOME, else the newest ndk/* of ANDROID_HOME / ANDROID_SDK_ROOT / ~/Android/Sdk.
set -euo pipefail
cd "$(dirname "$0")"

die() { echo "build_native: ERROR: $*" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)}"
[[ -n $NDK && -d $NDK ]] || die "Android NDK not found (set ANDROID_NDK_HOME or install one under $SDK/ndk)"
case "$(uname -s)" in
    Linux) HOST=linux-x86_64 ;;
    Darwin) HOST=darwin-x86_64 ;;
    *) die "unsupported build host $(uname -s)" ;;
esac
CXX="$NDK/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android23-clang++"
[[ -x $CXX ]] || die "NDK compiler not found: $CXX"
OUT=out/lib/arm64-v8a
mkdir -p "$OUT"

"$CXX" -std=c++17 -O2 -fPIC -shared -fvisibility=hidden -fno-exceptions -fno-rtti \
    -Wall -Wno-unused-function -Wno-unused-variable \
    -static-libstdc++ -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--gc-sections -s \
    -Isrc src/*.cpp third_party/And64InlineHook.cpp \
    -llog -o "$OUT/libtoemod.so"

echo "$PWD/$OUT/libtoemod.so"
