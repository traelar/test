# Aevorwyn Original Equipment Visual Amendment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the verified original Aevorwyn character assembler with stable original equipment visual slots so future Aevorwyn-made armor, capes, shields, weapons, and tools can be equipped without redesigning the player rig.

**Architecture:** `GameSession` remains authoritative for equipped item IDs. `CharacterAssembler.apply_equipment_visuals()` mirrors those IDs into eight stable visual slots and applies original procedural Aevorwyn visual parameters plus base-layer hide/restore behavior. The system contains no vendor asset dependencies.

**Tech Stack:** Godot 4.7.2, GDScript, runtime-generated Godot geometry/materials, existing inventory/equipment state and Android CI.

**Spec:** `docs/superpowers/specs/2026-09-13-aevorwyn-original-equipment-visual-amendment.md`

## Global Constraints
- Stable visual slots: `head`, `chest`, `hands`, `legs`, `feet`, `cape`, `main_hand`, `off_hand`.
- Shipped equipment visuals are original Aevorwyn content.
- Visuals mirror authoritative `GameSession` equipment; they do not independently mutate equipment state.
- Removing equipment restores the appropriate character-creation appearance layer.
- Preserve package `com.aevorwyn.game` and the current Android signing certificate.

### Task 1: Armor visual-slot contract

**Files:**
- Create: `src/player/aevorwyn_equipment_visuals.gd`
- Modify: `src/player/character_assembler.gd`
- Test: `tests/test_equipment_visual_slots.gd`

**Interfaces:**
- `equipment_visual_slots() -> Array[StringName]`
- `set_equipped_visual(slot: StringName, item_id: StringName) -> void`
- `clear_equipped_visual(slot: StringName) -> void`
- `apply_equipment_visuals(equipment: Dictionary) -> void`

- [x] Write RED regressions for the exact eight-slot list, helmet/hair coverage, chest/base-clothing coverage, cape, off-hand, and removal restoration.
- [x] Verify RED against the previously GREEN original-character base.
- [x] Implement original procedural equipment visual builders and hide/restore behavior.
- [x] Route world equipment synchronization through `apply_equipment_visuals()`.
- [x] Run strict Godot import, full regressions, vendor-reference rejection, and main-scene boot to GREEN.

### Task 2: Android integration

**Files:**
- Create: `.github/workflows/build-aevorwyn-apk-v8.yml`

- [x] Reconstruct the verified original-character + armor source deterministically.
- [x] Add a production gate rejecting vendor asset references/download requirements.
- [ ] Run strict import, full tests, and main-scene boot in the Android pipeline.
- [ ] Export the signed ARM64 APK.
- [ ] Verify the preserved certificate SHA-256 and upload the APK artifact.
