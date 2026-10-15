#!/usr/bin/env bash
# Build libtoemod.so (arm64-v8a) from work/mod/src/*.cpp with the Android NDK.
set -euo pipefail
cd "$(dirname "$0")"

NDK="${ANDROID_NDK_HOME:-$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)}"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang++"
OUT=out/lib/arm64-v8a
mkdir -p "$OUT"

"$CXX" -std=c++17 -O2 -fPIC -shared -fvisibility=hidden -fno-exceptions -fno-rtti \
    -Wall -Wno-unused-function -Wno-unused-variable \
    -static-libstdc++ -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--gc-sections -s \
    -Isrc src/*.cpp third_party/And64InlineHook.cpp \
    -llog -o "$OUT/libtoemod.so"

echo "$PWD/$OUT/libtoemod.so"
