# VRP Scrap Shredder

Drop this resource into your FiveM resources folder and add `ensure vrp-scrap-shredder` to `server.cfg`.

- Model/spawn name: `vrp_scrap_shredder`
- Approximate size: 8.25 m long, 4.08 m wide, 4.18 m tall
- Includes high, medium, and low visual LODs
- Includes simplified embedded metal collision
- Textures are embedded in the YDR; no separate YTD is required

## Test commands

- `/spawnshredder` - spawns one shredder about 6 meters in front of you
- `/spawnshredder 180` - spawns it with a specific heading
- `/deleteshredder` - removes the shredder spawned by your command

The spawn location is printed to the F8 console as a ready-to-copy `vector4`.
Only one test shredder is kept per player, and it is cleaned up when the
resource stops. To disable the test commands on a live server, add this to
`server.cfg`:

```cfg
setr vrp_shredder_test_commands 0
```

Example placement:

```lua
local model = joaat('vrp_scrap_shredder')
RequestModel(model)
while not HasModelLoaded(model) do Wait(0) end

local shredder = CreateObjectNoOffset(model, x, y, z, false, false, false)
SetEntityHeading(shredder, heading)
FreezeEntityPosition(shredder, true)
SetModelAsNoLongerNeeded(model)
```

The cutter geometry is part of the static prop. Animate separate gameplay effects in your scrap-yard script if desired.
