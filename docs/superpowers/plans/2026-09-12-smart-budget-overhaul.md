# BillNest Smart Budget Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace BillNest's simple category budget flow with a smart variable-spending budget engine and richer Budget UI, while limiting the Income screen to the latest 30 days without deleting older transaction history.

**Architecture:** Keep the existing persisted `Budget` type backward-compatible, but move budget matching, period math, suggestions, projections, transaction overrides, and current-period rebalancing into a focused pure Kotlin `BudgetEngine.kt`. Persist user-owned override and adjustment records in `AppData`, synchronize them as their own household record kinds, and expose a dedicated `BudgetPageV2.kt` from the existing finance shell. The existing Plaid transaction model remains authoritative; budgeting only classifies/assigns transactions without mutating bank-owned fields.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Gson, encrypted local store, BillNest household sync, Plaid Production, Gradle 8.11.1, JUnit 4, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-12-budget-overhaul-design.md`

## Global Constraints

- Work on `billnest-apk-build`; do not start over or remove working Plaid Production support.
- Preserve Android package `com.baylee.billnest`.
- Preserve stable signing certificate SHA-256 `0aa471987e2d6b34add2ce811df537fe8dae203a7bb474bb90cb9606e303654c`.
- Budgets cover variable spending only; fixed bills, income, account transfers, savings/reserve movements, and debt payments do not consume budgets.
- Manual transaction budget assignments/exclusions override merchant/category/account rules.
- Rule changes affect the current budget period; current-period budget-to-budget adjustments are persisted separately from recurring base amounts.
- Existing Alpha17 encrypted data and household records must continue to load.
- Income history remains stored; only the Income screen is limited to the inclusive latest 30 calendar days.
- Use TDD: verify RED in GitHub Actions before production behavior is added, then verify GREEN.
- Final release must pass Worker syntax/tests, Android unit tests, stable keystore verification, APK signing verification, and exact-head GitHub Actions before completion is claimed.

---

### Task 1: Income 30-Day Window

**Files:**
- Modify: `app/src/test/java/com/baylee/billnest/model/TransactionClassificationTest.kt`
- Modify: `app/src/main/java/com/baylee/billnest/model/IncomeRules.kt`
- Modify: `app/src/main/java/com/baylee/billnest/IncomePageV2.kt`

**Interfaces:**
- Produces: `recentVisibleIncomeTransactions(transactions: List<FinanceTransaction>, referenceDate: LocalDate = LocalDate.now(), days: Long = 30): List<FinanceTransaction>`.

- [ ] **Step 1: Write failing tests** proving the reference date and date 29 days earlier are included, date 30 days earlier is excluded, future/invalid dates are excluded, and manual classification rules still apply.
- [ ] **Step 2: Push the test-only commit and verify GitHub Actions fails because `recentVisibleIncomeTransactions` does not exist.**
- [ ] **Step 3: Implement the pure helper** by first applying `visibleIncomeTransactions`, parsing `dateIso`, and filtering to `[referenceDate.minusDays(days - 1), referenceDate]`.
- [ ] **Step 4: Route `IncomePageV2` through the helper** so the rows and detected payday suggestions use the same visible 30-day set while saved Payday schedules remain unchanged.
- [ ] **Step 5: Verify the focused tests and full Android test job are green.**

### Task 2: Smart Budget Domain Model and Engine

**Files:**
- Create: `app/src/test/java/com/baylee/billnest/model/BudgetEngineTest.kt`
- Create: `app/src/main/java/com/baylee/billnest/model/BudgetEngine.kt`
- Modify: `app/src/main/java/com/baylee/billnest/model/FinanceModels.kt`
- Modify: `app/src/main/java/com/baylee/billnest/model/Models.kt`

**Interfaces:**
- Produces model enums/types: `BudgetPeriod.PAYCHECK`, `BudgetRolloverMode`, `BudgetPace`, `BudgetOverrideAction`, `BudgetTransactionOverride`, `BudgetAdjustment`, `BudgetPeriodWindow`, `BudgetMatch`, `BudgetSummary`, `VariableSpendingSummary`, and `BudgetSuggestion`.
- Produces pure functions: `budgetPeriodWindow`, `resolveBudgetAssignments`, `calculateBudgetSummary`, `calculateVariableSpendingSummary`, `suggestBudgets`, `assignTransactionToBudget`, and `moveBudgetMoney`.

- [ ] **Step 1: Add failing tests** for weekly, biweekly, monthly, yearly, custom, and paycheck period windows; invalid custom ranges; and missing Payday links.
- [ ] **Step 2: Add failing tests** for variable-spending eligibility exclusions: income, transfers, debt/savings categories, and high-confidence fixed-bill matches.
- [ ] **Step 3: Add failing tests** for matching precedence: manual override, merchant include/exclude, category include/exclude, account include/exclude, legacy category fallback, and one-budget-only assignment.
- [ ] **Step 4: Add failing tests** for current-period overrides and rebalancing, including rejection of non-positive/excess transfer amounts and preservation of total planned allocation.
- [ ] **Step 5: Add failing tests** for spent/remaining/projection/daily allowance/pace, unbudgeted spending, rollover modes, and smart suggestions from recent eligible spending.
- [ ] **Step 6: Push the tests-only commit and verify expected RED failures for missing new types/functions.**
- [ ] **Step 7: Extend `Budget` compatibly** with include/exclude category, merchant, and account lists; optional Payday ID; rollover mode; warning threshold; and pace tracking while retaining all legacy fields.
- [ ] **Step 8: Add override/adjustment lists to `AppData`** with defaults so old call sites remain source compatible.
- [ ] **Step 9: Implement `BudgetEngine.kt`** as pure deterministic logic. Automatic rules never mutate `FinanceTransaction`, manual overrides win, and a transaction resolves to at most one budget.
- [ ] **Step 10: Implement current-period adjustment math and rollover** keyed by the active period start; source allocations cannot become negative.
- [ ] **Step 11: Implement smart suggestions** from recent eligible transactions, exposing 30-day spend, recent monthly average, and a rounded recommendation that users can override.
- [ ] **Step 12: Verify all budget engine tests are green without breaking existing finance model tests.**

### Task 3: Persistence and Household Sync

**Files:**
- Modify: `app/src/test/java/com/baylee/billnest/data/SyncMappingTest.kt`
- Modify: `app/src/main/java/com/baylee/billnest/data/EncryptedStore.kt`
- Modify: `app/src/main/java/com/baylee/billnest/model/SyncModels.kt`
- Modify: `app/src/main/java/com/baylee/billnest/data/BillRepository.kt`
- Modify: `app/src/main/java/com/baylee/billnest/data/HouseholdSyncRepository.kt`
- Modify: `app/src/main/java/com/baylee/billnest/ui/MainViewModel.kt`

**Interfaces:**
- Produces sync kinds `budget_override` and `budget_adjustment`.
- Produces repository/ViewModel operations for transaction budget assignment/exclusion and current-period money movement.

- [ ] **Step 1: Add failing sync tests** proving expanded budgets, `BudgetTransactionOverride`, and `BudgetAdjustment` round-trip through Gson and produce the correct record kinds.
- [ ] **Step 2: Add a legacy-budget JSON test** proving an Alpha17 budget payload with only old fields decodes with safe empty rule lists and reset/default behavior.
- [ ] **Step 3: Verify expected RED before changing sync production code.**
- [ ] **Step 4: Add safe budget JSON normalization** in `SyncMapper.decodeBudget` so missing Alpha17 fields cannot become unsafe null collections.
- [ ] **Step 5: Extend encrypted-store migration** to add new AppData arrays and normalize each stored legacy budget before Gson creates Kotlin objects.
- [ ] **Step 6: Add sync encode/decode/mutation helpers** for overrides and adjustments.
- [ ] **Step 7: Add repository methods** that persist/sync override and adjustment changes and update remote replay handling.
- [ ] **Step 8: Add the new record kinds to legacy snapshot upload and remote change routing** in `HouseholdSyncRepository`.
- [ ] **Step 9: Expose the operations through `MainViewModel`.**
- [ ] **Step 10: Verify sync tests and the full Android unit suite are green.**

### Task 4: Smart Budget Compose Experience

**Files:**
- Create: `app/src/main/java/com/baylee/billnest/BudgetPageV2.kt`
- Modify: `app/src/main/java/com/baylee/billnest/FinanceActivity.kt`

**Interfaces:**
- Consumes: budget engine summaries/suggestions, `MainViewModel.saveBudget`, assignment/exclusion operations, and budget money movement.
- Produces: `BudgetsPageV2(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Build the Budget home** with a Variable Spending summary, planned/spent/remaining/projected amounts, pace state, budget cards, and an Unbudgeted Spending section.
- [ ] **Step 2: Build Add/Edit as a Material 3 sheet-style guided flow** with budget type, optional `Start from my spending`, amount, period, Payday selector, rule inputs, account selection, rollover, warnings, and pace tracking.
- [ ] **Step 3: Build Budget detail** showing remaining/spent, days left, daily allowance, projection, pace, matching transactions, and match reasons.
- [ ] **Step 4: Add per-transaction actions** to assign to another budget or exclude from budgeting for the current period.
- [ ] **Step 5: Add `Move Money`** with source, destination, amount, validation, and resulting allocations through the repository operation.
- [ ] **Step 6: Route the existing `Budgets` destination to `BudgetsPageV2`** without disrupting other finance pages.
- [ ] **Step 7: Run the full build workflow and correct any Compose/compiler regression before release metadata is changed.**

### Task 5: Alpha18 Release and Verification

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/build-billnest-apk.yml`

**Interfaces:**
- Produces: `BillNest-v2.0.0-alpha18-debug.apk` GitHub Actions artifact signed by the existing stable certificate.

- [ ] **Step 1: Set versionCode to `25` and versionName to `2.0.0-alpha18`.**
- [ ] **Step 2: Update the workflow artifact/file names to `BillNest-v2.0.0-alpha18-debug.apk` and `BillNest-v2.0.0-alpha18-debug-apk`; do not change signing checks.**
- [ ] **Step 3: Verify exact-head GitHub Actions passes Worker syntax, Worker regressions, Android unit tests, stable keystore fingerprint, APK build, and APK certificate verification.**
- [ ] **Step 4: Read and apply `verification-before-completion`, then complete the branch workflow.**
- [ ] **Step 5: Download the successful workflow artifact, extract the APK into `/mnt/data`, calculate SHA-256, and provide the direct APK link to the user.**
