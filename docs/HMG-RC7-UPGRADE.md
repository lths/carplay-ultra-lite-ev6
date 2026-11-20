# EV6 update: HMG-API rc.7, Wireless rc.8 and CarPlay Ultra-Lite ev6.7

This staged bundle is for the owner's **existing CVEV USA 260507 installation**. It carries unmodified official
HMG-API **1.1.0-rc.7**, Wireless CarPlay + Android Auto **2.2.0-rc.8**, optional ADB **1.0.1-rc.1**, and the private
CarPlay Ultra-Lite **1.3.3-ev6.7** update. The official packages remain signed by their original authors; the private
mod retains its existing key. This has been checked off-car; the official core/wireless versions are release candidates.

## Compatibility setting that is required with the current routing mod

In **Wireless CarPlay + Android Auto → Settings**, turn **Shared CarPlay identity OFF** before applying the Wireless
update and restarting. Keep it off while using the existing theme-enabled E-GMP routing build.

Why: both the official Wireless identity recipe and the installed routing build's `native-fc7.recipe` branch at
**0x1aa04 in libiap2session.so** and patch the same bytes at **0x129c6**. They cannot both own that serializer.
The existing routing mod continues supplying the vehicle/theme identity and live EV routing data. Turning shared
identity off skips Wireless's optional identity recipe; its normal wireless, audio and navigation features remain.
No official package has been edited or re-signed to change its defaults. A future identity-API migration must update
both consumers together; this release does not silently replace the working routing mod.

## Install the stages in order

Copy the **contents of one stage folder at a time** to the USB root. Remove the previous stage's update ZIPs from
the USB's HMGAPI/updates folder before copying the next stage; this only removes files from the stick.

1. **01-update-hmgapi:** use HMG-API → Check USB updates to install the core update. Restart the head unit once.
   Confirm the app shows **1.1.0-rc.7** and Hooks on. Remove the stick, connect CarPlay and let it run for at least
   60 seconds before the next stage; this also clears the private mod's existing crash-guard counter.
2. **02-update-wireless:** install the Wireless update. In its settings, set **Shared CarPlay identity OFF**,
   then Apply changes and restart. Remove the stick and verify ordinary wireless CarPlay for at least 60 seconds
   before the next stage.
   If HMG-API reports an author mismatch, keep the current wireless mod and resolve that mismatch before replacing it.
3. **03-update-carplay:** install **1.3.3-ev6.7**, Apply changes and restart. Copy its CarPlayUltraLite-Logs folder too.
   Keep the existing themed-routing mod enabled. Follow INSTALL-EV6.md for fresh-air learning and charging checks.
4. **optional-adb-update:** only needed if the ADB mod is older than 1.0.1-rc.1. Installing its update does not require
   enabling debugging or changing the computer's network. Existing ADB settings are retained.

Remove the installation USB while establishing wireless projection, as the official release recommends.
If a stage is already at the named version, skip that package while still checking the shared-identity setting.

## Relevant upstream changes

- Core: corrected boot-guard accounting, serialized updates, automatically saved settings, broader hook maps and
  **Logs → Record a problem**. Use the normal recording to capture a freeze or missing audio; crash/kernel captures
  remain separate opt-in diagnostics.
- Wireless: park/leave session handoff, reconnecting the same phone, two-phone arbitration, a corrected phone-connect
  settings popup and broader vehicle support. It requires core **1.1.0-rc.6 or newer**.
- ADB: hotspot firewall support. No Wi-Fi switching or device commands are performed by these build tools.
- The official release still lists cold-start Bluetooth Audio focus as an outstanding issue. Our removed automatic
  resume/Now Playing feature is not reintroduced.

Release references: [HMG-API rc.7](https://github.com/Open-HMG/HMG-API/releases/tag/v1.1.0-rc.7),
[Wireless rc.8](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases/tag/v2.2.0-rc.8).
The bundle pins official SHA-256 values, verifies signed mod packages, preserves their bytes and records stage
contents/source commit in UPGRADE.json. It does not install anything on the car from this computer.

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI). See package notices and SOURCES.txt for upstream source/licenses.
