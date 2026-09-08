# Industrial Scrap Shredder

Drop this resource into your FiveM resources folder and add `ensure vrp-scrap-shredder` to `server.cfg`.

- Model/spawn name: `vrp_scrap_shredder`
- Approximate size: 10.45 m long, 4.08 m wide, 4.22 m tall
- Includes high, medium, and low visual LODs
- Includes an inclined ground-level feed conveyor into the hopper
- Includes attached portal-frame supports and sealed hopper corner channels
- Includes an open collision shell, conveyor collision, and ladder collision
- Includes animated input/output belt cleats and counter-rotating cutters
- Textures are embedded in the YDR; no separate YTD is required

## Test commands

- `/spawnshredder` - spawns one animated shredder about 8 meters in front of you
- `/spawnshredder 180` - spawns it with a specific heading
- `/deleteshredder` - removes the shredder spawned by your command

The spawn location is printed to the F8 console as a ready-to-copy `vector4`.
Only one test shredder is kept per player, and it is cleaned up when the
resource stops. To disable the test commands on a live server, add this to
`server.cfg`:

```cfg
setr vrp_shredder_test_commands 0
```

## Script integration

Create the complete animated assembly from another client resource:

```lua
local shredder, err = exports['vrp-scrap-shredder']:CreateAnimatedShredder(
    vector3(x, y, z),
    heading,
    false -- set true only when one client should network the assembly
)
```

Delete an assembly with:

```lua
exports['vrp-scrap-shredder']:DeleteAnimatedShredder(shredder)
```

The belt/cutter animation is visual. Your scrap-yard gameplay script should
control carried-item placement, item travel, deletion, rewards, particles, and
sound so random world objects are never moved automatically.
