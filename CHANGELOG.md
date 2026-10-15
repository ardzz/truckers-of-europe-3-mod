# Changelog

Notable changes to TOE3 Stormline, newest first. Releases embed the matching section below—not just a link—alongside the automatically generated commit history. If a version has no curated entry, CI groups its commit messages into change categories.

Mod versions use **Semantic Versioning**, starting at `v0.1.0`, independently of the upstream game's version. CI derives the next version from build-input commits: breaking changes raise major, `feat:` raises minor, and other changes raise patch. A compatible upstream-only rebuild raises patch; unchanged inputs are skipped. Numeric `mod_build` remains the OTA ordering counter. Historical `toe3-v…-b…-mod-…` tags stay intact.

## [v0.1.0] - 2026-10-15

Game 0.7.64 (build 193), CI build 8.

### Added

- Independent semantic mod versions in release tags, release titles and the in-game version display. Game versions and CI build numbers remain separate.
- Automatic major/minor/patch selection from commit messages, with unchanged-build skipping.

### Fixed

- Release pages now include the version's Added, Changed, Fixed and verification notes instead of showing only a commit subject and a link to this file.
- Changelog subheadings stay inside the section consumed by the in-game reader, so the detailed notes can be read without opening GitHub.

### Compatibility

- OTA keeps schema 1 and its exact fields. Semantic versions are derived from the tag rather than adding a field that older updaters would reject.
- The package, signing identity, split inventory and numeric rollback checks are unchanged. Historical release tags and APK assets are not rewritten.

### Verified

- The 161-test host suite, Java compilation and the [signed release pipeline](https://github.com/ardzz/truckers-of-europe-3-mod/actions/runs/38766505229/attempts/1) passed.
- Unmodified build 6 and build 7 updater policies accepted the published `v0.1.0` manifest and the downloaded base APK's embedded identity, while still rejecting a mixed legacy APK identity. Build 7's existing reader received both Added and Fixed notes.
- A [release rerun](https://github.com/ardzz/truckers-of-europe-3-mod/actions/runs/38766505229/attempts/2) loaded the published semantic manifest and skipped downloading, building and publishing unchanged inputs.

## [toe3-v0.7.64-b193-mod-7f0d995] - 2026-10-15

Game 0.7.64 (build 193), mod build 7. Source [`7f0d995`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/7f0d9954d7501ccb32076848a29b17d1d6b0705a).

### Added

- Focused Home, mod-category, World, Updates and Debug screens instead of one long accordion.
- Read-only Runtime, Weather and Truck diagnostics with explicit unavailable/stale states and sample ages.
- A Changelog dialog inside the menu, using the automatic commit-message notes already fetched by the update check.
- Exact numeric value entry alongside sliders, with visible range validation.
- Real Huawei tablet screenshots and a README demo gallery, separate from the promotional artwork.

### Fixed

- Weather status no longer claims the requested rain is active while its intensity is still being restored.
- Weather requests now steer the running game transition on the next sky update instead of waiting for the previous choice to finish first. Existing fade and AfterRain drying stages still control the visible effects.
- A rain request suppressed by a StopRain area is reported as suppressed even after the game settles to Clear.

### Verified

- Focused screens, live Runtime/Weather/Truck readings, the in-game release changelog, collapse/reopen and activity resume on the Huawei Android 12 tablet.
- One Rainy selection during Foggy weather reached Rainy without another toggle; the game's fade through Clear and ramp took about four minutes.

## [toe3-v0.7.64-b193-mod-da547e1] - 2026-10-15

Game 0.7.64 (build 193), mod build 6. Source [`da547e1`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/da547e154c38693de033e2a809725a68e48f1212).

### Changed

- The mod is now called **TOE3 Stormline**: launcher label, menu title, release titles and release notes. The package name and signing key are unchanged, so it still installs over the existing mod.
- Release titles include the mod build number, so builds for the same game version can be told apart.

### Added

- This changelog, and a **Changes in this build** section in every release generated from the commit history since the previous published release.

### Verified

- A successful in-game OTA installation on the Huawei tablet was confirmed after this release.

## [toe3-v0.7.64-b193-mod-a57fa36] - 2026-10-15

Game 0.7.64 (build 193). Source [`a57fa36`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/a57fa36068c8083b214484e4001128d75c9b668e).

### Added

- In-game updates: the menu's **Updates** section checks the latest GitHub release, downloads the complete split APK set, verifies file sizes, SHA-256 hashes, the installed signing certificate and the build identity, then asks Android to install it. Older mod builds are rejected.
- Each release publishes `update.json` with the build identity and APK hashes, covered by `SHA256SUMS`. Every APK carries the signed build identity.
- Launcher label **TOE3 Mod Menu** and a gold-truck **MOD** launcher icon.

### Known limitations

- An OTA-enabled build has to be installed manually once. Installing an update over the air from a published release has not yet been tested on a device.

## [toe3-v0.7.64-b193-mod-f4e2100] - 2026-10-15

Game 0.7.64 (build 193). Source [`f4e2100`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/f4e2100074157eb0aa0beb509d0b7e72004ee2f3). The mod code is unchanged from the previous release.

### Changed

- Release notes rewritten with hero artwork, a summary of the menu controls, checksum and install steps, save-data and Google Play Games caveats, and provenance.

## [toe3-v0.7.64-b193-mod-2141bcc] - 2026-10-15

First published release, for game 0.7.64 (build 193) on arm64-v8a. Source [`2141bcc`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/2141bccde4522587f70cb962c4ff6cb959311cea).

### Added

- Native mod library that hooks and patches `libil2cpp.so` in memory; the shipped `libil2cpp.so` is unmodified ([`3cc5504`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/3cc5504)).
- Draggable in-game menu with saved settings and a **Mods enabled** master switch. Modules cover economy, rain dirt, weather, time and traffic, engine power, damage, fuel, fatigue, jobs, shopping and travel ([`3cc5504`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/3cc5504), [`4893328`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/4893328)).
- Build scripts that inject the mod into the original split APK set and sign it with the mod key ([`4893328`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/4893328)).
- Release CI: downloads the latest upstream APK set, checks the original signer and the complete split set, refuses to build when `libil2cpp.so`, `libunity.so` or `global-metadata.dat` differ from the reviewed baseline, and publishes signed releases with `SHA256SUMS` ([`a92a869`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/a92a869), [`2141bcc`](https://github.com/ardzz/truckers-of-europe-3-mod/commit/2141bcc)).

[toe3-v0.7.64-b193-mod-a57fa36]: https://github.com/ardzz/truckers-of-europe-3-mod/releases/tag/toe3-v0.7.64-b193-mod-a57fa36
[toe3-v0.7.64-b193-mod-f4e2100]: https://github.com/ardzz/truckers-of-europe-3-mod/releases/tag/toe3-v0.7.64-b193-mod-f4e2100
[toe3-v0.7.64-b193-mod-2141bcc]: https://github.com/ardzz/truckers-of-europe-3-mod/releases/tag/toe3-v0.7.64-b193-mod-2141bcc
[toe3-v0.7.64-b193-mod-da547e1]: https://github.com/ardzz/truckers-of-europe-3-mod/releases/tag/toe3-v0.7.64-b193-mod-da547e1
[toe3-v0.7.64-b193-mod-7f0d995]: https://github.com/ardzz/truckers-of-europe-3-mod/releases/tag/toe3-v0.7.64-b193-mod-7f0d995
[v0.1.0]: https://github.com/ardzz/truckers-of-europe-3-mod/releases/tag/v0.1.0
