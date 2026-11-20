# EV6 ev6.5 update and test

For the owner's existing installation, use **cp-climate-1.3.3-ev6.5-usb.zip**.
This updates CarPlay Ultra-Lite only. It adds native Climate cooling/wheel controls, matching complete vehicle
descriptions, an OPACK fix and optional parked video from joshuaauger v1.1.15. It retains EV6 command safeguards,
last-audio resume and filtered diagnostics. The new UI/video and audio acceptance still need on-car testing.

## Update

1. Confirm HMG-API is **1.1.0-rc.1 or later**. The new video observer hooks require that version.
2. Unzip the update USB ZIP. Copy **HMGAPI** and **CarPlayUltraLite-Logs** to the USB stick root.
3. In HMG-API, select **Check USB updates**, install **CarPlay Ultra-Lite 1.3.3-ev6.5**, apply and restart the unit.
   Keep the working official wireless mod and the existing theme-enabled routing mod enabled.
4. Keep **CarPlay Ultra** on in this mod and the matching **CarPlay theme** on in the routing mod.
5. Reconnect CarPlay. Remove the installation USB stick while establishing wireless CarPlay.
   If the Vehicle page still labels the shortcut **Radio**, or the new Climate controls remain absent, forget the car on both the head unit and the iPhone
   (**Settings → General → CarPlay**, plus Bluetooth), then pair again to refresh the cached page description.
   The old Radio action identifier is retained and also requests CarPlay audio after this update.
6. Check the version under CarPlay's **Vehicle → Head unit** section: **1.3.3-ev6.5**.

**Seat ventilation in the Climate app** and **Steering wheel heat in the Climate app** default on in HMG-API → Mods → CarPlay Ultra-Lite → Settings.
If the wheel widget obscures another button in your iOS Climate layout, switch **Steering wheel heat in the Climate app** off,
apply and restart; **Vehicle → Comfort → Steering-wheel heat** still uses the working EV6 command.
**AirPlay video while parked (experimental)** defaults off and has its own restart setting.

The update uses the same private author key as ev6.1, UUID `51951394-274a-8d4b-8396-c831913a9a44`.
Do not uninstall that existing private mod to update it. An upstream copy signed by another author cannot
be updated with this key; it must be removed before installing this fork.

## Test while parked

First test audio: play something in the desired CarPlay app, such as Audiobooth or Music. Pause it,
then tap **Vehicle → Head unit → Resume CarPlay audio**. It should open Now Playing and request playback
from the iPhone's current audio session, retaining its app and position. Confirm that Kia's FM screen stays closed.
Then disconnect/reconnect CarPlay: it should request playback once after roughly three seconds.
Repeat with audio already playing; an explicit Play request should not pause it.

In CarPlay's Climate app, check both front seats' cooling steps and OFF, heat then OFF, and wheel heat ON/OFF.
The four cooling dots map to the EV6's three physical levels: the middle two dots share medium ventilation.
With HVAC on, choose distinct driver/passenger temperatures. Turn HVAC off: the displayed setpoints should stay
at their last valid settings, without turning the system back on. Use Kia's screen to confirm actual states.

**Resume last CarPlay audio on connect** is enabled by default in HMG-API's CarPlay Ultra-Lite settings.
Turn it off for manual resume only; it is read at the next connection. The receiver cannot guarantee that
an app which the phone closed, or one needing login/network access, will resume. The log records submitted
requests, not a playback confirmation. If Now Playing opens without playback, collect the `MEDIA` lines;
there is deliberately no automatic Play/Pause toggle fallback or repeated retry.

1. Compare **battery, range and odometer** against Kia's own displays. They should occupy their correct rows.
2. Use the existing Climate page to try seat heat at **3 → 2 → 1 → Off**. Confirm physical feedback,
   and confirm Kia's buttons update CarPlay. If it remains stuck, turn it off with Kia's controls.
3. Open **Vehicle → Comfort**. Check the seat-status lines, then ventilation low/medium/high/off and wheel heat on/off.
   “State unavailable” means the required feedback is missing and the command is withheld. Ventilation requires option 6.
   These controls are on the Vehicle page; native Climate-page ventilation/wheel widgets have not been added.
4. Recheck temperature, fan and SYNC, which worked in ev6.1. Check defrost separately; rear defrost requires READY.
5. CarPlay's integrated Radio controls also request CarPlay audio instead of handing off to Kia FM.
   Integrated radio tuning remains unimplemented, so the DAB message may remain in CarPlay's Radio app.
   Use Kia's own menu when you want its FM radio.
6. Observe whether the center image still freezes. The lower virtual-cluster frame rate is a test mitigation,
   not a confirmed repair. Themes still do not replace the driver's instrument display.

## Optional parked video

Enable **AirPlay video while parked (experimental)** in the mod's HMG-API settings, apply and restart.
Leave **Keep video picture with the phone audio** on initially. With the car in **P** and CarPlay connected, open a video app on the iPhone that
offers AirPlay video and select the car as its output. Ordinary screen mirroring and FairPlay-protected services
are not supported. Start with an unprotected H.264/AAC MP4 or HLS stream; there is no new video-app launcher on the car.

The car shows video with a separate overlay containing close, pause/play, seek, fit/fill/stretch and captions when
the stream supplies them. Audio stays on the phone's normal CarPlay path. Close or use the car's Home key to return
to CarPlay. Confirm that normal CarPlay still works after closing video and after disconnect/reconnect.

Video is withheld unless the named `getGearPStatus()` getter reports `1` and its latest sample is no more than
1.5 seconds old. The getter is polled every 500 ms; a second watchdog checks freshness on the player loop.
Non-Park, missing/failed/stale feedback, or session loss stops the player and hides its windows. There is no setting
to bypass this gate. If no picture opens, capture **VIDEO Park feedback** and **VIDEO** messages while remaining parked.
The EV6's Park getter, codec support, overlay layout and actual phone-to-car playback require this on-car validation.
Disable the optional feature and restart to return to the default build behavior.

## Capture the remaining faults

**Log Kia touchscreen climate commands** is on by default. For recirculation diagnosis:

1. From Kia's own **touchscreen** climate page, switch recirculation on, wait a few seconds, then off.
   Physical buttons may bypass the Java methods being observed.
2. Repeat on/off in CarPlay, waiting for the state after each tap. The current OFF mapping is unchanged.
3. If the image freezes, note the time, then use the Kia menu round trip that restores the display.
4. Insert the USB stick containing **CarPlayUltraLite-Logs**. Wait at least 15 seconds after reproducing
   the problem. The root helper copies a recent filtered log to **CarPlayUltraLite-Logs/ev6-carplay.log**.
   Remove the stick and share that file. It excludes receiver handshakes and unrelated mods.

Useful lines start with **CAR seat feedback**, **VS phone set**, **CAR sent**, **STOCK**, **VIDEO**, **MEDIA**, **VS OPACK**, or **VEH**.
New `VS OPACK` failures identify the lane and layer, byte counts, reason, cursor offset and tag location.
Offsets are relative to the named outer or pluginData buffer; no payload bytes are exported.
`VIDEO` events include the foreground package, a CarPlay-visibility estimate and the last Vehicle-shortcut
launch's result and age. `carplayVisible` uses Android's foreground-task snapshot, so it cannot prove that
video frames are updating. `lastVehicleLaunch` covers this mod's shortcuts; other app launches have no recorded
shortcut timestamp. If the task snapshot is unavailable, visibility is `unknown`.

The export is a recent log snapshot, not a complete session recording; collect it soon after the test.
If no file appears, use HMG-API → Mods → CarPlay Ultra-Lite → Log, or Logs → Mods → Refresh; photos remain useful.
The old compatibility Markdown report is not required to proceed.

The charging API names are inventoried once by signature under **VEH charging candidate**. New power/time displays
and charging callbacks are not enabled until units and interfaces are verified. `getBatSoc()` is mapped by HMG-API
to the 12 V battery and is not used for traction-battery percentage.

## Complete install bundle

For an installation missing the theme dependency, the alternative
**carplay-ultra-lite-ev6-1.3.3-ev6.5-install-usb.zip** contains the private mod and the original signed
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
