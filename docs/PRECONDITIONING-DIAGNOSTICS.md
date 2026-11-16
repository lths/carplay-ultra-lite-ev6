# Diagnose the CarPlay preconditioning switch

CarPlay Ultra-Lite delegates preconditioning to the separate **Launchpad / Precondition** app.
It does not install that app or send an independent conditioning command. The Vehicle row stays visible
to preserve CarPlay's stable schema, including when the dependency is missing.

## Finding on this EV6, 2026-11-16

A verified on-car snapshot found neither supported Android package installed. The mod logged `no Launchpad`
and its root helper returned `none`. This explains the switch's lack of action. The existing ev6.2 CarPlay build
does not need replacement to supply the missing dependency.

The original [Precondition v2.0.0 USB installers](https://github.com/paytondevs/gen5w-preconditioning/releases/tag/v2.0.0)
are staged under `out/preconditioning-install/`; the normal ZIP is extracted to `Launchpad-2.0.0-USB/` for copying.
`START-HERE-EV6.txt` includes the steps. The normal installer needs NEMESIS 1.16+; the upstream manual ZIP is
also staged for older NEMESIS. Each outer archive, the nested package and APK were checked against the release hashes.

Turn **ADB over USB OFF** in the car's HMG-API ADB mod and apply before inserting a USB stick. Then install
the official app through NEMESIS, restart infotainment, enable Battery conditioning mode in Kia's EV settings,
and check **Precondition's own screen** before retrying CarPlay. An app state of Request sent is pending;
Active confirms the car's indication. Warm/Unavailable explains a declined Start. No compatibility check is bypassed.

The release lists an EV6 USA 260507 profile, originally tested on a 2023 EV6. A matching 2024 unit must still pass
its exact device, software and firmware-file checks. See the [release's compatibility record](https://github.com/paytondevs/gen5w-preconditioning/blob/v2.0.0/COMPATIBILITY.md#ev6-us-260507-profile).

Supported package/component combinations are:

- `com.paytondev.precondition/com.paytondev.launchpad.PreconditioningService` (current package).
- `com.paytondev.launchpad/com.paytondev.launchpad.PreconditioningService` (older package).

Both use the `com.paytondev.launchpad.PRECONDITION_START` and `PRECONDITION_STOP` actions.
These names match [Launchpad's source](https://github.com/paytondevs/gen5w-preconditioning/blob/main/app/src/main/java/com/paytondev/launchpad/PreconditioningService.java).
Launchpad handles firmware checks, READY, the vehicle's battery-conditioning enable setting, temperature advice,
request ownership and pending-request cancellation. A sent request does not establish active conditioning.

## Collect locally when the car hotspot has no internet

1. On the car, use **HMG-API → Mods → ADB → ADB over Wi-Fi (hotspot, port 5555)**, save and apply.
   Applied should show `persist.adb.tcp.port = 5555`. An unset port is the Wi-Fi ADB OFF configuration.
   Use this mod's setting; its implementation differs from the old Engineering Mode ADB TCP switch.
2. While the Mac still has internet, start the collector locally:

   ```sh
   python3 tools/collect_preconditioning.py --connect 192.168.0.1:5555 --wait 600 --observe 30
   ```

3. Join the car's hotspot from the Mac. The script waits for a direct route and verifies **CVEV USA 260507**
   before inspecting apps or logs. It keeps running without chat/internet access.
4. When the terminal reports the first snapshot saved, tap preconditioning once in CarPlay.
   Leave the Mac on the hotspot until the second snapshot completes, normally under a minute.
5. Reconnect the Mac to internet and return to chat. Results are in `out/diagnostics/preconditioning-<UTC timestamp>/`:
   `status.json`, `initial.txt` and, if the connection lasts, `after-tap.txt`.

The collector reads installed package versions/components, the running service, the mod's helper state,
the app's request-ownership boolean, and narrowly filtered logs. It does not install, start/stop conditioning,
change settings, clear logs, restart the head unit or retrieve firmware. Reports remain excluded from Git.

If the app is missing, its separate installer is the next step. If it is present, the service/error/ownership
evidence distinguishes a missing component, a rejected request, and missing state feedback. Temperature,
firmware or operating-state checks are not bypassed.

HMG-API's documented hotspot endpoint is `192.168.0.1:5555`:
[ADB mod instructions](https://github.com/Open-HMG/hmgapi-adb/blob/main/README.md),
[property configuration](https://github.com/Open-HMG/hmgapi-adb/blob/main/adb/patches.json).
