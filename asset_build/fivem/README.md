# VRP Scrap Shredder

Drop this resource into your FiveM resources folder and add `ensure vrp-scrap-shredder` to `server.cfg`.

- Model/spawn name: `vrp_scrap_shredder`
- Approximate size: 8.25 m long, 4.08 m wide, 4.18 m tall
- Includes high, medium, and low visual LODs
- Includes simplified embedded metal collision
- Textures are embedded in the YDR; no separate YTD is required

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
