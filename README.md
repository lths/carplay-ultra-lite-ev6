# CarPlay Ultra-Lite for EV6

Private development copy for a **2024 Kia EV6, Gen5W USA firmware 260507**. Current test version:
**1.3.3-ev6.2**. Based on [SafariForest's CarPlay Ultra-Lite 1.3.3](https://github.com/SafariForest/carplay-ultra-lite-gen5w/releases/tag/v1.3.3-test).
Requires working wireless CarPlay, **HMG-API 1.1.0-rc.1 or later**, and the existing theme-enabled routing dependency.
The owner uses iOS 27.2 and confirms **ventilation, wheel heat, range, odometer and battery percentage work**
on ev6.2. Battery preconditioning was traced to the separate Launchpad / Precondition app being absent;
see the [diagnostic finding and USB installation path](docs/PRECONDITIONING-DIAGNOSTICS.md).

GitHub's public-repository forks remain public, so this is an independent private repository retaining
upstream history and an `upstream` remote. [UPSTREAM.md](UPSTREAM.md) preserves the original documentation.

## Changes in ev6.2

- Fix the Vehicle page's identities: optional apps no longer shift battery/range/odometer or shortcut IDs.
  The connect-time native description and runtime page are generated from the same Java schema and checked together.
- Prefer BCM seat feedback. The owner's previous USM reads returned `-1/-1`, leaving CarPlay without seat-state updates.
  Conflicting heat-level and OFF writes now resolve to one OFF command. Unknown or stale seat feedback withholds commands.
- Add **Vehicle → Comfort**: wheel heat, seat status, ventilation low/medium/high and seat-off buttons.
  Ventilation requires reported option 6 and valid feedback; wheel commands require a checked method and recognized
  state from the existing, fingerprint-verified BCM listener. The owner confirms ventilation and wheel heat work.
- Add bounded, passive stock-touchscreen climate tracing and decoder-event logging. Filtered diagnostics can export to USB.
- Request 5 fps for the discarded virtual cluster, reducing its advertised workload. This is an unverified freeze mitigation;
  the center display's frame rate is unchanged. No automatic decoder-recovery loop has been added.
- Correct the checker: `-1` seat readings no longer pass. Inventory the owner's charging-method leads by signature only.
  No new charging listener is registered and no unverified power/time reading is displayed.

The target remains **CVEV / USA / 260507**. Climate flag, method-signature, stock-library and listener checks remain enabled.
The rebuilt native segment retains every original stock verify/patch directive and all 33 library bindings for
`libCarPlayCore.so` MD5 `d3a47296437df56fb1ffc7ac3dfca4cb`; no manufacturer binary is included.

## Install and test

For the existing installation, use **`cp-climate-1.3.3-ev6.2-usb.zip`** from the private release.
Keep the existing wireless and theme-enabled routing mods. See [installation and test steps](docs/INSTALL-EV6.md).
The complete `carplay-ultra-lite-ev6-1.3.3-ev6.2-install-usb.zip` also carries the unchanged signed routing dependency
and its source for an installation that needs it. The checker USB ZIP is optional for this car.

The owner's evidence and unresolved issues are recorded in [EV6-VALIDATION.md](docs/EV6-VALIDATION.md).
The original eight YES results established interface compatibility; they did not prove working controls.
The old checker also accepted invalid seat-state values, which is corrected here.

## Current limits

- Recirculation OFF is still unresolved: the phone correctly sends false, and the mod sends `vr(11, 2, 0)`,
  but the observed intake state stays on. Capture Kia touchscreen ON/OFF commands before changing that mapping.
- CarPlay's integrated Radio backend is unimplemented; the DAB message does not establish a tuner fault.
  **Vehicle → Head unit → Radio** opens Kia's own radio, where FM can be selected. Shortcut IDs are fixed in this build;
  integrated FM tuning has not been added.
- Visual freezing needs on-car diagnosis. A running discarded cluster stream does not prove the center video is healthy.
- Themes do not replace the EV6 driver's instrument display; `ClusterSink` discards the separate Ultra cluster video.
- Charging-method names do not establish units or callbacks. HMG-API's EV6 map identifies `getBatSoc()` as **12 V SOC**;
  retain the working `remainBatteryPercentage()` traction-battery reading. Charging/energy calibration in Apple Maps
  remains the separate routing mod's responsibility.
- Audio streams 14/13/11/12, maximum volume 45, and Launchpad preconditioning retain upstream assumptions.
  Volume-key interception, ADB keepalive and drive logging remain off by default.
- Missing optional apps keep their rows to preserve the schema; their shortcuts have no action.
- The battery-preconditioning switch requires the separate Launchpad / Precondition app. Diagnose a missing app,
  rejected Start, or missing feedback using the [local offline collector](docs/PRECONDITIONING-DIAGNOSTICS.md).

Primary interface references: [HMG vehicle commands](https://github.com/Open-HMG/HMG-API/blob/main/docs/vehicle-commands.md),
[EV6 framework map](https://github.com/Open-HMG/HMG-API/blob/main/maps/kia/ev6/usa/260507/framework.json),
[EV6 DiPO map](https://github.com/Open-HMG/HMG-API/blob/main/maps/kia/ev6/usa/260507/dipo.json).

## Build

Fast host checks need Python 3.9+, JDK 11+ and clang. Full checks compile against Android API 19 and the HMG SDK:

```sh
python3 tools/test.py --android-jar /path/to/android.jar --sdk /path/to/hmgapi-sdk
```

After changing page rows or version, regenerate the description and native segment before testing/packaging:

```sh
python3 tools/build_description.py --android-jar /path/to/android.jar --sdk /path/to/hmgapi-sdk
python3 tools/rebuild_segment.py --zig /path/to/zig
python3 tools/test.py --android-jar /path/to/android.jar --sdk /path/to/hmgapi-sdk
python3 tools/package.py mod --d8-jar /path/to/r8.jar --sdk /path/to/hmgapi-sdk --android-jar /path/to/android.jar
python3 tools/package.py check --d8-jar /path/to/r8.jar --sdk /path/to/hmgapi-sdk --android-jar /path/to/android.jar
python3 tools/bundle.py --upstream-bundle /path/to/carplay-ultra-lite-bundle-1.0.0.zip --mod-package out/mod/cp-climate-1.3.3-ev6.2.zip --sdk /path/to/hmgapi-sdk
```

`rebuild_segment.py` needs clang, Zig's linker and pyelftools. It reuses pinned stock bindings and guards;
it cannot port another stock library. That still requires its stock file and the original `tools/build.py` workflow.
Tool versions and checksums are in [tools/toolchain.json](tools/toolchain.json).

Packages target DEX 035 / API 19 and use the persistent author key at
`~/.config/carplay-ultra-lite-ev6/author.key`. Only [the public key](signing/author.pub) belongs in Git.
Expected author UUID: `51951394-274a-8d4b-8396-c831913a9a44`. Updates must retain that key.
The separate checker, `ev6-carplay-check` version `1.1.0-ev6.2`, sends no commands or listener registrations.
It exports to the included `CarPlayUltraLite-Check` folder; ADB alternative: `sh check/check.sh`.

No manufacturer binaries or private diagnostic files belong in Git.

## Attribution

Based on [LIVI by Lasse Heitgres](https://github.com/f-io/LIVI).
CarPlay Ultra-Lite and this fork are GPL-3.0-or-later; retain [LICENSE](LICENSE), [NOTICE](NOTICE),
[mod/NOTICE.md](mod/NOTICE.md) and the source-data notices. Upstream source archive SHA-256:
`ea679ecf94764d71b550704d0d69fce8cf0a0f16dd94b554b9bd8d7c4f5e2f45`.
