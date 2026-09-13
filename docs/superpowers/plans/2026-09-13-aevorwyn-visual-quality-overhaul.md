# Aevorwyn Visual Quality & Character Animation Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Aevorwyn's prototype-looking axe handling, player presentation, trees, terrain, lighting, and starter-zone dressing with a coherent stylized fantasy presentation that remains performant on Android and preserves the existing point-and-tap gameplay.

**Architecture:** Keep gameplay state and interaction services unchanged. Introduce focused visual components for character presentation, resource presentation, world dressing, and impact FX so art/animation can evolve without contaminating progression logic. Prefer redistribution-safe CC0 assets where they materially improve quality, while retaining deterministic fallbacks so CI and the game can still load if a visual asset is unavailable.

**Tech Stack:** Godot 4.7.2, GDScript, Godot glTF/GLB import, Android ARM64, GitHub Actions, CC0 stylized 3D assets.

**Spec:** `docs/superpowers/specs/2026-09-13-aevorwyn-visual-quality-overhaul.md`

## Global Constraints

- Godot 4.7.2.
- Android ARM64 first; Galaxy S25 Ultra is the primary test target.
- Package ID remains `com.aevorwyn.game`.
- Existing stable Android signing identity must be preserved.
- Tap-to-move, hold-for-context, drag-to-rotate, pinch-to-zoom remain unchanged.
- Camera drag and pinch must never trigger movement.
- Existing gathering, inventory, skill, save, combat, and progression behavior must remain intact.
- Only original or redistribution-safe assets may be added; no Jagex/RuneScape art, audio, models, maps, or animations.

---

### Task 1: Axe orientation and two-handed chop contract

**Files:**
- Modify: `.aevorwyn/mobile-context-animation-green.patch.part01`
- Modify: `.aevorwyn/mobile-context-animation-green.patch.part02`
- Modify: `tests/test_player_action_animator.gd` through a new regression patch
- Test: `tests/test_player_action_animator.gd`

**Interfaces:**
- Consumes: `PlayerActionAnimator.pose() -> Dictionary`
- Produces: chop pose keys `left_arm`, `right_arm`, `left_elbow`, `right_elbow`, `left_wrist_yaw`, `right_wrist_yaw`, `torso_pitch`, `torso_yaw`, `axe_pitch`, `axe_yaw`, `axe_roll`, `grip_upper`, `grip_lower`, `axe_visible`.

- [ ] **Step 1: Write failing tests** asserting the axe head faces toward the target during impact, both hands remain on the handle, the strike travels forward/sideways rather than behind the spine, and recovery returns to a stable ready stance.
- [ ] **Step 2: Run Godot tests** and verify the new tests fail specifically on the missing orientation/grip invariants.
- [ ] **Step 3: Implement a proper four-phase chop pose**: ready, compact backswing, diagonal side strike, recoil/recovery. Drive shoulders, elbows, wrists, torso yaw, weight shift, and axe transform together.
- [ ] **Step 4: Update the player visual rig** so the axe is parented to a dedicated two-hand grip rig rather than a single forearm, and place the axe head/handle in correct local orientation.
- [ ] **Step 5: Run the focused tests and full suite** until green.
- [ ] **Step 6: Commit** with `fix: rebuild two-handed axe chopping motion`.

### Task 2: Higher-quality player presentation

**Files:**
- Create: `src/player/player_visual_profile.gd`
- Modify: `src/player/player_visual.gd`
- Modify: `scenes/player.tscn`
- Create/Test: `tests/test_player_visual_quality.gd`

**Interfaces:**
- Produces: `PlayerVisualProfile` material palette, body proportions, equipment attachment transforms, and silhouette quality settings.
- Player visual continues consuming `PlayerActionAnimator.pose()` only; gameplay code does not animate bones directly.

- [ ] **Step 1: Write failing presentation tests** requiring articulated hands/forearms, non-cubic torso/head proportions, dedicated equipment anchors, and no deprecated one-piece primitive limb path.
- [ ] **Step 2: Run tests** and verify failure on the existing primitive presentation.
- [ ] **Step 3: Implement a more coherent stylized character silhouette** with tapered limbs, layered tunic/boots/belt, better head/hair proportions, hands, shoulder/hip offsets, and improved materials/roughness.
- [ ] **Step 4: Add equipment anchors** for right hand, left hand, back, head, and main-hand tool alignment.
- [ ] **Step 5: Run tests and Godot import** and commit `feat: upgrade player presentation rig`.

### Task 3: Resource tree presentation and chop impact FX

**Files:**
- Create: `src/world/tree_visual.gd`
- Create: `src/fx/woodcutting_impact_fx.gd`
- Modify: `src/world/resource_node.gd`
- Modify: `scenes/resource_node.tscn`
- Modify: `src/game/main_world.gd`
- Create/Test: `tests/test_tree_visual.gd`, `tests/test_woodcutting_impact_fx.gd`

**Interfaces:**
- `TreeVisual.set_depleted(bool)` toggles resource-state visuals.
- `TreeVisual.play_hit(Vector3 local_hit_direction)` applies a short non-destructive trunk/canopy reaction.
- `WoodcuttingImpactFX.emit_at(Vector3 position, Vector3 normal)` emits pooled wood chips/dust and calls an audio hook.

- [ ] **Step 1: Write failing tests** for tree hierarchy, root flare/branch/canopy layers, hit reaction, and pooled impact FX.
- [ ] **Step 2: Run and observe red.**
- [ ] **Step 3: Replace spherical canopy/trunk blockout** with layered stylized geometry or vetted CC0 nature assets, with mobile-friendly material counts.
- [ ] **Step 4: Trigger hit feedback exactly at chop impact beats**, not continuously.
- [ ] **Step 5: Preserve existing resource respawn/gather logic** and verify full tests.
- [ ] **Step 6: Commit** `feat: add polished tree visuals and woodcutting impact fx`.

### Task 4: Starter-zone terrain and environment dressing

**Files:**
- Create: `src/world/starter_region_visuals.gd`
- Create: `src/world/environment_prop_factory.gd`
- Modify: `src/game/main_world.gd`
- Create/Test: `tests/test_starter_region_visuals.gd`

**Interfaces:**
- `StarterRegionVisuals.build(Node3D world_root)` builds visual-only terrain/dressing around existing interaction coordinates.
- Gameplay collision/interactions remain owned by current world/resource/enemy nodes.

- [ ] **Step 1: Write failing structural tests** requiring terrain height variation, path borders, water treatment, at least three vegetation classes, rock clusters, settlement props, and clear visual separation between camp/grove/water.
- [ ] **Step 2: Run tests and observe red.**
- [ ] **Step 3: Replace flat slabs** with shaped terrain meshes/terraces, softened path edges, water shader/material, clustered vegetation, rocks, flowers, roots, fence/settlement props, and intentional composition around the playable area.
- [ ] **Step 4: Use instancing/mesh reuse** for repeated vegetation to keep draw/memory costs suitable for the S25 Ultra.
- [ ] **Step 5: Run Godot import/full tests** and commit `feat: rebuild starter region presentation`.

### Task 5: Lighting, atmosphere, and mobile material pass

**Files:**
- Create: `src/world/world_lighting.gd`
- Modify: `src/game/main_world.gd`
- Modify: `project.godot` only if renderer settings are required
- Create/Test: `tests/test_world_lighting.gd`

**Interfaces:**
- `WorldLighting.apply(Node3D world_root)` owns environment, key light, fill/ambient, fog, sky, shadow tuning, and mobile-safe quality values.

- [ ] **Step 1: Write failing tests** for directional light, environment sky, fog, shadow settings, and exposure/ambient configuration.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement warm key light, cool ambient fill, readable shadows, sky gradient, subtle depth fog, and improved material contrast without expensive desktop-only effects.
- [ ] **Step 4: Validate compatibility renderer/mobile settings and run import/full tests.**
- [ ] **Step 5: Commit** `feat: add polished mobile lighting and atmosphere`.

### Task 6: Visual asset provenance and CC0 integration

**Files:**
- Create: `docs/ASSET_CREDITS.md`
- Create: `assets/vendor/README.md`
- Modify: `.github/workflows/build-aevorwyn-apk-v4.yml` if build-time acquisition is used
- Test: CI asset existence/hash checks

**Interfaces:**
- Every third-party asset entry records title, author, source URL, license, local path, and any modifications.

- [ ] **Step 1: Select only CC0 assets** whose license is explicitly verifiable from the publisher/source.
- [ ] **Step 2: Add deterministic acquisition or vendor the exact needed files**, avoiding entire oversized packs when only a few models are used.
- [ ] **Step 3: Add file/hash checks** so CI fails if expected visual assets are missing or replaced unexpectedly.
- [ ] **Step 4: Document provenance** in `docs/ASSET_CREDITS.md` even where attribution is legally optional.
- [ ] **Step 5: Import in Godot and verify no missing external dependencies.**
- [ ] **Step 6: Commit** `build: vendor verified CC0 visual assets`.

### Task 7: Final regression, signed Android build, and APK handoff

**Files:**
- Modify: `.github/workflows/build-aevorwyn-apk-v4.yml`
- Modify: `aevorwyn-build-trigger-v4.txt`

**Interfaces:**
- Produces signed `Aevorwyn-S25-Ultra.apk` using the existing package ID and signing certificate.

- [ ] **Step 1: Run static verification.** Expected: PASS.
- [ ] **Step 2: Run Godot headless import.** Expected: PASS with no script parse errors.
- [ ] **Step 3: Run the complete test suite.** Expected: all tests PASS, including mobile gesture, axe orientation, player presentation, trees/FX, terrain, and lighting.
- [ ] **Step 4: Export release ARM64 APK.** Expected: non-empty APK.
- [ ] **Step 5: Verify package ID, monotonically increasing version code, and the existing signing certificate digest.**
- [ ] **Step 6: Upload the APK artifact and only then present it as complete.**
