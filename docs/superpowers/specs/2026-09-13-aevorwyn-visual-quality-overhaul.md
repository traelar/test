# Aevorwyn Visual Quality & Character Animation Overhaul

**Status:** Approved for implementation

## Goal
Replace the current prototype-looking character animation and starter-area presentation with a cohesive, high-quality stylized 3D fantasy presentation suitable for Android on a Galaxy S25 Ultra and later Windows builds, while preserving point-and-tap gameplay and current progression systems.

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
- Upgrade starter-zone terrain with elevation/shape variation, better path edges, grass/ground breakup, rocks, shrubs, flowers, and water treatment.
- Upgrade trees to more believable stylized trunks, branches, root flare, layered canopies, and resource-state feedback.
- Improve lighting with a warm directional key, ambient fill, shadows, sky color, light fog/atmosphere, and better material roughness/contrast.
- Replace placeholder structures with recognizable stylized settlement props/buildings where visible in the starter view.
- Keep the mobile HUD compact and secondary to the world view.

## Technical Constraints
- Godot 4.7.2.
- Android ARM64 first; Galaxy S25 Ultra is the primary test target.
- Package ID remains `com.aevorwyn.game`.
- Existing stable Android signing identity must be preserved so APKs install as updates.
- Current tap-to-move, hold-for-context, drag-to-rotate, and pinch-to-zoom behavior must remain intact.
- Camera drag and pinch gestures must never trigger movement.
- Existing skill, gathering, inventory, save, combat, and progression behavior must not be regressed.
- Use original or redistribution-safe assets only. No copied Jagex/RuneScape art, audio, models, maps, or animations.

## Quality Bar
The starter area should read as an intentional game scene at first glance rather than an engine prototype. The character’s axe handling must look physically coherent from the gameplay camera, and the environment must have enough shape, material variation, lighting, props, and depth to look materially better than the current blockout while retaining smooth mobile performance.

## Verification
- Add automated animation-state tests for correct axe orientation/grip/forward strike invariants.
- Add regression coverage for existing mobile gesture behavior.
- Godot headless import and full test suite must pass.
- Android release export must pass.
- Stable signing certificate must verify.
- APK artifact must be produced before the build is presented as complete.
