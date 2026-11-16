# EV6 validation record

## Owner-provided result, 2026-11-15

Vehicle: 2024 Kia EV6, Gen5W USA software ending in `V015.012.260507`.
Wireless CarPlay already works through HMG-API.

The owner supplied a photo of the HMG-API service log for `ev6-carplay-check`.
The detailed Markdown and JSON files were not retrieved. The visible summary is:

```text
YES CarPlay climate (dock temperatures, Climate app)
YES Driver Only (passenger power button) and passenger temperature
YES Seat heat
YES Front defrost
YES Rear defrost (READY only)
YES CarPlay theme, wallpapers and the per-seat climate panels
YES Vehicle page battery, range and odometer
YES Volume link interfaces and read access
```

The log also shows successful report generation at:

```text
/data/hmgapi/run/ev6-carplay-check/check/report/cp-climate-check.md
```

The owner explicitly asked to continue from this log. Retrieving the files is not a prerequisite for the first CarPlay test.

## What this establishes

Under the checker's verdict logic, these YES results require matching CarPlay and theme native-library hashes,
the climate method signatures and flag values, recognized temperature units, and successful climate reads.
The rear-defrost YES also requires the checked BCM listener fingerprint and registration method signature.
Vehicle-page YES requires the HSCAN getters, distance-unit getter and successful battery/range/odometer reads.
The volume YES requires the checked audio method signatures and readable levels within the assumed 0..45 range.

This is evidence of interface compatibility on the owner's unit. No new transaction mapping or relaxed guard
is indicated by the visible results. The original checks and runtime guards stay enabled.

The summary does not expose every WARN/INFO row. In particular, it does not establish the DATC temperature
listener fingerprint, existing-mod author identities, actual temperature values, unit calibration or audio stream routing.

## Functional testing of ev6.1, reported through 2026-11-16

The owner reports iOS 27.2, visible Themes, and working driver/passenger temperature, fan and SYNC commands.
Kia's own controls can turn the seat heaters off. The table below records ev6.1 failures and the changes made for ev6.2.

| Observation | Evidence and interpretation | ev6.2 action |
|---|---|---|
| Seat heat sticks at maximum | Repeated `passengerSeatHeatingLevel to 3` / `seat(214, 8)`; live seats `-1/-1`. The USM getter supplies no valid feedback. | Prefer BCM getters; preserve USM fallback only for valid states. Coalesce OFF/level conflicts and withhold commands without fresh valid feedback. |
| Range/odometer labels wrong or blank | Raw log: SOC 60.5–61%, range 189–190 km, odometer 92222.7 km, unit mi. Runtime rows were conditional, while the native description contained every row. | Keep a complete schema and pin legacy IDs; rebuild native description from the runtime schema. |
| Car-home shortcut refused | `VS phone may not write value 20579`; other shortcuts map to `open_hmgapi`. | Same stable-ID correction. |
| Recirculation turns on but not off | True → `vr(11, 1, 0)`; false → `vr(11, 2, 0)`; observed intake later stays 1. False is decoded correctly. | Mapping unchanged pending Kia touchscreen command trace. Do not invent another OFF value. |
| Steering-wheel heat / ventilation absent | Original source had no controls for these features. | Add guarded Vehicle-page Comfort controls using documented methods and seat enums. Hardware behavior still unverified. |
| DAB unavailable, no FM choice | Upstream Radio backend has no station-list/tuning implementation. | Correct IDs for the Kia Radio shortcut. Integrated FM remains unimplemented. |
| Center image freezes, returns after Kia menu round trip | The discarded cluster stream continues; this alone does not identify the fault. | Lower discarded cluster's advertised fps from 30 to 5 and observe decoder/frame/timeout events. Mitigation unproven. |
| Themes do not change the driver's gauges | Separate cluster video is consumed by `ClusterSink`. | No claim of physical cluster integration. |

The previous checker accepted `-1` seat values and did not require a valid live seat state in its seat verdict.
Its initial YES for seat heat was therefore too broad. Version `1.1.0-ev6.2` corrects that logic.

## Functional testing of ev6.2, 2026-11-16

The owner confirms **seat ventilation, steering-wheel heating, range, odometer and battery percentage work**.
This validates the new Comfort commands and corrected vehicle-row identities on the owner's EV6.
The report does not yet establish seat-heater OFF behavior, recirculation OFF, freeze recovery or integrated radio tuning.

Battery preconditioning did not work in the owner's test. A local collector then captured a verified
`CVEV.USA.0000.V015.012.260507` snapshot over network ADB with HMG-API `1.1.0-rc.1`:

- `pm list packages com.paytondev` succeeded with no package listings.
- The mod's page initialization logged `no Launchpad`.
- Its root helper logged `launchpad request none`; `cpc-precond-state` contained `none`.

This establishes the immediate cause: neither supported Launchpad/Precondition app package is installed.
The current CarPlay switch delegates to that service and silently does nothing if it cannot resolve it.
The next step is the separate official app installation, followed by the app's normal eligibility checks;
no new CarPlay vehicle-command mapping is indicated by this evidence.

Direct USB diagnosis found no enumerated head-unit device. Network ADB later connected long enough to collect
the first report; a second snapshot was interrupted when the connection ended. The earlier Applied screenshot
showed `persist.adb.tcp.port` unset; the later captured ADB settings were `tcp:true, usb:true`.
The skipped routing profiles for NEEV23/NEEV/CEEV are model-specific and expected on a CVEV unit.

The car hotspot interrupts the laptop's internet connection. The owner requested a different approach, so the
local collector is stopped and an unchanged official v2.0.0 USB installer is staged instead. Both installer
ZIPs, the nested NEMESIS package and its APK match the official release SHA256 values. No app was installed remotely.
See [PRECONDITIONING-DIAGNOSTICS.md](PRECONDITIONING-DIAGNOSTICS.md).

## Charging API leads

The owner supplied names for EVSE/mains/OBC display power, battery energy sum, SOC, remaining charge time,
charging status/type, and change callbacks. This build inventories matching HSCAN/HEV signatures by reflection
and logs them once. It does not invoke new methods or register a listener.

Units, invalid/sentinel values, AC/DC differences, enum meanings and listener ABI still require verification.
In particular, [HMG-API's EV6 map](https://github.com/Open-HMG/HMG-API/blob/main/maps/kia/ev6/usa/260507/framework.json)
identifies `getBatSoc()` as **12 V battery SOC**. It is not substituted for the working traction-battery percent.

## Host validation of ev6.2

- Native hook behavior, climate mapping, session pairing, firmware mismatch guards, Vehicle-page updates/actions,
  and audio/notice host tests pass. Full mod and checker sources compile for Java 8 / Android API 19.
- Regression checks cover BCM preference, invalid feedback, ventilation clearing heat, OFF versus stale levels,
  fixed legacy IDs, and an exact match between the generated native description and runtime page.
- ARM32 segment links without undefined symbols or relocations, contains the current binary plist, and preserves
  every original stock verify/patch directive, branch site and all 33 library bindings.
- HMG SDK accepts all nine declared hooks at the new minimum version, 1.1.0-rc.1. The three video hooks also
  appear in the published EV6 USA 260507 map. Head-unit script command checks pass.
- USB export test verifies paths containing spaces, filtering of unrelated/handshake logs and retention of useful logs.

These are host results. See the later owner test above for the controls/readings now confirmed on the car.
Native placement against a freshly retrieved OEM library, freeze recovery, audio routing, preconditioning,
and Apple Maps energy/charging calibration have not been validated for ev6.2.
Follow [INSTALL-EV6.md](INSTALL-EV6.md) for the next test and diagnostic capture.
