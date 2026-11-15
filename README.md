# CarPlay Ultra-Lite for Kia EV6

Private development copy for a **2024 Kia EV6 with Gen5W USA firmware 260507**, based on
[SafariForest/CarPlay Ultra-Lite 1.3.3](https://github.com/SafariForest/carplay-ultra-lite-gen5w/releases/tag/v1.3.3-test).
Version **1.3.3-ev6.1** is an experimental source port. **On-car compatibility and control behavior are pending.**
The owner's wireless CarPlay already works through HMG-API. Requires an iPhone on iOS 26 or later.

GitHub requires native forks of public repositories to remain public. This private repository retains the
upstream Git history and an `upstream` remote, with the released 1.3.3 sources imported in a separate commit.
[UPSTREAM.md](UPSTREAM.md) preserves the original project documentation.

## What changed for EV6

- Lists **CVEV / USA / 260507** explicitly and declares `wireless-carplay-aa` as a dependency.
- Changes the Vehicle app's embedded model label to **EV6**. The existing recipe's native instructions,
  offsets and plist length are preserved; `tools/set_vehicle_model.py` reproduces the metadata edit.
- Checks the climate flag definitions in the car's framework before reading or sending DATC selectors.
  Changed or missing flags disable those operations. A recognized temperature unit is required before commands.
- Keeps method lookup by name and exact signature, and retains the original listener fingerprint checks.
  No EV6 transaction numbers or listener fingerprints have been guessed.
- Extends the checker to inspect the HSCAN battery/range/odometer getters, distance unit, volume methods
  and live read access. It never invokes a setter or registers a vehicle listener.

The published EV6 USA 260507 stock list has the same `libCarPlayCore.so` MD5 targeted by the existing recipe:
`d3a47296437df56fb1ffc7ac3dfca4cb`. HMG-API's EV6 map contains all six requested Java hook points.
Evidence: [stock checksums](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/blob/fa672126f1fa2c37f457534fa6a476ad3b8a24b5/wireless-carplay-aa/patches/ev6/stock),
[EV6 map](https://github.com/Open-HMG/HMG-API/tree/e15dadcccd4dbebac2cc62557c0a8c0aacb184a4/maps/kia/ev6/usa/260507).
These facts do not establish climate encoding, audio stream assignments or battery-model calibration on this car.

## Run the check first

Use this repository's `ev6-carplay-check-1.1.0-ev6.1-usb.zip` from its private release.

1. Unzip it and copy `HMGAPI` to the root of a USB stick.
2. In HMG-API, open Updates, install **EV6 CarPlay Ultra-Lite compatibility check**, apply, and restart if requested.
3. About a minute after startup, the USB stick receives `CarPlayUltraLite-Check/cp-climate-check.md` and `.json`.
4. Return both reports and remove the checker in HMG-API.

The checker has its own ID, `ev6-carplay-check`, and this fork's signing key, so it can coexist with the
upstream checker. The public author key is in [signing/author.pub](signing/author.pub); the private key stays
outside this repository. The report writes files but sends no climate, seat, defrost, volume or preconditioning commands.
The expected author UUID is `51951394-274a-8d4b-8396-c831913a9a44`.
`YES` means the required signatures and read access were found; actual controls still require validation.

ADB is an alternative after building: `sh check/check.sh`, or `sh check/check.sh --root 'su -c'` if needed.

## Dependencies and limits

- Keep the official wireless CarPlay mod already working on this EV6. The Ioniq 6 wireless fix is unnecessary.
- Ultra themes require the **modified E-GMP CarPlay EV Routing 0.4.3-ioniq6.15** supplied in the original
  [1.0.0 bundle](https://github.com/SafariForest/carplay-ultra-lite-gen5w/releases/tag/v1.0.0), under
  `usb-2-install-ev6-ioniq5`. The unmodified routing release does not supply this theme handshake.
- The routing mod must match `libiap2session.so` MD5 `fc7cb675b46a44bd893a1edc054c5e71`; the checker verifies it.
  The theme and Ultra settings must agree. The iPhone must forget and re-pair the car for theme changes.
- The source retains the Ioniq 6's climate encodings, three seat-heating levels, audio streams 14/13/11/12,
  maximum volume 45, and preconditioning integrations. Validate them on the EV6 before treating them as supported.
- Battery/range/odometer readings are live getters. Apple Maps consumption and charging profiles belong to the
  separate routing dependency and have not been calibrated here for the owner's trim, battery or drivetrain.
- The instrument-cluster video stream is discarded by `ClusterSink`; this does not put Ultra gauges on the driver display.
- Volume-key interception, USB ADB keepalive and drive logging remain off by default.
- Fork packages use a different author key from upstream. HMG-API cannot update an upstream `cp-climate`
  install in place with this fork. Resolve author-key conflicts only after reviewing the compatibility report.

## Build and test

Fast host checks need Python 3.9+, JDK 11 or later, and clang:

```sh
python3 tools/test.py
```

Full compilation additionally takes an Android `android.jar` and the HMG-API mod SDK:

```sh
python3 tools/test.py --android-jar /path/to/android.jar --sdk /path/to/hmgapi-sdk
python3 tools/package.py check --d8-jar /path/to/r8.jar --sdk /path/to/hmgapi-sdk --android-jar /path/to/android.jar
python3 tools/package.py mod --d8-jar /path/to/r8.jar --sdk /path/to/hmgapi-sdk --android-jar /path/to/android.jar
```

The packager emits signed mod ZIPs and USB wrappers in `out/check` or `out/mod`. It creates the author key at
`~/.config/carplay-ultra-lite-ev6/author.key` on the first build; retain it for updates. Only the public key belongs in Git.
The checker is also copied to `check/cp-climate-check.jar` for the ADB runner. Builds target Android API 19 and DEX 035.
Tool versions and download checksums used for this port are recorded in [tools/toolchain.json](tools/toolchain.json).

No manufacturer binaries or private reports belong in Git. Rebuilding native instructions for a different library
still requires that unit's stock library and the original `tools/build.py` workflow.

## Attribution

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
CarPlay Ultra-Lite and this fork are GPL-3.0-or-later; retain [LICENSE](LICENSE), [NOTICE](NOTICE),
[mod/NOTICE.md](mod/NOTICE.md), and the source-data notices. Upstream source archive SHA-256:
`ea679ecf94764d71b550704d0d69fce8cf0a0f16dd94b554b9bd8d7c4f5e2f45`.
