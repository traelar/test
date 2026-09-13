# Aevorwyn Original Content + Modular Character System Design

## Status
Approved direction for Aevorwyn. This spec establishes a hard project rule and the first implementation target under that rule.

## Hard Project Rule: Original Aevorwyn Content
All shipped Aevorwyn game content must be original and created specifically for Aevorwyn.

Third-party assets may be used only as temporary development references or disposable placeholders. They must not remain in production/shipped builds.

This applies to:
- player characters
- body meshes, heads, faces, hair, facial hair
- clothing and armor
- weapons and tools
- animations
- enemies and NPCs
- trees, rocks, foliage, terrain art
- buildings and props
- UI artwork, icons, panels, cursors, skill icons
- VFX and particles
- environment art and world dressing
- sounds/music where applicable

References may influence general quality targets, but Aevorwyn must not copy protected designs, assets, silhouettes, exact interfaces, or branded content from RuneScape or any other game.

## Immediate Goal
Replace the current third-party ranger/placeholder character path with an original Aevorwyn modular player-character system and RuneScape-style character customization.

The replacement must solve the current visible problems at the root:
- no missing head or faceless hood
- no blank/dark face
- correct model forward direction
- correct walking direction
- correct tree-facing behavior
- correct axe/tool orientation
- animation-compatible body proportions
- consistent visual identity

## Character Architecture
Aevorwyn uses one shared humanoid rig contract for player characters. Visual parts are modular and attach to the same skeleton so appearance changes do not require replacing gameplay code.

Core modules:
- Body
- Head/Face
- Hair
- Facial Hair
- Torso Clothing
- Leg Clothing
- Feet
- Optional cosmetic/accessory slots
- Equipped weapon/tool visuals

Male/female body types use compatible animation semantics. They may use separate meshes and proportion tuning, but gameplay animation names and attachment points remain consistent.

## Original Base Character Style
Target style: stylized 3D fantasy RPG, cleaner and more modern than OSRS while remaining mobile-friendly.

The base character should have:
- readable human proportions
- visible eyes, nose, mouth, ears, jawline
- clear face at Aevorwyn's normal camera distance
- simplified but intentional geometry
- clean silhouette
- smooth but performance-conscious skinning
- hands capable of reading correctly around tools/weapons
- enough facial geometry for variation without requiring full cinematic facial animation

Do not ship a featureless mannequin, faceless hood, primitive block head, or externally sourced finished character model.

## Character Creator
Character creation should feel familiar to players of classic RPG/MMO character creators without copying RuneScape's exact UI.

Initial customization categories:
- body type: male / female
- skin tone
- face preset
- hair style
- hair color
- facial hair style where supported
- facial hair color
- starter top style
- starter top color
- starter bottoms style
- starter bottoms color
- starter footwear style
- footwear color where applicable

The UI must show a live 3D preview with rotation.

A Randomize option may be included after the core controls work.

## Appearance Data Model
Appearance is saved as structured player data rather than baked into the scene.

Example fields:
- body_type
- skin_tone_id
- face_id
- hair_style_id
- hair_color_id
- facial_hair_style_id
- facial_hair_color_id
- top_style_id
- top_color_id
- bottoms_style_id
- bottoms_color_id
- footwear_style_id
- footwear_color_id

IDs must be stable so future content can be added without invalidating existing saves.

## Equipment Integration
Character appearance and equipment are separate layers.

Base customization defines the unequipped appearance. Equipment can visually replace or layer over compatible body slots.

Examples:
- chest armor replaces/hides the starter torso clothing where needed
- helmets can hide or modify hair
- boots can replace starter footwear
- weapons/tools attach to named skeleton sockets

The inventory/equipment system remains authoritative. Visuals reflect equipped state; they do not independently decide gameplay state.

## Animation Contract
The original character system must expose a stable animation contract for gameplay systems.

Initial required animation states:
- Idle
- Walk
- Run if/when enabled
- Turn/rotation blending as needed
- TreeChop
- Mining swing
- Fishing interaction
- Basic melee attack
- Hit reaction
- Death

The character must face the gameplay target before interaction animations begin.

Tool/weapon sockets must use deliberate local transforms verified visually. No arbitrary 180-degree flip hacks.

## Woodcutting Specific Requirements
Woodcutting must use Aevorwyn's own character and axe/tool visuals.

Required behavior:
- player moves into interaction range
- player rotates to face the tree
- axe is held by the handle in a readable grip
- backswing occurs on the correct side of the body
- strike travels toward the trunk
- impact frame aligns with gathering impact logic
- recovery returns to a natural ready position
- repeated attempts can loop without snapping orientation

The old third-party ranger + imported axe setup is not the final solution and must be removed from shipped builds.

## Mobile Input Preservation
The character rewrite must not regress current mobile controls.

Required mobile behavior:
- short tap ground -> walk
- stationary long-hold ground -> walk on release
- long-hold interactable -> context menu
- drag -> rotate camera only
- pinch -> zoom only
- camera drag/zoom must never become a movement request on release

Regression tests stay mandatory.

## Visual Pipeline Rule
Original art assets should live under Aevorwyn-owned content directories, not vendor directories.

Suggested structure:
- assets/aevorwyn/characters/
- assets/aevorwyn/hair/
- assets/aevorwyn/clothing/
- assets/aevorwyn/weapons/
- assets/aevorwyn/tools/
- assets/aevorwyn/world/
- assets/aevorwyn/ui/
- assets/aevorwyn/vfx/

Vendor/temporary reference directories must be clearly marked and excluded from production builds once replacements exist.

## First Implementation Milestone
The first deliverable under this spec is a production-ready character foundation, not the entire game's art replacement in one pass.

Milestone 1 includes:
1. original shared humanoid rig contract
2. original male base body
3. original female base body
4. visible original head/face meshes
5. multiple original face presets
6. multiple original hairstyles
7. starter clothing variants
8. color customization
9. saved appearance data
10. character creator UI with live preview
11. runtime character assembler
12. equipment/tool attachment sockets
13. original basic idle/walk/chop animation set
14. migration away from the current third-party ranger player model
15. mobile input regressions preserved

## Future Original-Content Milestones
After the character foundation works, replace placeholder/vendor content in focused batches:
- player tools/weapons
- gathering resources
- environment foliage
- buildings/props
- monsters/NPCs
- UI/icons
- VFX
- audio/music if externally sourced

Each replacement should preserve gameplay behavior while moving production content toward 100% Aevorwyn-owned original assets.

## Testing / Acceptance
Character-system work is not accepted solely because the project compiles.

Required checks:
- automated regression tests for appearance data and slot assembly
- automated tests for camera-drag input separation
- automated tests for target-facing helpers
- Godot import/compile clean
- main scene boot clean
- Android export clean
- signing identity preserved
- on-device visual verification for face visibility, walking direction, tree-facing, axe grip/orientation, and chop arc

## Definition of Done for This Subsystem
The character subsystem is complete when a player can create and save a visibly original Aevorwyn character, load back into the world with the same appearance, walk facing the correct direction, rotate the camera without unintended walking, approach a tree, face it correctly, and perform an original readable woodcutting animation using an Aevorwyn-made axe/tool visual.
