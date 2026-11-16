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

## Next validation

Use the install bundle described in [INSTALL-EV6.md](INSTALL-EV6.md). It includes the private EV6 mod and the
original signed theme-enabled routing dependency. Keep the existing official wireless CarPlay installation.

Still to verify in a CarPlay session:

- Theme download, pairing, startup, display and reconnect behavior.
- Temperature/fan/AUTO/Driver Only, seat heat and defrost commands, plus matching feedback from the car.
- Displayed battery, range and odometer against the vehicle's own display.
- Volume and mute behavior, including navigation, Siri and phone audio.

This record does not claim a completed functional vehicle test or validated Apple Maps energy/charging calibration.
