# Industrial Scrap Shredder v2.5

Requires `ox_lib`, `ox_inventory`, and either `ox_target` or `qb-target`.
Add `ensure vrp-scrap-shredder` after those resources in `server.cfg`.

## Main features

- Native FiveM `industrial_scrap_shredder_v24` model with LODs and collision
- Animated input/output belts and counter-rotating cutters
- Functional conveyors that carry players and dynamic props
- Compact collidable control cabinet with silent synchronized power toggle
- Distance-faded industrial shredder loop while powered
- Admin ghost-placement creator with ground snapping and Q/E rotation
- Persistent placements stored in FiveM resource KVP across restarts
- In-game recipe creator supporting multiple inventory items and prop models
- Server-authoritative ox_inventory removal and collection
- Physical input prop travels up the belt and disappears into the cutters
- A configurable processed-scrap prop exits the other belt and must be collected

## Admin creator

Grant access in `server.cfg`:

```cfg
add_ace group.admin command.shreddercreator allow
add_ace group.admin command.placeshredder allow
add_ace group.admin command.removeshredder allow
```

Commands:

- `/shreddercreator` – opens placement, removal, and recipe management
- `/placeshredder` – opens ghost placement directly
- `/removeshredder` – permanently removes the nearest saved shredder

During ghost placement, aim where the machine should sit. The preview snaps to
the ground beneath the aim point. Use `Q` and `E` to rotate, hold Shift for
faster rotation, press Enter to save, or Backspace to cancel. Saved machines
return automatically after resource/server restarts and restart powered off.

## Recipe creator

Open `/shreddercreator` and choose **Manage processing recipes**. Each recipe
stores:

- Recipe ID and display label
- Input ox_inventory item name
- Input prop model shown on the feed belt
- Output ox_inventory item name and label
- Output scrap prop model shown on the discharge belt
- Minimum and maximum output quantity

The item names must exist in ox_inventory. Base-game GTA prop names work
directly. A custom prop model must be streamed by this resource or another
started resource. The included `industrial_scrap_shredder_v24_chunk` is a
ready-to-use generic scrap output model.

When powered on, players target the bottom of the intake conveyor and choose a
configured recipe. One input item is removed server-side, its configured model
travels through the machine, and the configured output model travels out the
other side. Players must target that physical result and choose
**Collect processed scrap** before receiving the output item.

Recipes and placements are saved through resource KVP, so no database table is
required. `config.lua` contains optional default recipes and sound/gameplay
settings; recipes created in game become the saved authoritative list.

## Test commands

- `/spawnshredder` – temporary test machine about 8 meters in front of you
- `/spawnshredder 180` – temporary test machine with a chosen heading
- `/deleteshredder` – removes the temporary test machine and its debris
- `/toggleshredder` – fallback power toggle when no target resource is running

`/spawnshredder` does not persist. Use the admin creator for permanent machines.
Disable test commands on a live server with:

```cfg
setr vrp_shredder_test_commands 0
```

## Performance and settings

World-object scans only run within 70 meters of a powered machine at a 220 ms
interval. Debris is capped at 48 pieces. Audio updates at 350 ms while active
and only the nearest configured number of machines can play simultaneously.

Settings are documented in `config.lua`. Existing gameplay convars:

```cfg
setr vrp_shredder_carry_players 1
setr vrp_shredder_carry_props 1
setr vrp_shredder_player_damage 1
setr vrp_shredder_piece_count 4
setr vrp_shredder_piece_lifetime_ms 45000
setr vrp_shredder_target_debug 0
```

## Script integration

```lua
local shredder, err = exports['vrp-scrap-shredder']:CreateAnimatedShredder(
    vector3(x, y, z), heading, false
)

exports['vrp-scrap-shredder']:DeleteAnimatedShredder(shredder)
```

Generic dynamic props still fire the local compatibility event:

```lua
AddEventHandler('vrp-scrap-shredder:shredded', function(shredder, sourceModel, pieceCount, recipeId)
    -- recipeId is supplied for configured processing and nil for generic props.
end)
```
