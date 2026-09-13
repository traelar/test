# Aevorwyn Original Character System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the vendor ranger player path with an original Aevorwyn modular character creator/runtime, persistent appearance data, original procedural character art, and original movement/woodcutting presentation while preserving mobile input behavior.

**Architecture:** Keep gameplay state authoritative in `GameSession`/services, add a stable `CharacterAppearance` data object, and render it through an Aevorwyn-owned `CharacterAssembler`. The first original art pass is generated entirely inside Godot from Aevorwyn-authored geometry/material parameters and animation transforms, so no vendor model, texture, animation, prop, or UI art is required in the shipped build.

**Tech Stack:** Godot 4.7.2, GDScript, Godot primitive/ArrayMesh geometry, AnimationPlayer/procedural transforms, existing persistence + Android CI.

**Spec:** `docs/superpowers/specs/2026-09-13-aevorwyn-original-content-character-system-design.md`

## Global Constraints

- All shipped Aevorwyn game content must be original and created specifically for Aevorwyn.
- Third-party assets may be temporary references only and must not remain in production/shipped builds.
- Preserve `com.aevorwyn.game` and the current Android signing identity.
- Preserve tap/hold/drag/pinch semantics; camera drag/zoom must never request movement.
- Character visuals never decide gameplay inventory/equipment state; they only reflect authoritative state.
- Stable appearance IDs must survive future content additions.

---

### Task 1: Appearance data + persistence

**Files:**
- Create: `aevorwyn/src/player/character_appearance.gd`
- Modify: `aevorwyn/src/game/game_session.gd`
- Modify: `aevorwyn/src/domain/persistence_service.gd`
- Create: `aevorwyn/tests/test_character_appearance.gd`
- Modify: `aevorwyn/tests/test_runner.gd`

**Interfaces:**
- Produces: `CharacterAppearance.default() -> CharacterAppearance`, `to_dict() -> Dictionary`, `from_dict(Dictionary) -> CharacterAppearance`, `sanitize() -> void`.
- `GameSession.appearance: CharacterAppearance` becomes the single saved appearance state.

- [ ] **Step 1: Write failing serialization tests** asserting round-trip stability for `body_type`, `skin_tone_id`, `face_id`, hair/facial-hair, top/bottom/footwear styles and colors.

```gdscript
var a := CharacterAppearance.default()
a.face_id = &"face_2"
a.hair_style_id = &"hair_short_2"
var b := CharacterAppearance.from_dict(a.to_dict())
_assert(b.face_id == &"face_2", "face id must round-trip")
_assert(b.hair_style_id == &"hair_short_2", "hair id must round-trip")
```

- [ ] **Step 2: Run** `Godot_v4.7.2-stable_linux.x86_64 --headless --path aevorwyn -s tests/test_runner.gd` and verify RED because `CharacterAppearance` is missing.
- [ ] **Step 3: Implement `CharacterAppearance`** with stable `StringName` IDs, `default`, `sanitize`, `to_dict`, and `from_dict`; invalid IDs fall back to known defaults instead of breaking old saves.
- [ ] **Step 4: Wire persistence** under save key `appearance`; missing appearance in older saves receives `CharacterAppearance.default()`.
- [ ] **Step 5: Re-run tests** and verify GREEN.

### Task 2: Original humanoid rig contract + procedural body

**Files:**
- Create: `aevorwyn/src/player/aevorwyn_character_rig.gd`
- Create: `aevorwyn/src/player/character_part_factory.gd`
- Create: `aevorwyn/tests/test_aevorwyn_character_rig.gd`

**Interfaces:**
- Produces named rig nodes: `Root`, `Pelvis`, `Spine`, `Chest`, `Neck`, `Head`, `ShoulderL/R`, `ElbowL/R`, `HandL/R`, `HipL/R`, `KneeL/R`, `FootL/R`, `ToolSocketR`.
- `CharacterPartFactory.build_body(body_type, skin_tone_id, face_id) -> Node3D`.

- [ ] **Step 1: Write failing rig tests** for every required node/socket and for visible face children `EyeL`, `EyeR`, `Nose`, `Mouth`, `EarL`, `EarR`.
- [ ] **Step 2: Run tests** and verify RED.
- [ ] **Step 3: Implement the original body** using smooth capsule/sphere/cylinder geometry and Aevorwyn-authored proportions; no vendor scene/resource paths are allowed. Use separate male/female torso/hip proportions while keeping identical rig node names.
- [ ] **Step 4: Implement three face presets** by parameterizing head width, jaw scale, nose length, eye spacing, brow height, and mouth width. Heads must be actual visible geometry, never a dark hood opening.
- [ ] **Step 5: Run tests** and verify GREEN.

### Task 3: Original hair, clothing, colors, and runtime assembler

**Files:**
- Create: `aevorwyn/src/player/character_catalog.gd`
- Create: `aevorwyn/src/player/character_assembler.gd`
- Create: `aevorwyn/tests/test_character_assembler.gd`

**Interfaces:**
- `CharacterCatalog` exposes stable IDs for 6 skin tones, 3 faces, 5 hairstyles, 3 beard styles, 3 tops, 3 bottoms, 2 footwear styles, and color palettes.
- `CharacterAssembler.apply_appearance(CharacterAppearance) -> void` rebuilds visual slots without replacing gameplay nodes.
- `CharacterAssembler.set_equipped_visual(slot: StringName, item_id: StringName) -> void` only reflects authoritative equipment.

- [ ] **Step 1: Write failing tests** that every catalog ID builds a visible node and applying appearance twice is idempotent.
- [ ] **Step 2: Run tests** and verify RED.
- [ ] **Step 3: Implement original hairstyles** from authored mesh primitives/curves (`short`, `cropped`, `swept`, `tied_back`, `shoulder_length`) and beard variants (`none`, `stubble`, `short_beard`).
- [ ] **Step 4: Implement starter clothing** as original torso/leg/foot layers with material colors; clothing hides/replaces underlying regions only where necessary.
- [ ] **Step 5: Run tests** and verify GREEN.

### Task 4: Character creator UI + live preview

**Files:**
- Create: `aevorwyn/src/ui/character_creator.gd`
- Modify: `aevorwyn/src/ui/hud.gd`
- Modify: `aevorwyn/src/game/main_world.gd`
- Create: `aevorwyn/tests/test_character_creator.gd`

**Interfaces:**
- `CharacterCreator.open(current: CharacterAppearance) -> void`
- Signal: `appearance_confirmed(appearance: CharacterAppearance)`.
- Preview owns a `CharacterAssembler`; rotation changes preview only, never world movement.

- [ ] **Step 1: Write failing UI tests** for all required categories and confirmation persistence.
- [ ] **Step 2: Run tests** and verify RED.
- [ ] **Step 3: Build the contained Aevorwyn creator** with category selectors for body, skin, face, hair, hair color, beard, beard color, top, top color, bottoms, bottoms color, footwear, footwear color; add live 3D preview and left/right preview rotation controls.
- [ ] **Step 4: Confirm writes to `GameSession.appearance` and persistence**, then applies the same appearance to the world player.
- [ ] **Step 5: Run tests** and verify GREEN.

### Task 5: Original movement + woodcutting presentation

**Files:**
- Create: `aevorwyn/src/player/aevorwyn_character_animator.gd`
- Create: `aevorwyn/src/player/aevorwyn_tool_visuals.gd`
- Modify: `aevorwyn/src/game/main_world.gd`
- Create: `aevorwyn/tests/test_aevorwyn_character_animator.gd`

**Interfaces:**
- `set_locomotion(velocity: Vector3) -> void`
- `face_world_target(origin: Vector3, target: Vector3) -> float`
- `play_tree_chop() -> void`
- `AevorwynToolVisuals.build_axe() -> Node3D` returns an original axe attached to `ToolSocketR`.

- [ ] **Step 1: Write failing tests** for forward direction, target-facing yaw, axe socket attachment, and chop phase ordering `ready -> backswing -> strike -> impact -> recover`.
- [ ] **Step 2: Run tests** and verify RED.
- [ ] **Step 3: Implement authored idle/walk** using pelvis/leg/arm transforms with foot phase tied to speed; model forward is `-Z` consistently.
- [ ] **Step 4: Implement original axe** from handle/head geometry and a two-hand-readable chop pose: body faces trunk before animation, backswing remains beside/behind the correct shoulder, strike travels toward trunk, impact callback fires at strike peak, recovery resets cleanly.
- [ ] **Step 5: Run tests** and verify GREEN.

### Task 6: Remove vendor player content from production + preserve mobile input

**Files:**
- Modify: `aevorwyn/src/player/rigged_character_visual.gd` or remove its production use
- Modify: `aevorwyn/src/input/mobile_gesture_interpreter.gd` only if regression tests expose a remaining issue
- Modify: `aevorwyn/tools/static_verify.py`
- Modify: `aevorwyn/tests/test_mobile_gesture_interpreter.gd`

**Interfaces:**
- Production player scene must reference only `res://assets/aevorwyn/` and original runtime-generated geometry for player art.

- [ ] **Step 1: Add failing static/runtime checks** rejecting `assets/vendor/quaternius/character`, `UAL2_Standard`, and vendor axe paths from production player scripts/scenes.
- [ ] **Step 2: Re-run current camera-drag tests** including the 11-pixel rotate regression; verify they still pass before changing input code.
- [ ] **Step 3: Switch player creation to `CharacterAssembler` + `AevorwynCharacterAnimator`; remove vendor player/animation/axe loading from runtime.
- [ ] **Step 4: Run full tests** and verify vendor rejection + mobile input GREEN.

### Task 7: Strict Android release validation

**Files:**
- Create/update: `.github/scripts/aevorwyn-original-character-validate.sh`
- Create/update: `.github/workflows/build-aevorwyn-apk-v8.yml`

- [ ] **Step 1: Reconstruct exact current source and apply character-system patch in CI.**
- [ ] **Step 2: Run static verify, Godot import/compile, full tests, and main-scene boot with fatal log pattern rejection.**
- [ ] **Step 3: Assert no production player dependency references `assets/vendor/`.**
- [ ] **Step 4: Export ARM64 Android APK with package `com.aevorwyn.game`.**
- [ ] **Step 5: Verify certificate SHA-256 remains `0aa471987e2d6b34add2ce811df537fe8dae203a7bb474bb90cb9606e303654c`.**
- [ ] **Step 6: Upload the APK artifact only after all gates pass.**

## Self-Review

- Spec coverage: appearance persistence, modular original bodies/faces/hair/clothes, creator UI, equipment sockets, original idle/walk/chop, target-facing, mobile regressions, vendor removal, Android/signing checks are each mapped to a task.
- Placeholder scan: no TBD/TODO/future implementation placeholders are used for this milestone.
- Type consistency: all appearance flows use `CharacterAppearance`; rendering uses `CharacterAssembler`; animation uses `AevorwynCharacterAnimator`; authoritative state remains in `GameSession`/inventory/equipment services.
