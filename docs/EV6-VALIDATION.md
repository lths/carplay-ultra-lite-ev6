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
At this point the report did not establish seat-heater OFF behavior, recirculation OFF, freeze recovery or integrated radio tuning.
The later November 17 control trace below adds seat-heater OFF evidence.

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

## Normal-startup capture, 2026-11-17

The owner supplied `logs/report-20261117-1535.txt` and `logs/log-20261117-1535.txt` and confirms
this was **normal startup, with no image freeze**. The runtime file contains 248 lines spanning about
one minute; the report was saved at 15:36:55. Raw reports remain local and excluded from Git.

The report identifies the US CVEV head unit, Android 4.4.2/API 19, and a **1920×720 display at 240 dpi**.
Installed versions are HMG-API **1.1.0-rc.1**, Wireless **2.2.0-rc.1**, Ultra-Lite **1.3.3-ev6.2**,
and the existing modified EV Routing **0.4.3-ioniq6.15**.

| Observation | Evidence | Interpretation |
|---|---|---|
| Original target libraries match | Report lines 42 and 51 give the expected `libCarPlayCore.so` and `libiap2session.so` MD5 values. | On-disk stock bytes match. Stock markers do not mean the memory patches are absent. |
| Rebuilt description is active | Runtime line 19 advertises a 79979-byte description; lines 34–42 show both vehicle-state connections and the phone's configuration request. | The ev6.2 native description and runtime protocol are being used. |
| New page schema is present | Runtime lines 38 and 54–57 show 25 items and subscriptions to the fixed battery/range/odometer and Comfort IDs. | Consistent with the corrected page identities. Subscription alone does not test every action. |
| Climate reads are valid | Runtime line 78 reports seats `2/2`, intake `2`, synchronized 68°F/20°C setpoints and a recognized unit. | Both seat readings are valid OFF states; no seat command is recorded. |
| Live vehicle data continues | Runtime lines 98 and 241 report SOC 53.5%, range 163–164 km and odometer 92361.5 km. The range update is 101 mi at 163 km. | Getter values and distance conversion are operating during this baseline. |
| Routing produces EV payloads | Repeated `native_send=0`, `live=1` and updated range values through line 248. | Host-side sending continues. This does not prove the iPhone's displayed routing values or battery-model calibration. |
| Ultra auxiliary channels connect | Cluster port 7132, uiSync 7135 and vehicle-state ports 7130/7131 connect successfully. | A virtual-cluster stream exists; this remains separate from the physical driver's display. |

The copied stock receiver text includes `Unsupported stream type 111/130`, but the mod reports successful
setup and actual connections for those same auxiliary channels. Those strings alone do not establish a
stream failure. The USB `MediaPrescanner` error names the exported text log, not a decoder failure.

An actionable version-related observation appears at runtime lines 70–77: Bluetooth drops and the bridge
reattaches iAP2 from component 4 to component 5 while wireless CarPlay remains up. The
[Wireless rc.3 release](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases/tag/v2.2.0-rc.3)
changes this behavior to keep Bluetooth connected, addressing later session drops reported on some phones.
This baseline does **not** show such a session drop or establish the cause of the owner's earlier visual freezes.
Upstream also lists picture recovery after a freeze as unresolved.

Wireless rc.3 is the narrower reliability update to test: its manifest does not add the new
`libiap2session.so` identification patch, and its release supports HMG-API 1.1.0 release candidates.
Wireless rc.4 instead requires HMG-API 1.1.0-rc.3+ and introduces shared identification; the existing routing
identification patch must be addressed in that coordinated migration. No installed software was changed
during this log review.

This recording has no heater/ventilation/wheel/recirculation test writes, stock-touchscreen command trace,
preconditioning attempt, Launchpad package inventory, or decoder failure callback. It adds normal-startup
evidence; it does not resolve the still-unverified control and freeze cases or establish today's Launchpad installation state.

## Control capture after software updates, 2026-11-17 at 16:24

The next files, `logs/report-20261117-1624.txt` and `logs/log-20261117-1624.txt`, contain 304 runtime lines
from 16:24:09 through 16:26:45. The installed software changed to **HMG-API 1.1.0-rc.3** and
**Wireless 2.2.0-rc.4**. Ultra-Lite remains **1.3.3-ev6.2**, and EV Routing remains **0.4.3-ioniq6.15**.
The on-disk target library hashes are unchanged.

This trace provides actual phone-request → sent-command → vehicle-feedback evidence:

| Control | Requests and commands | Vehicle acknowledgement | Runtime lines |
|---|---|---|---|
| Passenger seat heat | Levels 3, 2, 1, 0 send USM raw 8, 7, 6, 2. | BCM reports high (8), low (6), then OFF (2); CarPlay heating-on becomes false. The intermediate medium command is sent between polling samples, without its own sampled acknowledgement. | 79–104 |
| Steering-wheel heat | ON/OFF requests send `wheel(1)` / `wheel(0)`. | The longer on/off cycle reports wheel 1 then 0, and the Vehicle status updates On then Off. | 117–136 |
| Driver ventilation | Low sends `seat(213, 3)`; Off sends `seat(213, 2)`. | BCM driver state changes 3 then 2. | 138–149 |
| Passenger ventilation | Low sends `seat(214, 3)`; Off sends `seat(214, 2)`. | BCM passenger state changes 3 then 2. | 151–162 |
| Front defrost OFF | Heating level 0 sends `vr(10, 0, 0)`. | Front defrost changes from 1 to 0. Its ON command is not captured. | 26–42 |
| Lower airflow on/off | Requests send blower modes 2 and 1. | Repeated reads return modes 2 and 1. | 45–66 |
| Fan | Level 2 sends `vr(5, 3, 0)`. | Raw fan 3 is read back, matching the translated step. | 68–70 |
| Driver temperature | 19.5°C sends the 67°F command. | Driver and synchronized passenger readings both become 67°F/19.5°C. | 72–75 |
| Passenger temperature | 19°C and 19.5°C send passenger raw values 10 and 11. | Passenger reads 66°F then 67°F, and the car disengages SYNC when that zone changes independently. | 106–113 |

BCM seat options are **6/6**, wheel feedback is recognized as **0/1**, and the original USM seat-state getters
still return **-1/-1**. This directly supports preferring BCM feedback. The first three high-heat taps happen
before the next read; after feedback arrives, the phone asks for lower levels and OFF. The original persistent
stuck-at-high symptom is not present in this recorded passenger-seat sequence.

One video callback occurs at **16:26:23.960**: `dipo.carplay.decode_failure [1]`. It follows the HMG-API shortcut
request at 16:26:23.670 and successful activity launch at 16:26:23.690. The owner confirms CarPlay was working
normally before opening HMG-API. The timing is consistent with an app/surface transition; the callback alone
does not establish a spontaneous image freeze or define the meaning of error argument 1. Routing sends continue
afterward, with no session-end event in this capture. No forced-key-frame or screen-timeout callback is recorded.

At **16:26:08.340**, one **73861-byte** message fails the local OPACK decoder during Apple Maps route sharing.
Subsequent route messages are decoded and the stream continues. The raw rejected payload and exception detail
were not captured, so the exact cause cannot be reproduced from this text log. Host probes of the current codec
successfully round-trip data at 65535, 65536, 73861 and 131072 bytes, plus a 224015-byte synthetic route; size alone
does not reproduce the failure. This remains a protocol-decoding investigation, separate from the video callback.
Route locations and addresses from the raw log are not included in this summary.

The `CAR nothing to send` lines after Comfort controls reflect the separate page-action path: those controls
already queued their commands. Similar messages for Apple Maps fields concern route metadata rather than climate
actuation. There is no rejected vehicle transaction or `may not write` ID error in this capture.

This trace does not exercise recirculation, ventilation medium/high, driver seat heat, rear defrost or preconditioning.
It contains no stock-touchscreen trace and does not establish whether the new shared identity serializer is active;
the unchanged routing/Ultra-Lite consumers have not been migrated to the API. No installed software or runtime code
was changed during this review.

## Charging API leads

The owner supplied names for EVSE/mains/OBC display power, battery energy sum, SOC, remaining charge time,
charging status/type, and change callbacks. This build inventories matching HSCAN/HEV signatures by reflection
and logs them once. It does not invoke new methods or register a listener.

Units, invalid/sentinel values, AC/DC differences, enum meanings and listener ABI still require verification.
In particular, [HMG-API's EV6 map](https://github.com/Open-HMG/HMG-API/blob/main/maps/kia/ev6/usa/260507/framework.json)
identifies `getBatSoc()` as **12 V battery SOC**. It is not substituted for the working traction-battery percent.

## CarPlay audio request, 2026-11-19 (ev6.3)

The owner reports that the Radio shortcut opens Kia's FM screen at its last station, and requests the last
CarPlay audio app instead, both from the shortcut and automatically when CarPlay connects.
The existing `CarVehicle` callback explicitly launched `com.hkmc.av.app.radiona`; this was the handoff's cause.

ev6.3 replaces that shortcut's label and action while retaining `open_radio` and action IID **20723**.
It calls the active DiPO `CarPlayService.requestUI("nowplaying:")`, followed by an explicit
`onConsumer(Play, true/false)` pair. The iPhone owns app selection, queue and position; no app-specific URL,
Kia FM launch, Bluetooth handoff, Play/Pause toggle fallback or repeated autoplay loop is added.
The default-on setting makes one attempt three seconds after the stock session-start callback.
A manual media action or session end cancels pending autoplay. The path is independent of the optional Vehicle page.

The readable receiver method signatures and Now Playing URL are also used by
[DeFlight's Gen5W Button Mapper](https://github.com/DeFlight/hmgapi-gen5w-button-mapper/blob/ea25ab3f86a8f44afa3561c7c9ca870a489de1d0/gen5w-button-mapper/src/hooks/com/deflight/buttonmapper/hooks/Entry.java)
and its [URL choices](https://github.com/DeFlight/hmgapi-gen5w-button-mapper/blob/ea25ab3f86a8f44afa3561c7c9ca870a489de1d0/gen5w-button-mapper/src/app/java/com/deflight/buttonmapper/Choices.java).
That project's notes report that some phones ignore separate Play/Pause consumer keys. We therefore do not
treat method resolution or a receiver return value as proof of audible playback on this EV6. An unattended
toggle would risk pausing audio already playing and is deliberately withheld. Actual phone/app acceptance,
FM-to-CarPlay audio focus and repeat reconnect behavior need the owner's on-car test.

Host regressions cover one attempt per session, setting-off/manual behavior, duplicate callbacks, manual
actions overriding autoplay, disconnect/reconnect cancellation, unsupported receiver signatures, explicit
Play without a toggle, and a paired release after invocation errors. The native Vehicle description must
match the new runtime label without renumbering any controls. Filtered USB diagnostics include `MEDIA` lines;
no phone app history or audio metadata is collected.

The full host suite passes, including 18 audio-resume checks, 39 Vehicle-page checks, all-source Android API 19
compilation, native/runtime schema equality and firmware mismatch guards. HMG SDK hook-floor and shell-command
checks pass. The rebuilt segment is 95,639 bytes with an 80,002-byte Vehicle description; the original stock
guards, patch sites and all 33 bindings are preserved. These checks do not establish on-car playback.

## Diagnostic improvements, 2026-11-19 (ev6.4)

The owner requested four improvements after the normal-startup and HMG-API-transition captures:

- Vehicle-state packet logging now reuses the outer and pluginData objects decoded by the handler. It lists
  at most eight schema fields per map, with types/counts/sizes. It never traverses nested route values.
  Per-field log formatting also avoids re-encoding and pretty-decoding maps/lists and omits string contents.
  Unknown command and field-name contents are withheld. Numeric/boolean climate diagnostics remain readable.
- OPACK failures include lane, outer/pluginData layer, packet/data sizes, reason, cursor offset, tag byte and
  tag offset. Offsets are relative to that layer's buffer. At most 32 parse failures plus one suppression notice
  are logged per VehicleState session. These structured failures are included in the filtered USB export.
- Existing video observers add session state and an Android foreground-task snapshot, plus the last Vehicle
  shortcut's target, requested/opened/failed status and elapsed age. “Recent” means within ten seconds.
  The app-switch marker is recorded before startActivity, so a decoder callback during the transition is visible.
  A denied task query produces unknown visibility. The snapshot cannot establish actual frame progress, and
  launches outside this mod's shortcuts have no shortcut timestamp. No polling or recovery loop is added.
- Page-only writes no longer invoke the climate callback with an empty map. The generic “CAR nothing to send”
  message is removed for metadata/non-climate writes; successful actions still receive the same protocol reply.

All host checks pass. New checks cover a synthetic 4,000-point route with private-value sentinels, constant
work on nested-list summaries, bounded field listing, parser failure offsets/tags, the 65,535/65,536/73,861-byte
boundaries, nested decode failures and error-log limits. Video-context cases and the page-only callback
regression pass. An independent encrypted phone simulator verifies sync replies, climate write responses and
notifications after a malformed 20 KB nested payload spans multiple transport frames.

The exact rejected 73,861-byte payload from the owner's earlier capture is unavailable. This change supplies
future diagnostics; it does not establish that packet's cause or claim a spontaneous-freeze repair. New logs
need an on-car check. ev6.3 audio resume, vehicle command mappings, native guards and listener checks are retained.

## joshuaauger port, 2026-11-19 (ev6.5)

Selected changes from joshuaauger v1.1.15, commit `4f739c79c3d884f47f914468148213994d46ca46`, are integrated:
compact OPACK -1, native front cooling and wheel widgets, declared HVAC power, neutral initial media source,
remembered off-state temperatures and complete matching/versioned descriptions. Existing EV6 control paths,
BCM feedback guards, stable Vehicle IDs and explicit last-audio resume are retained.

Optional AirPlay video is included, off by default. It requires fresh Park feedback and an active CarPlay session;
unknown/stale/non-Park feedback or session loss stops the player. The source import adds retries, overlay controls,
live/VOD sync and phone-proxy TLS. Raw video-side recording is replaced by an acknowledging sink, and diagnostic
URLs/auth headers are redacted. See [the source audit](JOSHUA-1.1.15-PORT.md) and [parked test steps](INSTALL-EV6.md).

Host validation passes for all prior suites and the new comfort/schema, OPACK-reference, Park-freshness,
encrypted-side-stream and video-command cases. Local video tests pass synchronization, TLS 1.3 PSK authentication,
certificate parsing, HTTP ranges/redirects/chunking, parallel retry and diagnostic redaction. Full mod sources
compile against API 19. SDK hook-floor and script checks pass.

The native segment retains original guards and 33 bindings and adds the pinned fork's same-library command hook
with one additional verify site and 14 bindings (47 total). It does not import the fork's other-firmware recipe.
The new Climate UI, actual Park feedback, hardware decoding and phone playback/handoff remain **unverified on car**.
The old rejected packet's exact payload remains unavailable, and spontaneous freezing has not been reproduced.

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
