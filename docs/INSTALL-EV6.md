# EV6 ev6.2 update and test

For the owner's existing installation, use **cp-climate-1.3.3-ev6.2-usb.zip**.
This updates CarPlay Ultra-Lite only. The new build has passed host checks; its behavior on the car is still unverified.

## Update

1. Confirm HMG-API is **1.1.0-rc.1 or later**. The new video observer hooks require that version.
2. Unzip the update USB ZIP. Copy **HMGAPI** and **CarPlayUltraLite-Logs** to the USB stick root.
3. In HMG-API, select **Check USB updates**, install **CarPlay Ultra-Lite 1.3.3-ev6.2**, apply and restart the unit.
   Keep the working official wireless mod and the existing theme-enabled routing mod enabled.
4. Keep **CarPlay Ultra** on in this mod and the matching **CarPlay theme** on in the routing mod.
5. Delete the car pairing on the head unit. On the iPhone, forget the car in **Settings → General → CarPlay**
   and Bluetooth, then pair again. Re-pairing refreshes the changed Vehicle-page description.
   Remove the installation USB stick while establishing wireless CarPlay. Keep the phone online for any theme download.
6. Check the version under CarPlay's **Vehicle → Head unit** section: **1.3.3-ev6.2**.

The update uses the same private author key as ev6.1, UUID `51951394-274a-8d4b-8396-c831913a9a44`.
Do not uninstall that existing private mod to update it. An upstream copy signed by another author cannot
be updated with this key; it must be removed before installing this fork.

## Test while parked

1. Compare **battery, range and odometer** against Kia's own displays. They should occupy their correct rows.
2. Use the existing Climate page to try seat heat at **3 → 2 → 1 → Off**. Confirm physical feedback,
   and confirm Kia's buttons update CarPlay. If it remains stuck, turn it off with Kia's controls.
3. Open **Vehicle → Comfort**. Check the seat-status lines, then ventilation low/medium/high/off and wheel heat on/off.
   “State unavailable” means the required feedback is missing and the command is withheld. Ventilation requires option 6.
   These controls are on the Vehicle page; native Climate-page ventilation/wheel widgets have not been added.
4. Recheck temperature, fan and SYNC, which worked in ev6.1. Check defrost separately; rear defrost requires READY.
5. Open **Vehicle → Head unit → Radio** to use Kia's radio and select FM there.
   Integrated CarPlay radio tuning remains unimplemented, so the DAB message may remain in CarPlay's Radio app.
6. Observe whether the center image still freezes. The lower virtual-cluster frame rate is a test mitigation,
   not a confirmed repair. Themes still do not replace the driver's instrument display.

## Capture the remaining faults

**Log Kia touchscreen climate commands** is on by default. For recirculation diagnosis:

1. From Kia's own **touchscreen** climate page, switch recirculation on, wait a few seconds, then off.
   Physical buttons may bypass the Java methods being observed.
2. Repeat on/off in CarPlay, waiting for the state after each tap. The current OFF mapping is unchanged.
3. If the image freezes, note the time, then use the Kia menu round trip that restores the display.
4. Insert the USB stick containing **CarPlayUltraLite-Logs**. Wait at least 15 seconds after reproducing
   the problem. The root helper copies a recent filtered log to **CarPlayUltraLite-Logs/ev6-carplay.log**.
   Remove the stick and share that file. It excludes receiver handshakes and unrelated mods.

Useful lines start with **CAR seat feedback**, **VS phone set**, **CAR sent**, **STOCK**, **VIDEO**, or **VEH**.
The export is a recent log snapshot, not a complete session recording; collect it soon after the test.
If no file appears, use HMG-API → Mods → CarPlay Ultra-Lite → Log, or Logs → Mods → Refresh; photos remain useful.
The old compatibility Markdown report is not required to proceed.

The charging API names are inventoried once by signature under **VEH charging candidate**. New power/time displays
and charging callbacks are not enabled until units and interfaces are verified. `getBatSoc()` is mapped by HMG-API
to the 12 V battery and is not used for traction-battery percentage.

## Complete install bundle

For an installation missing the theme dependency, the alternative
**carplay-ultra-lite-ev6-1.3.3-ev6.2-install-usb.zip** contains the private mod and the original signed
**E-GMP CarPlay EV Routing 0.4.3-ioniq6.15**, plus that routing build's source and notices.
Install routing first, then CarPlay Ultra-Lite. Its Ioniq 6 suffix also lists EV6 USA 260507 in its signed profiles.
Routing author UUID: `4bec7579-9d68-8e3c-b667-2804d05a20b9`.
If a routing mod with a different author is already installed, remove it before installing this one;
its prior settings are removed with it. The existing working wireless mod is retained.

The optional **ev6-carplay-check-1.1.0-ev6.2-usb.zip** corrects the old invalid-seat-state verdict.
Copy both its HMGAPI and CarPlayUltraLite-Check folders to the USB root to export reports.
A rerun is optional for this car; the functional logs are the next useful evidence.

## Return to the previous wireless setup

Disable CarPlay Ultra-Lite and the theme-enabled routing mod, apply and restart; keep official wireless enabled.
Forget/re-pair if the iPhone retains the theme identity. Alternatively, when testing classic CarPlay climate,
turn **both** CarPlay Ultra and the routing mod's CarPlay theme off together, then restart and re-pair.

## Attribution

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).
CarPlay Ultra-Lite and E-GMP CarPlay EV Routing are GPL-3.0-or-later. See the included licenses and notices.
