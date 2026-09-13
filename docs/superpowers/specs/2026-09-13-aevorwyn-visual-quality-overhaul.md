# Aevorwyn Visual Quality, Character Animation & Player UI Overhaul

**Status:** Approved for implementation

## Goal
Replace the current prototype-looking character animation and starter-area presentation with a cohesive, high-quality stylized 3D fantasy presentation suitable for Android on a Galaxy S25 Ultra and later Windows builds, while preserving point-and-tap gameplay and current progression systems. Replace the placeholder HUD with a compact, functional player interface whose Inventory and Skills tabs are wired to authoritative saved game state.

## Character & Animation
- Replace the current primitive/procedural chop behavior with a believable two-handed woodcutting motion.
- The axe head must point forward/outward correctly; the player must never hold the tool upside down or swing it behind the body.
- Chopping stance: player faces the target tree, feet planted in a staggered stance, shoulders and hips rotate naturally.
- Chopping cycle: ready stance -> short backswing -> diagonal/side strike into trunk -> impact -> recoil -> recovery.
- Both hands visibly grip the axe handle at different heights; the axe remains aligned with the hands throughout the swing.
- Chopping repeats until the existing Woodcutting/tool/resource success roll awards a log.
- Add clean idle/walk/turn/chop transitions with no snapping.
- Add subtle tree impact feedback: shake, wood-chip particles, and an impact sound hook.

## Visual Direction
- Target a polished stylized fantasy RPG look, not bare primitive blockout art and not a copy of RuneScape assets/UI.
- Preserve efficient mobile rendering and landscape play.
- Upgrade the player silhouette, proportions, clothing/material treatment, and equipment presentation.
- Prefer a coherent rigged CC0 fantasy character and CC0 environment assets over continuing to polish block primitives when the licensed assets materially improve quality.
- Upgrade starter-zone terrain with elevation/shape variation, better path edges, grass/ground breakup, rocks, shrubs, flowers, and water treatment.
- Upgrade trees to more believable stylized trunks, branches, root flare, layered canopies, and resource-state feedback.
- Improve lighting with a warm directional key, ambient fill, shadows, sky color, light fog/atmosphere, and better material roughness/contrast.
- Replace placeholder structures with recognizable stylized settlement props/buildings where visible in the starter view.
- Keep the mobile HUD compact and secondary to the world view.

## Player UI Shell
- The game world remains visible while player panels are open; no full-screen menu takeover during ordinary play.
- Use a compact right-side landscape panel with Aevorwyn-specific visual language rather than copying RuneScape artwork or exact interface layout.
- Primary tabs in this pass: Inventory and Skills. The shell must be extensible for Equipment, Quests, Map, Settings, and future systems.
- A small persistent status HUD continues to show health and other critical combat information without covering the play area.
- Touch targets must be comfortable on a Galaxy S25 Ultra in landscape orientation.
- Panel open/close and tab switching must not cause world taps behind the UI to move the character.

## Inventory Tab
- Show the authoritative fixed-slot inventory as a real slot grid; do not invent duplicate UI-only inventory state.
- Each occupied slot displays an item visual/icon treatment, quantity for stackable items, and selected/highlight state.
- Empty slots remain visibly available without dominating the screen.
- Short tap invokes the safe default action for the item when one exists; otherwise it selects the slot.
- Long-hold opens a contextual item menu whose actions are derived from item capabilities. Initial supported actions: Use, Wield/Equip when appropriate, Eat when consumable, Drop, Examine, and Cancel.
- Inventory actions must call GameSession/domain APIs rather than directly mutating UI data.
- The tab refreshes immediately after gathering, loot, consumption, dropping, loading a save, or any other authoritative inventory change.
- Drag-to-reorder is intentionally deferred until after the basic authoritative interaction flow is stable.

## Skills Tab
- Display all 14 current skills: Attack, Strength, Defense, Vitality, Ranged, Magic, Mining, Smithing, Woodcutting, Firemaking, Fishing, Cooking, Crafting, and Herbalism.
- Each skill row/tile shows a distinct icon treatment, skill name, current level, and readable XP/progress state.
- Show Total Level prominently but compactly.
- Tapping a skill opens an in-panel details view showing current level, current XP, XP required for next level, progress bar, and current known training/unlock information where available.
- Skills data comes directly from GameSession/SkillService state and updates immediately when XP or level changes.
- Do not copy RuneScape skill icons; use original Aevorwyn iconography or simple original vector/shape treatments until final icon art is created.

## Technical Constraints
- Godot 4.7.2.
- Android ARM64 first; Galaxy S25 Ultra is the primary test target.
- Package ID remains `com.aevorwyn.game`.
- Existing stable Android signing identity must be preserved so APKs install as updates.
- Current tap-to-move, hold-for-context, drag-to-rotate, and pinch-to-zoom behavior must remain intact.
- Camera drag and pinch gestures must never trigger movement.
- Existing skill, gathering, inventory, save, combat, and progression behavior must not be regressed.
- UI must never mutate raw gameplay dictionaries directly when an authoritative service/session method exists.
- Use original or redistribution-safe assets only. No copied Jagex/RuneScape art, audio, models, maps, icons, or animations.

## Asset Provenance
- CC0 assets from Quaternius may be used when their bundled license explicitly states CC0 1.0 Universal.
- Vendor only the models/textures needed for the starter build; avoid blindly importing entire multi-hundred-megabyte packs.
- Keep an asset credits/provenance file with source, author, license, local path, and any modifications even when attribution is not legally required.

## Quality Bar
The starter area should read as an intentional game scene at first glance rather than an engine prototype. The character’s axe handling must look physically coherent from the gameplay camera, and the environment must have enough shape, material variation, lighting, props, and depth to look materially better than the current blockout while retaining smooth mobile performance. Inventory and Skills must look and behave like real game systems rather than debug widgets.

## Verification
- Add automated animation-state tests for correct axe orientation/grip/forward strike invariants.
- Add regression coverage for existing mobile gesture behavior.
- Add UI tests for authoritative inventory slot rendering, item-context actions, all 14 skills, Total Level, XP-to-next-level, and touch/input blocking behind open panels.
- Godot headless import and full test suite must pass.
- Android release export must pass.
- Stable signing certificate must verify.
- APK artifact must be produced before the build is presented as complete.
