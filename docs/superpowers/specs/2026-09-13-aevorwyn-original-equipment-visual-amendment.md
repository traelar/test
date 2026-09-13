# Aevorwyn Original Equipment Visual Amendment

## Status
Approved amendment to `2026-09-13-aevorwyn-original-content-character-system-design.md`.

## Rule
All wearable/equippable visuals shipped with Aevorwyn are original Aevorwyn content. Third-party armor, clothing, helmets, capes, shields, weapons, tools, meshes, textures, rigs, or animation assets are not production content.

## Stable Character Equipment Visual Slots
The modular player-character assembler must support these stable visual slots from the character foundation onward:

- `head`
- `chest`
- `hands`
- `legs`
- `feet`
- `cape`
- `main_hand`
- `off_hand`

The inventory/equipment state in `GameSession` remains authoritative. Character visuals only mirror that state.

## Layering and Hide Rules
- Head equipment can hide or modify hair when its definition requires it, but the player face must remain intentionally visible unless an original item is explicitly designed to cover it.
- Chest equipment replaces/hides starter torso clothing where needed.
- Hand equipment replaces/covers base hand/palm visuals where needed.
- Leg equipment replaces/hides starter leg clothing where needed.
- Foot equipment replaces/hides starter footwear where needed.
- Cape/back equipment attaches behind the torso without replacing the body.
- Main-hand equipment attaches to the right-hand/tool socket contract.
- Off-hand equipment attaches to the left-hand/off-hand socket contract.
- Clearing a slot must restore the correct underlying appearance layer.

## Extensibility
Future original Aevorwyn armor sets must plug into these slots without replacing the base character rig or changing gameplay equipment interfaces. Armor definitions may add metadata such as palette, coverage/hide flags, body-type-compatible visual parameters, and socket transforms while preserving stable item IDs and slot IDs.

## Acceptance
Automated regressions must verify the slot list, representative armor creation, hair/clothing hide behavior, off-hand support, and restoration when equipment is removed. Production validation must reject vendor asset references and Android builds must preserve the existing package/signing identity.
