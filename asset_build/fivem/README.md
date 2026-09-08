# Industrial Scrap Shredder

Drop this resource into your FiveM resources folder and add `ensure vrp-scrap-shredder` to `server.cfg`.

- Model/spawn name: `industrial_scrap_shredder_v24`
- Approximate size: 10.70 m long, 4.08 m wide, 4.72 m tall
- Includes high, medium, and low visual LODs
- Includes an inclined ground-level feed conveyor that clears the hopper wall and drops through the open top
- Includes attached portal-frame supports and sealed hopper corner channels
- Includes five conveyor portal frames, X-bracing, foundation rails, a hopper saddle, and four chamber pedestals
- Includes an open collision shell plus conveyor, ladder, and control-cabinet collision
- Includes animated input/output belt cleats and counter-rotating cutters
- Belt cleats remain inside their rollers throughout the full animation cycle
- Carries the local player and dynamic props up the intake belt while running
- Converts props entering the cutter throat into temporary scrap pieces on the output belt
- Includes its own lightweight native scrap-fragment model for the output pieces
- Uses a synchronized control-panel target with automatic `ox_target` or `qb-target` detection
- Uses a compact waist-height control cabinet with an enlarged forward target zone
- Textures are embedded in the YDR; no separate YTD is required

## Test commands

- `/spawnshredder` - spawns one animated shredder about 8 meters in front of you
- `/spawnshredder 180` - spawns it with a specific heading
- `/deleteshredder` - removes the shredder spawned by your command
- `/toggleshredder` - fallback power toggle when no supported target resource is running

The spawn location is printed to the F8 console as a ready-to-copy `vector4`.
Only one test shredder is kept per player. Its animated components and generated
scrap pieces are removed with it and when the resource stops. To disable the test commands on a live server, add this to
`server.cfg`:

```cfg
setr vrp_shredder_test_commands 0
```

## Control panel and conveyor gameplay

The machine spawns switched off. Target its control panel and select
`Toggle industrial shredder`; `ox_target` and `qb-target` are detected
automatically. The synchronized power state controls the belt animation,
cutters, player transport, prop transport, shredding and output pieces.

Optional `server.cfg` settings:

```cfg
setr vrp_shredder_carry_players 1
setr vrp_shredder_carry_props 1
setr vrp_shredder_player_damage 1
setr vrp_shredder_piece_count 4
setr vrp_shredder_piece_lifetime_ms 45000
setr vrp_shredder_target_debug 0
```

For performance, world-object scans run only within 70 meters of a powered
shredder, at a 220 ms interval. Output debris is capped at 48 pieces and is
automatically cleaned up. Unattached dynamic objects and script-created mission
props are transported; frozen map scenery is ignored.

Gameplay resources can listen for the local event below to award materials or
run custom effects. `sourceModel` is the original model hash.

```lua
AddEventHandler('vrp-scrap-shredder:shredded', function(shredder, sourceModel, pieceCount)
    -- Add your server-authoritative reward request here.
end)
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

Rewards, inventory removal, particles and sound remain intentionally separate
so your scrap-yard resource can validate them server-side. The included carrier
only moves nearby dynamic objects and the local player.
