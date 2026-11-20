# joshuaauger v1.1.15 port to EV6 ev6.5

Source reviewed: [v1.1.15 release](https://github.com/joshuaauger/carplay-ultra-lite-gen5w/releases/tag/v1.1.15),
tag commit `4f739c79c3d884f47f914468148213994d46ca46`. The implementation is under that tag's
[`source/`](https://github.com/joshuaauger/carplay-ultra-lite-gen5w/tree/v1.1.15/source) directory.
The EV6 base is private ev6.4, commit `c45b34a`, derived from SafariForest 1.3.3.

## Integrated

| Change | EV6 adaptation |
| --- | --- |
| OPACK compact negative one (`0x07`) | Decode as `-1`, without adding an object-reference entry. Preserves bounded ev6.4 parser diagnostics. The old rejected packet is unavailable, so its cause remains unproven. |
| Neutral initial media source | Clear characteristic 4354's initial `DAB` string in both descriptions. Preserve ev6.3's Now Playing/explicit Play path and one resume attempt per connection. |
| Native front seat cooling | Publish level minimum -4; feedback low/medium/high is -1/-2/-4. Commands -2 and -3 both select physical medium. Negative levels remain on. Existing BCM preference, fresh feedback, option-6 check and OFF priority apply. |
| Native wheel heat | Add service 571, level 572, range 0–1. Map to the EV6's existing direct VR wheel command and verified body-listener feedback. The Ioniq fork's toggle command is not used. Optional native widget can be disabled if iOS places it poorly. |
| HVAC power | Declare missing writable characteristic 514 in the cabin; retain the existing blower/system-power mapping. |
| Temperatures while off | Retain valid driver/passenger setpoints in memory; ignore the off-state placeholder. Restart clears that memory until the next valid on-state reading. |
| Complete vehicle description | Generate `/info` and channel descriptions with the same code, actual native-control settings and all three existing theme keys. Write the runtime plist atomically before patching. Refuse the patch if it cannot be written. |
| Cached schema versions | Per-accessory CRC versions change with the final schema. Mutable initial readings are excluded to avoid repeated cache invalidation. |
| Optional AirPlay video | Port ExoPlayer 2.19.1, Bouncy Castle phone proxy/TLS, controls overlay, seeks/fit/captions, retries, synchronization, live-edge handling and return-to-CarPlay behavior. Disabled by default. |

## Parked-video changes

The original fork's player is extended with an active-session and Park gate. The getter is resolved by name and
signature from `com.hkmc.micom.ISystemService.getGearPStatus(): int`, through service `com.hkmc.micom.system`.
The HMG-API rc.1 catalogue describes `1` as Park. No binder transaction number or new enum is guessed.
Polling is every 500 ms. Unknown/error/non-Park samples immediately invalidate the gate; samples older than
1.5 seconds also invalidate it. Both the feedback thread and player-loop watchdog request stop. Message processing,
screen acquisition and delayed retry check the gate. Stopping releases the decoder and hides both overlays.
Session finalization also stops playback and deletes the saved proxy/parameter files; restart deletes stale files.
Returning to Park does not automatically restart a stopped item.

The head unit's player has audio disabled; normal CarPlay audio remains on the phone. Encrypted settings/overlay
streams are acknowledged and discarded. The raw side-stream recorder and USB video-recording workflow are omitted.
Message logs summarize field types/sizes; exception diagnostics redact URLs and authorization headers. Local video
trace files rotate at approximately 512 KiB each and contain the same redacted diagnostics.

## Native provenance and build

Target remains CVEV USA 260507 with stock `libCarPlayCore.so` MD5 `d3a47296437df56fb1ffc7ac3dfca4cb`.
Keep every original guard and the first 33 bindings. Add the pinned fork's matching-library command hook at
`0xaf10`, its `0xaf08`/20-byte verify fingerprint, and 14 additional bindings (47 total).
The fork's other-firmware recipe is excluded. The strict rebuilder pins the full verify/patch list and all targets,
checks the embedded description, rejects relocations/undefined symbols and permits one allocated segment.
`-fno-jump-tables` avoids an absolute-address switch table outside that segment.

Application Java sources compile against Android API 19. The player dependencies and R8 8.5.35 are SHA-256 pinned
in `video/fetch-libs.sh`; no downloaded library belongs in Git. R8 uses verified API 34 linking stubs for guarded
ExoPlayer paths, with minimum API 19 and one DEX 035 required. Notices/license texts accompany the signed package.
The EV6 author key and UUID are retained. The full install bundle's signed routing dependency is unchanged.

The packaged DEX contains 86 references absent from API 19, primarily the dependency's newer media/audio/display
implementations. This inventory is not a list of runtime failures: ExoPlayer uses SDK checks and separate API helper
classes, and its [pinned build supports API 16 and later](https://github.com/google/ExoPlayer/blob/r2.19.1/constants.gradle).
It also is not a device-runtime test; decoder initialization and Dalvik behavior still need the parked car test.

## Validation and limits

`tools/test.py` checks native hooks, complete description equality, old EV6 behavior, new front-control writes and
feedback, negative OPACK references, schema versions, Park freshness and encrypted video datagrams/side streams.
`tools/test_video.py` checks the imported synchronization rules, a local TLS 1.3 PSK proxy (including wrong key and
bad authorization), supplemental certificate parsing, redaction, redirects/chunking/byte ranges and ordered parallel
downloads through a retried failure. SDK hook-floor and shell-command checks remain mandatory.

These are host tests. Native control rendering, actual Park feedback, phone video negotiation, hardware decoding,
overlay composition, screen handoff and synchronized audio still require a parked on-car test. Host success does
not establish an EV6 freeze repair. Protected FairPlay playback is unsupported.

Rear-seat heating, rear cabin zones, ambient RGB, raw payload recording and other model/firmware recipes were
not imported. Unreleased custom home tiles and the physical instrument cluster are outside the released source.
Recirculation OFF, Launchpad preconditioning and Apple Maps energy calibration retain their documented limits.

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI). GPL-3.0-or-later; see repository and video notices.
