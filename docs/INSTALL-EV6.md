# EV6 first CarPlay test

The compatibility checker returned YES for all eight feature groups on the owner's EV6 USA 260507.
This bundle is ready for the first CarPlay session. Actual control behavior has not yet been verified.

## Install

1. Unzip `carplay-ultra-lite-ev6-1.3.3-ev6.1-install-usb.zip` and copy its contents to the root of a USB stick.
2. Keep the official wireless CarPlay/Android Auto mod already working on the car.
3. In HMG-API Updates, install **E-GMP CarPlay EV Routing 0.4.3-ioniq6.15** first, then **CarPlay Ultra-Lite 1.3.3-ev6.1**.
   Apply the changes and restart the head unit.
4. Confirm **CarPlay theme** is on in the routing mod, and **CarPlay Ultra** is on in CarPlay Ultra-Lite.
   The bundled defaults already select the matching theme.
5. Remove the old phone pairing from the head unit. On the iPhone, forget the car in both Bluetooth and
   Settings > General > CarPlay. Pair again and allow CarPlay. Keep the iPhone online for the first theme download.
6. Open CarPlay Settings > Themes and choose a style.

The two packages retain their own signing identities:

| Package | Expected author UUID |
|---|---|
| Private EV6 CarPlay Ultra-Lite | `51951394-274a-8d4b-8396-c831913a9a44` |
| Theme-enabled routing dependency | `4bec7579-9d68-8e3c-b667-2804d05a20b9` |

If an existing E-GMP EV Routing mod has a different author key, HMG-API cannot update it with this package.
Remove that routing mod first, then install the bundled one; its previous settings are removed with it.
The same rule applies to an upstream CarPlay Ultra-Lite install signed by another author.
Keep the working wireless mod. No wireless replacement is included in this bundle.

The routing package is the theme-enabled build from SafariForest's original 1.0.0 bundle. Its Ioniq 6 suffix
does not mean it is limited to that car: its signed descriptor and profiles include CVEV USA 260507.
Its source and notices are included. It is unmodified and retains its original signature.

## First session

With the car parked, compare the CarPlay temperatures and Vehicle page readings with the car's displays.
Then try temperature, fan, AUTO, Driver Only and seat heat, one change at a time. Check front defrost;
the rear defrost command requires the car to be READY. Confirm that changes made with the physical controls
also appear in CarPlay.

Start audio at a low level, then check the knob, steering-wheel volume, mute, navigation and Siri.
Leave the volume-slider interception experiment, ADB keepalive and drive logging off for this first session.

The expected result is the Ultra theme and vehicle controls on the center screen. The EV6 instrument cluster
continues to use its existing display; this project discards the separate Ultra cluster video stream.

The included `CarPlayUltraLite-Check` folder also gives the installed checker an export destination if it runs
again. A new checker run is not needed before this first session.

## Returning to the previous wireless CarPlay setup

If the themed session stays black or will not connect, disable CarPlay Ultra-Lite and the theme-enabled routing
mod in HMG-API, apply and restart. Keep the official wireless mod enabled. Forget and re-pair the car if the
iPhone retains the theme identity. This returns to the previous wireless receiver setup.

## Build and attribution

The private mod's code is unchanged from commit `5b2ec88414c636b4832516443a09e72feab06369`.
This bundle adds the required theme dependency, installation instructions and the validation record.
It makes no changes to the runtime compatibility guards or the EV routing calibration.

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
CarPlay Ultra-Lite and E-GMP CarPlay EV Routing are GPL-3.0-or-later. See the included licenses and notices.
