# truckers-of-europe-3-mod

Runtime mod + floating in-game menu for **Truckers of Europe 3** (Android, Unity 2022.3 / IL2CPP, arm64).

A native library (`libtoemod.so`) inline-hooks and byte-patches `libil2cpp.so` at runtime; a small Java overlay
(`com.toemod.Menu`, injected into `UnityPlayerActivity.onCreate`) lets you toggle each feature and change its value
while playing. No root, no overlay permission.

> Work in progress. This repo contains only the mod's own code — no game files. You need your own copy of the game.

## Layout

| Path | What |
|---|---|
| `work/mod/src/mod.h` | Module API: `FEATURE`, `INSTALL`, `HOOK`, `BytePatch`, `FIELD`, `VA` |
| `work/mod/src/main.cpp` | Core: finds `libil2cpp.so`, hooks (And64InlineHook), byte patches, feature registry, JNI |
| `work/mod/src/*.cpp` | Feature modules (economy, fuel, damage, fatigue, jobs, shop, perf, world) |
| `work/mod/java/` | Floating menu (plain Android views) |
| `work/mod/third_party/` | [And64InlineHook](https://github.com/Rprop/And64InlineHook) (MIT) |
| `work/re.py` | RE helper over the Cpp2IL dump (find / disassemble / callers / field xrefs) |
| `work/mod/claims.txt` | Which function entries / byte ranges each module owns |

## Target build

- Package `com.WandaSoftware.TruckersofEurope3`, version `0.7.64` (versionCode 193), arm64-v8a split.
- All addresses are hard-coded for this build (`vaddr = Cpp2IL RVA + 0x4000`). Other versions need re-mapping.

## Reproducing

1. Pull the installed split APKs from your device (`adb shell pm path com.WandaSoftware.TruckersofEurope3`, `adb pull` each).
2. Dump metadata: extract `libil2cpp.so` + `global-metadata.dat`, run
   [Cpp2IL](https://github.com/SamboyCoding/Cpp2IL) with `--use-processor attributeinjector --output-as diffable-cs --output-to work/cs`.
3. Build `libtoemod.so` with the Android NDK, inject the menu into `base.apk`, add the library to the arm64 split,
   sign all splits with one key and `adb install-multiple`.

Re-signing breaks Google Play Games sign-in / cloud save (certificate mismatch), and switching from the store build
requires uninstalling it (local save is lost).

## Disclaimer

For personal, offline use on your own device. Not affiliated with Wanda Software.
