# Aevorwyn Inventory & Skills UI Overhaul Amendment

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real compact landscape player UI with authoritative Inventory and Skills tabs to the approved Aevorwyn visual-quality overhaul.

**Architecture:** Add a focused `PlayerPanel` UI layer that reads snapshots from `GameSession` and sends all mutations back through session/domain methods. Inventory and Skills are independent tab views hosted by the same shell so later Equipment/Quests/Map tabs can be added without rewriting input routing.

**Tech Stack:** Godot 4.7.2, GDScript, Android ARM64, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-13-aevorwyn-visual-quality-overhaul.md`

## Global Constraints
- Package ID remains `com.aevorwyn.game` and the stable signing identity remains unchanged.
- Open UI must consume its touches so taps do not move the world underneath.
- Inventory and skills data are authoritative gameplay state, never duplicate UI state.
- No RuneScape/Jagex art, icons, UI assets, or copied layouts.

---

### Task 1: Player panel shell and input isolation

**Files:**
- Create: `src/ui/player_panel.gd`
- Modify: `src/ui/hud.gd`
- Modify: `src/game/main_world.gd`
- Test: `tests/test_player_panel.gd`

**Interfaces:**
- Produces: `PlayerPanel.open_tab(tab_id: String)`, `PlayerPanel.close()`, `PlayerPanel.is_open() -> bool`.
- Emits: `item_action_requested(slot_index: int, action_id: String)` and `skill_selected(skill_id: StringName)`.

- [ ] **Step 1: Write a failing test** that creates `PlayerPanel`, calls `open_tab("inventory")`, verifies it reports open, switches to `skills`, closes, and verifies `is_open()` is false.

```gdscript
func test_panel_open_switch_close() -> void:
    var panel := PlayerPanel.new()
    panel.open_tab("inventory")
    assert_true(panel.is_open(), "panel should open")
    assert_eq(panel.current_tab(), "inventory", "inventory should be active")
    panel.open_tab("skills")
    assert_eq(panel.current_tab(), "skills", "skills should be active")
    panel.close()
    assert_false(panel.is_open(), "panel should close")
```

- [ ] **Step 2: Add an input-isolation test** requiring panel controls to use `MOUSE_FILTER_STOP` so touches do not fall through into `MainWorld` movement handling.
- [ ] **Step 3: Run the Godot test runner** and confirm failure because `PlayerPanel` does not exist.
- [ ] **Step 4: Implement the compact right-side shell** with Inventory/Skills tab buttons, close button, touch-safe sizing, and world-input blocking.
- [ ] **Step 5: Run tests** and confirm the panel shell tests pass.

### Task 2: Authoritative Inventory tab

**Files:**
- Create: `src/ui/inventory_tab.gd`
- Modify: `src/ui/player_panel.gd`
- Modify: `src/game/game_session.gd`
- Modify: `src/content/content_db.gd`
- Test: `tests/test_inventory_tab.gd`

**Interfaces:**
- `InventoryTab.bind_session(session: GameSession)` reads `session.player_state.inventory` and `ContentDB` definitions.
- `GameSession.inventory_action(slot_index: int, action_id: String) -> Dictionary` validates and executes supported actions.

- [ ] **Step 1: Write failing rendering tests** that feed a 24-slot inventory snapshot containing `oak_log`, `training_blade`, and `small_ration`; assert occupied slots, quantities, and empty slots render correctly.
- [ ] **Step 2: Write failing action tests** requiring `training_blade` to expose Wield/Examine/Drop and `small_ration` to expose Eat/Examine/Drop while `oak_log` exposes Use/Examine/Drop.

```gdscript
func test_context_actions_come_from_item_capabilities() -> void:
    assert_array_contains(InventoryTab.actions_for_item({"id":"training_blade"}), "wield")
    assert_array_contains(InventoryTab.actions_for_item({"id":"small_ration"}), "eat")
    assert_array_contains(InventoryTab.actions_for_item({"id":"oak_log"}), "use")
```

- [ ] **Step 3: Run tests** and observe red on missing tab/session APIs.
- [ ] **Step 4: Implement the fixed-slot grid** with original Aevorwyn icon treatments, stack counts, selection state, and long-hold item context menu.
- [ ] **Step 5: Implement `GameSession.inventory_action`** so UI actions mutate through authoritative services and emit `state_changed`.
- [ ] **Step 6: Run focused and full tests** and confirm gathering/loot/save inventory regressions stay green.

### Task 3: Skills tab and detail view

**Files:**
- Create: `src/ui/skills_tab.gd`
- Modify: `src/ui/player_panel.gd`
- Modify: `src/domain/skill_service.gd` only if a next-level helper is missing
- Test: `tests/test_skills_tab.gd`

**Interfaces:**
- `SkillsTab.bind_session(session: GameSession)` reads the canonical skill dictionary.
- `SkillsTab.skill_detail(skill_id: StringName) -> Dictionary` returns level, XP, next-level XP, remaining XP, and progress ratio.

- [ ] **Step 1: Write a failing test** requiring exactly the 14 approved skills and correct Total Level calculation.
- [ ] **Step 2: Write a failing test** for Woodcutting detail at level 1/known XP, verifying next-level XP and normalized progress are derived from `SkillService`.

```gdscript
func test_total_level_and_skill_detail() -> void:
    var tab := SkillsTab.new()
    tab.set_skills(_all_level_one_skills())
    assert_eq(tab.total_level(), 14, "all fourteen level-one skills total 14")
    var detail := tab.skill_detail(&"woodcutting")
    assert_true(int(detail.next_level_xp) > int(detail.xp), "next level XP must be ahead")
```

- [ ] **Step 3: Run tests** and observe red because the Skills tab is absent.
- [ ] **Step 4: Implement skill tiles** with original simple iconography, level, XP/progress treatment, and Total Level.
- [ ] **Step 5: Implement in-panel skill detail view** with current XP, XP to next level, progress bar, and training/unlock copy sourced from Aevorwyn content metadata.
- [ ] **Step 6: Run focused and full tests** and verify XP gains refresh the open Skills tab immediately.

### Task 4: Integrate with the visual-quality Android build

**Files:**
- Modify: `.github/workflows/build-aevorwyn-apk-v4.yml`
- Modify: `tests/test_runner.gd`
- Modify: `aevorwyn-build-trigger-v4.txt`

**Interfaces:**
- Produces the same signed `Aevorwyn-S25-Ultra.apk` package as the visual-quality plan.

- [ ] **Step 1: Add new UI tests to `tests/test_runner.gd`.**
- [ ] **Step 2: Run static verification and Godot headless import.** Expected: PASS.
- [ ] **Step 3: Run the complete test suite including mobile gesture, animation, Inventory, and Skills tests.** Expected: PASS.
- [ ] **Step 4: Export ARM64 release APK with monotonically increased version code/name.**
- [ ] **Step 5: Verify the existing signing certificate digest and non-empty artifact.**
- [ ] **Step 6: Upload the artifact and only then present it as ready to install over the previous build.**
