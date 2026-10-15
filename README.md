# truckers-of-europe-3-mod

Runtime mod + floating in-game menu for **Truckers of Europe 3** (Android, Unity 2022.3 / IL2CPP, arm64).

A native library (`libtoemod.so`) inline-hooks and byte-patches `libil2cpp.so` at runtime; a small Java overlay
(`com.toemod.Menu`, injected into `UnityPlayerActivity.onCreate`) toggles each feature and edits its value while
playing. No root, no overlay permission, `libil2cpp.so` itself is shipped unmodified.

> This repo contains only the mod's own code — no game files. You need your own copy of the game.

## Features

| Category | Feature id | What it does | Default |
|---|---|---|---|
| Economy | `money`, `gold`, `wash_tickets`, `level`, `xp` | Override the value the game reads (spending never reduces it) | money/gold/tickets/level on |
| Economy | `ad_free`, `unlock_trailers` | Force the paid flags | off |
| Damage | `no_rain_dirt` | Truck/trailer don't get dirty from rain | on |
| Damage | `no_truck_damage`, `no_trailer_damage` | Collisions don't lower truck health / raise trailer+cargo damage | off |
| Damage | `free_repair`, `instant_repair` | Garage + roadside repair cost 0; repair bar completes immediately | off |
| Fuel | `inf_fuel`, `free_fuel` | No fuel consumption; refuelling (station + roadside) costs 0 | off |
| Driver | `no_fatigue` | Fatigue always reads "rested": no blackouts / sleep nag; sleeping still works | off |
| Jobs | `no_penalties` | No late or damage penalty on delivery | off |
| Jobs | `pay_mult`, `xp_mult` | Multiply job income / XP (1–20×) | off |
| Jobs | `bonus_no_ad` | Every rewarded-ad option grants its reward without an ad | off |
| Shopping | `free_shop` | Trucks, parts, interior, trailers, garages and upgrades cost 0 (money + gold) | off |
| Shopping | `free_travel` | Ferry, toll gates and tow truck cost 0 | off |
| Truck | `engine_mult` | Engine torque/power multiplier (1–10×), rev limit preserved | off |
| Truck | `no_speed_limit` | Disable the >200 km/h auto-tow | off |
| World | `weather` | Force Clear / Rainy / Cloudy / Foggy | on, Rainy |
| World | `time_freeze`, `time_hour` | Freeze the clock / set the hour | off |
| World | `traffic` | AI traffic cap (0 = none) | off |

Settings persist in SharedPreferences (`toemod`). The "Mods enabled" master switch turns everything off at once.

## Layout

| Path | What |
|---|---|
| `work/mod/src/mod.h` | Module API: `FEATURE`, `INSTALL`, `HOOK`, `BytePatch`, `FIELD`, `VA` |
| `work/mod/src/main.cpp` | Core: finds `libil2cpp.so`, hooks (And64InlineHook), verified byte patches, registry, JNI |
| `work/mod/src/*.cpp` | Feature modules (economy, fuel, damage, fatigue, jobs, shop, perf, world); each documents its evidence |
| `work/mod/java/com/toemod/` | Floating menu (plain Android views) |
| `work/mod/claims.txt` | Function entries / byte ranges owned by each module (no two modules touch the same code) |
| `work/mod/build_native.sh` | NDK build of `libtoemod.so` |
| `work/mod/build_base.sh` | apktool injection of the menu into `base.apk` |
| `work/mod/build_release.sh` | Builds, aligns and signs the full split set |
| `work/re.py` | RE helper over the Cpp2IL dump (find / disassemble / callers / field xrefs) |
| `work/mod/third_party/` | [And64InlineHook](https://github.com/Rprop/And64InlineHook) (MIT) |

## Target build

Package `com.WandaSoftware.TruckersofEurope3`, version `0.7.64` (versionCode 193), arm64-v8a. Addresses are
hard-coded for this build (`vaddr = Cpp2IL RVA + 0x4000`); byte patches verify the original bytes before writing
and refuse on mismatch, but other game versions need re-mapping.

## Build & install

Requirements: Android SDK build-tools, NDK (r27+), JDK, `apktool`, Python 3, `adb`.

```bash
# 1. pull your installed game's splits into ./com.WandaSoftware.TruckersofEurope3/
adb shell pm path com.WandaSoftware.TruckersofEurope3   # then adb pull each path
# 2. (optional, for RE) extract libil2cpp.so + global-metadata.dat into work/ and run Cpp2IL:
#    cpp2il --use-processor attributeinjector --output-as diffable-cs --output-to work/cs ...
# 3. build everything (creates build/mod.keystore if missing; override with KEYSTORE/KS_PASS/KS_ALIAS)
bash work/mod/build_release.sh
# 4. first time: uninstall the store version (signature differs; local save is lost)
adb install-multiple -r work/mod/out/release/*.apk
```

Re-signing breaks Google Play Games sign-in / cloud save (OAuth client is bound to the developer's certificate;
this applies to microG too).

## Disclaimer

For personal, offline use on your own device. Not affiliated with Wanda Software.
