# cp-climate

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI)

`recipes/carplay-climate-m1.recipe` embeds `vehicleStateProtocolInfo` as produced by LIVI's
`vehicle_state` code (GPL-3.0-or-later, see data/LICENSE and data/NOTICE in the source
folder; LIVI commit in data/LIVI-COMMIT). The rest is this mod's own code, GPL-3.0-or-later.

EV6 ev6.5 adapts front Climate/schema/protocol improvements, native video hooks and the optional video engine
from joshuaauger/carplay-ultra-lite-gen5w v1.1.15, commit `4f739c79c3d884f47f914468148213994d46ca46`,
under GPL-3.0-or-later: https://github.com/joshuaauger/carplay-ultra-lite-gen5w/tree/v1.1.15/source.
Changes include EV6 command/feedback retention, an enforced fresh-Park/session gate, diagnostic redaction,
and replacing raw video side-stream recording with an acknowledging sink. Dependency notices and license texts
ship in `video-licenses/` in the signed package and `video/licenses/` in source.
