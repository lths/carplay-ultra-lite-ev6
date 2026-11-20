# EV6 controls checklist from the Live Car Controls photo

The owner supplied this photo on 2026-11-20 as a feature checklist. The screen's source was not supplied.
The public joshuaauger v1.1.15 source and its main/climate development branches do not contain that console.
A visible button is not evidence of its vehicle command, enum, feedback or compatibility with this EV6.
This list separates implemented functions from remaining work; it does not advertise unsupported controls.

## Implemented in this EV6 build

| Photo controls | EV6 integration / validation |
| --- | --- |
| Power, AUTO, A/C, SYNC, Driver Only | Existing native Climate controls; preserve tested EV6 mappings and feedback. Passenger-zone power is Driver Only. |
| Driver/passenger temperatures, LO/HI and quick temperatures | Existing native target-temperature controls. The photo's preset buttons do not add a new vehicle function. |
| Fan OFF and levels 1–8 | Existing native fan slider/power with the checked raw-step conversion. |
| Face, bi-level, floor, windshield/defrost | Existing mapped vent and defrost controls. Any additional combined floor/defrost enum still needs stock-command evidence. |
| Front defrost / maximum defrost, rear defrost | Existing native controls; rear defrost requires READY and verified body-listener feedback. |
| Maximum A/C | Existing ON action combines A/C, recirculation and fan. A separate reversible OEM maximum-A/C mode has not been established. |
| Recirculation / fresh air | ev6.7 routes fresh-air requests through a command learned from a confirmed stock touchscreen ON→OFF transition. Learning and replay need an on-car check. |
| Front seat heat / ventilation, wheel heat | Existing native/Comfort controls, preserving the owner's confirmed commands and feedback guards. |
| Volume up/down/mute | Existing CarPlay volume integration and manual media buttons; automatic audio resume remains removed. |
| Battery preconditioning | Existing Launchpad integration. The last checked unit lacked the app; the new Charging page separately reports the real conditioning indicator. |
| Open / close charge port | ev6.7 adds buttons using `IBCMService.requestChargeLockDoor(boolean)`: false opens, true closes. Exact signature lookup, a fresh Park read and a 5-second rate limit apply. A submitted request is not shown as confirmed physical movement. Needs an owner test. |
| Live battery / energy information | ev6.7 adds a Charging section with battery %, range, decoded charge state/connection, unit-aware power/time, real conditioning state and data availability. Unmapped states and unverified raw units remain unavailable. |

## Missing functions requiring additional evidence or equipment checks

| Photo controls | Remaining prerequisite |
| --- | --- |
| Separate HEAT control | Identify the stock EV6 command and actual feedback; A/C OFF alone is not a verified HEAT command. |
| Smart Vent, Auto Defog, After-Blow, Air Clean | Establish supported options, settings getters, command enums and persistent-versus-temporary behavior. |
| Rear HVAC / rear temperature | Confirm actual rear-zone hardware and interface. The current EV6 integration is a two-zone front climate system; rear vents do not establish a controllable rear HVAC zone. |
| Rear-left/right seat heat | Check installed seat options, valid states and named setters before offering levels. Other cars' rear-seat assumptions are not applied. |
| Door/lock/trunk/hood status | Map and validate the live getters or the exact listener fields. Unknown enum values cannot be labelled open/closed. |
| Lock/unlock, Open/Close All Doors | Identify supported stock commands and guards. “Open doors” must not be assumed to mean unlocking or powered door movement. |
| Trunk open/close, height levels and speed | Check powered-tailgate equipment, stock request APIs, option limits and movement feedback. |
| All/per-window movement and 25% ventilation | Verify each window command, position encoding, stop behavior and state feedback. No generic movement enum is inferred from the photo. |
| Sunroof and sunshade | Check equipment and independent shade actuation, then verify stock commands and positions. |
| Speed, steering angle, brake and other top-bar values | Some read-only probes exist. Validate units and freshness before adding display rows; these are not required for the three requested features. |

Charge-port provenance: [HMG-API vehicle commands](https://github.com/Open-HMG/HMG-API/blob/main/docs/vehicle-commands.md)
and its [EV6 test record](https://github.com/Open-HMG/HMG-API/blob/main/docs/car-test.md).
The private source adds no custom CAN frames and does not use the HMG vehicle-listener service for these functions.

## Next owner test

1. Connect CarPlay, open Kia's **touchscreen** climate page, turn recirculation ON, wait two seconds, then OFF.
   Wait two seconds. **Vehicle → Comfort → Fresh-air control** should show **Kia command learned**.
2. Turn recirculation on, then request fresh air in CarPlay. Confirm Kia's state changes. Physical buttons alone
   may bypass the observed Java command and cannot be relied on for training.
3. While actually charging, compare the new Charging page with Kia's EV page. Capture the filtered USB log if
   state/connection is unavailable; the log inventories only allowlisted charging getter signatures and enum values.
   Choose power/time units only after comparing the recorded raw value with Kia's displayed measurement.
4. Check the charge-port buttons in Park, allowing five seconds between requests; confirm movement at the port.
5. Notifications appear in Vehicle notification history on real events while CarPlay is connected; no startup
   test entry is posted. This is not a remote phone-push service while away from the car.
