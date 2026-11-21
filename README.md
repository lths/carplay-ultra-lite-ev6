# CarPlay Ultra-Lite for EV6

Private development copy for a **2024 Kia EV6, Gen5W USA firmware 260507**. Current test version:
**1.3.3-ev6.8**. Based on [SafariForest's CarPlay Ultra-Lite 1.3.3](https://github.com/SafariForest/carplay-ultra-lite-gen5w/releases/tag/v1.3.3-test).
Requires working wireless CarPlay, **HMG-API 1.1.0-rc.1 or later**, and the existing theme-enabled routing dependency.
The owner uses iOS 27.2 and confirms **ventilation, wheel heat, range, odometer and battery percentage work**
on ev6.2. The November 17 control trace also confirms passenger seat-heat OFF, wheel on/off, and low ventilation
on/off for both front seats through vehicle feedback. Battery preconditioning was traced to the separate Launchpad / Precondition app being absent;
see the [diagnostic finding and USB installation path](docs/PRECONDITIONING-DIAGNOSTICS.md).

GitHub's public-repository forks remain public, so this is an independent private repository retaining
upstream history and an `upstream` remote. [UPSTREAM.md](UPSTREAM.md) preserves the original documentation.

## Changes in ev6.8

The owner confirmed ev6.7 reads Kia's recirculation ON/OFF correctly but never shows a learned command.
ev6.8 corrects the capture path: stock apps send a fixed-size, credential-checked local handoff; DiPO reads
the live state and saves in its own private files. Framework proxy observers are installed before apps start,
with an app-loader fallback. Both DATC setter paths are observed even without a named front intake alias.
An unnamed front selector requires confirmed ON and OFF calls on the same selector with distinct values;
readings alone cannot create a replayable command. A newer candidate invalidates an older pending confirmation.

In Kia's touchscreen climate screen, **ensure recirculation is OFF, then turn it ON, then OFF**, waiting
**three seconds at each step**, with CarPlay connected. Vehicle → Comfort → Fresh-air control shows progress
or a specific capture/confirmation failure. **Kia command learned** still requires an actual saved profile.
Replay confirms fresh-air feedback for up to four seconds and deletes an unconfirmed profile without retries.
Host checks cover packet/peer boundaries, paired selectors, cross-client supersession, timeouts and persistence.
The new Android local-socket path and the resulting command still need an owner test; no ADB device is connected.

This is a private-mod update. The existing HMG rc.7/Wireless rc.8 versions need no reinstall if already present.
Charging, notifications, Park-only charge-port controls and the removed audio-resume behavior are retained.
The video player is unchanged: the Crunchyroll photo shows an item without an accepted HTTP(S) content location,
not proof of a decoder or DRM failure. This release does not claim Crunchyroll compatibility.

## Changes in ev6.7

- Add a learned stock-command path for recirculation OFF. From Kia's touchscreen, turn recirculation ON then OFF
  once while CarPlay remains connected. A command is saved only after ON→fresh feedback is confirmed twice,
  and is bound to the exact EV6 firmware. Unlearned or unknown-state requests are withheld. Native and Comfort
  fresh-air controls use the same path; the previous ineffective OFF value is no longer sent.
- Add **Vehicle → Charging**: SOC, range, decoded charging state/connection, power/time with explicit units,
  conditioning indication and data availability. Untyped status codes and raw numbers with unverified scales
  are not displayed as measurements. See the power/time unit settings and test steps.
- Replace the startup notification with real low-battery, charging-state and conditioning events. Two-sample
  debounce, SOC hysteresis and stale-session handling prevent repeated alerts and false completion messages.
- Add Park-gated charge-port open/close requests, with a five-second rate limit and explicit unconfirmed status.
- Inventory every visible control in the supplied [Live Car Controls screenshot](docs/EV6-CONTROLS-CHECKLIST.md).
  Existing climate/seat/audio functions are covered; unsupported body/roof/rear-HVAC actions remain documented work.

These functions need owner validation. The Charging page's unmapped state/units remain unavailable until its
OEM enums or measured scales are confirmed. Automatic audio resume remains removed; parked video remains optional.

Built/tested with the verified HMG-API **rc.7 SDK**, with hook compatibility retained at rc.1. A separate
[staged upgrade bundle](docs/HMG-RC7-UPGRADE.md) carries official HMG-API **1.1.0-rc.7**, Wireless **2.2.0-rc.8**
and optional ADB **1.0.1-rc.1**. **Shared CarPlay identity must be OFF** with the existing themed-routing build;
its native serializer overlaps Wireless's new optional serializer. Official packages are preserved unmodified.

## Changes in ev6.6

- Remove automatic last-audio resume, its setting and the **Resume CarPlay audio** shortcut after the owner
  reported that it kept opening Now Playing. Existing saved resume settings are ignored.
- Connection and reconnection send no UI or playback requests. Radio source/tuning commands no longer redirect
  to phone audio. Explicit play/pause/next/previous buttons send their media keys without a screen request.
- Reserve the removed shortcut's old IID, so later shortcuts and battery/range/odometer readings keep their IDs.
  Both connection and runtime descriptions omit the removed action; cached writes to its old IID are rejected.

Climate controls and optional parked video retain the ev6.5 behavior. Confirm the corrected connection behavior
on the car after installing and restarting.

## Changes in ev6.5

Selected improvements from [joshuaauger v1.1.15](https://github.com/joshuaauger/carplay-ultra-lite-gen5w/releases/tag/v1.1.15):

- Accept OPACK's compact `-1` value, used by native seat-cooling controls. Advertise front cooling and wheel heat
  in CarPlay's Climate app, using this fork's existing EV6-tested seat/wheel commands and feedback guards.
  **Seat ventilation in the Climate app** and **Steering wheel heat in the Climate app** default on; the existing Comfort controls remain available.
- Declare the missing HVAC power characteristic and send the same complete vehicle description at connection and
  over the live protocol. Schema versions change with control options; live battery/temperature values do not reset them.
- Retain the last valid temperature setpoints while HVAC is off, for the life of the receiver process.
- Clear the forced initial **DAB** media-source value. This does not implement CarPlay FM tuning or prove audio resume.
- Add **AirPlay video while parked (experimental)**, **off by default**. Includes the fork's ExoPlayer player, separate
  controls above the video surface, seek/fit/captions, network retries, VOD/live synchronization and screen handoff.
  Playback requires an active CarPlay session and fresh Park feedback; loss of either releases the player and hides
  its window. The phone supplies normal CarPlay audio. Protected FairPlay streams are unsupported.

The new Climate widgets and parked video still need on-car testing. The wheel widget may
overlap another Climate control in some iOS layouts; disable its native toggle and use **Vehicle → Comfort** if needed.
Unreleased home tiles and the physical instrument cluster are not implemented. The fork's rear-seat, ambient-light,
other-firmware and raw-video-recording experiments are omitted. See the [port audit](docs/JOSHUA-1.1.15-PORT.md).

## Changes in ev6.4

- Reuse decoded vehicle-state messages for bounded field/type/size summaries. Remove the second pretty-decode
  and nested route serialization from logging; route addresses, coordinates and app text are omitted.
- Report OPACK failures with lane, outer/pluginData layer, packet and data sizes, reason, cursor offset,
  tag and tag offset. Offsets refer to the named layer's buffer; repeated failures are capped per session.
- Add session state, foreground package and recent Vehicle-shortcut launch context to the existing bounded
  video-event log. A denied foreground query is unknown. Pending, opened and failed launches are distinguished.
- Skip empty climate callbacks after page-only actions and remove the misleading “nothing to send” message.
  Accepted control values, response messages, climate mappings and receiver recovery behavior are retained.

These are diagnostic changes. They do not identify the old 73,861-byte packet's failure or repair spontaneous
video freezes without a new on-car capture. Native changes only refresh the embedded version label.

## Changes in ev6.3 (resume behavior removed in ev6.6)

- Replace **Vehicle → Head unit → Radio** with **Resume CarPlay audio**, keeping its existing identifier and IID.
  The action requests the iPhone's **Now Playing** screen and sends an explicit **Play** press/release through
  DiPO's own CarPlay service. It no longer launches Kia's FM app or chooses a fixed iPhone app.
- Enable **Resume last CarPlay audio on connect** by default. It makes one attempt three seconds after connection;
  turn this setting off in HMG-API for manual resume only. The iPhone retains the audio app, queue and position.
  A disconnect or a manual media action cancels pending autoplay. Repeated session callbacks do not repeat it.
- Forward the existing media controls to CarPlay and include `MEDIA` diagnostics in the filtered USB export.
  No Play/Pause toggle fallback or retry loop is used: a resume request must not pause existing playback.

The owner later reported unwanted Now Playing navigation, so ev6.6 removes resume and the Radio redirection.
Integrated CarPlay Radio still has no station backend.

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
The ev6.5 native segment retains the original stock guards and 33 bindings, and adds the fork's guarded `/command`
hook and 14 bindings for the **same** `libCarPlayCore.so` MD5 `d3a47296437df56fb1ffc7ac3dfca4cb`.
No manufacturer binary is included; no other firmware recipe is imported.

## Install and test

For the existing installation, use **`cp-climate-1.3.3-ev6.8-usb.zip`** from the private release.
Keep the existing wireless and theme-enabled routing mods. See [installation and test steps](docs/INSTALL-EV6.md).
The complete `carplay-ultra-lite-ev6-1.3.3-ev6.8-install-usb.zip` also carries the unchanged signed routing dependency
and its source for an installation that needs it. The checker USB ZIP is optional for this car.

The owner's evidence and unresolved issues are recorded in [EV6-VALIDATION.md](docs/EV6-VALIDATION.md).
The original eight YES results established interface compatibility; they did not prove working controls.
The old checker also accepted invalid seat-state values, which is corrected here.

## Current limits

- The old recirculation OFF command `vr(11, 2, 0)` left the observed intake state on.
  ev6.7's learning prompt did not advance on the owner's unit. ev6.8 corrects the capture and persistence path;
  successful learning and subsequent fresh-air operation still need an on-car check.
- CarPlay's integrated Radio backend is unimplemented; the DAB message does not establish a tuner fault.
  Use Kia's own menu for its FM radio. The old Radio/resume shortcut is removed.
- Visual freezing needs on-car diagnosis. A running discarded cluster stream does not prove the center video is healthy.
- Themes do not replace the EV6 driver's instrument display; `ClusterSink` discards the separate Ultra cluster video.
- Charging-method names do not establish units or callbacks. HMG-API's EV6 map identifies `getBatSoc()` as **12 V SOC**;
  retain the working `remainBatteryPercentage()` traction-battery reading. Charging/energy calibration in Apple Maps
  remains the separate routing mod's responsibility.
  The new Charging page uses the existing traction SOC and verified typed states/units; unknown values stay unavailable.
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
python3 tools/test_video.py
```

After changing page rows or version, regenerate the description and native segment before testing/packaging:

```sh
python3 tools/build_description.py --android-jar /path/to/android.jar --sdk /path/to/hmgapi-sdk
python3 tools/rebuild_segment.py --zig /path/to/zig
python3 tools/test.py --android-jar /path/to/android.jar --sdk /path/to/hmgapi-sdk
python3 tools/package.py mod --d8-jar /path/to/r8.jar --sdk /path/to/hmgapi-sdk --android-jar /path/to/android.jar
python3 tools/package.py check --d8-jar /path/to/r8.jar --sdk /path/to/hmgapi-sdk --android-jar /path/to/android.jar
python3 tools/bundle.py --upstream-bundle /path/to/carplay-ultra-lite-bundle-1.0.0.zip --mod-package out/mod/cp-climate-1.3.3-ev6.8.zip --sdk /path/to/hmgapi-sdk
python3 tools/build_hmg_upgrade.py --assets /path/to/verified-rc7-assets --mod-package out/mod/cp-climate-1.3.3-ev6.8.zip --sdk /path/to/rc7-sdk
```

`test.py` also uses Python `cryptography` for the independent encrypted transport test.
`test_video.py` uses only local TLS/HTTP fixtures and verifies the pinned library downloads.
`rebuild_segment.py` needs clang, Zig's linker and pyelftools. It reuses pinned stock bindings and guards;
it cannot port another stock library. That still requires its stock file and the original `tools/build.py` workflow.
Tool versions and checksums are in [tools/toolchain.json](tools/toolchain.json).
The mod build merges the optional video engine into one DEX with pinned R8 8.5.35; checker builds use D8 8.3.37.
Application sources compile against API 19. R8 links against a verified API 34 jar to resolve ExoPlayer's guarded
newer-platform code, while retaining DEX 035 / minimum API 19. Dependencies and hashes are in `video/fetch-libs.sh`.

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
The ev6.5 port also credits joshuaauger v1.1.15; bundled video dependency licenses are in [video/licenses](video/licenses).
