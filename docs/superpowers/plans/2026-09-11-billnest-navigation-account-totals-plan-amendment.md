# BillNest Navigation + Account Totals Plan Amendment

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the approved left navigation drawer, persistent household account ordering, account roles/inclusion flags, and separate Total Money / Spending Money / Savings / Available After Bills calculations to the v2 foundation delivery.

**Architecture:** Extend the shared Account model and household sync payload with presentation/calculation metadata. Keep financial calculations in a small pure Kotlin helper so they can be tested independently. Replace the bottom navigation in the Compose app shell with a Material3 drawer using full labels.

**Tech Stack:** Kotlin 2.3.10, Jetpack Compose Material3, existing encrypted storage/sync foundation, Cloudflare Worker/D1 shared record sync, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-11-billnest-navigation-account-totals-amendment.md`

## Global Constraints

- Preserve the existing Plaid production connection and backend URL.
- Do not hide excluded accounts; exclusion only changes financial calculations.
- Savings defaults to Total Money=true and Spending Money=false.
- Account order and inclusion metadata must sync across the household.
- The drawer uses full labels and replaces the bottom navigation.
- No dashboard widget reordering is introduced.

---

### Task A1: Account metadata and migration

**Files:**
- Modify: `app/src/main/java/com/baylee/billnest/model/Models.kt`
- Modify: `app/src/main/java/com/baylee/billnest/data/BillRepository.kt`
- Test: `app/src/test/java/com/baylee/billnest/model/AccountPresentationTest.kt`

**Interfaces:**
- Produces enum `AccountRole { SPENDING, SAVINGS, CREDIT_DEBT, OTHER }`.
- Extends `Account` with `role`, `includeInTotalMoney`, `includeInSpendingMoney`, `displayOrder`, `ownerLabel`.
- Produces `BillRepository.moveAccount(accountId, direction)` and preserves existing IDs/names during Plaid refresh.

- [ ] Write failing tests showing checking/cash default to spendable, savings is Total Money-only, and display order is deterministic.
- [ ] Run `gradle :app:testDebugUnitTest --tests '*AccountPresentationTest*'` and verify failure.
- [ ] Add model fields/default helpers and legacy migration mapping.
- [ ] Add account move/reindex logic and preserve synced Plaid presentation fields.
- [ ] Re-run the focused test and full Android unit test suite.

### Task A2: Financial totals helper

**Files:**
- Create: `app/src/main/java/com/baylee/billnest/domain/FinancialTotals.kt`
- Test: `app/src/test/java/com/baylee/billnest/domain/FinancialTotalsTest.kt`

**Interfaces:**
- Produces `data class FinancialTotals(totalMoney: Double, spendingMoney: Double, savings: Double)`.
- Produces `calculateFinancialTotals(accounts: List<Account>): FinancialTotals`.
- Produces `availableAfterBills(spendingMoney: Double, upcomingBills: Double): Double`.

- [ ] Write failing tests for savings inclusion/exclusion, credit exclusion, manual cash inclusion, and available-after-bills.
- [ ] Run the focused tests and verify failure.
- [ ] Implement the pure helper with no Android dependencies.
- [ ] Re-run focused and full unit tests.

### Task A3: Drawer app shell and Accounts controls

**Files:**
- Create: `app/src/main/java/com/baylee/billnest/ui/navigation/BillNestDestination.kt`
- Create: `app/src/main/java/com/baylee/billnest/ui/navigation/BillNestAppShell.kt`
- Modify: `app/src/main/java/com/baylee/billnest/MainActivity.kt`
- Modify/create focused account/dashboard screen files as part of the v2 UI split.

**Interfaces:**
- Drawer destinations: Dashboard, Accounts, Transactions, Bills, Budgets, Debt, Goals, Income, Calendar, Settings.
- Accounts UI exposes role, Total Money inclusion, Spending Money inclusion, owner label, Move Up, Move Down.

- [ ] Add a pure navigation-label test asserting full destination labels.
- [ ] Verify it fails before `BillNestDestination` exists.
- [ ] Implement the destination enum and Material3 drawer shell.
- [ ] Remove the v2 bottom navigation path.
- [ ] Wire Accounts controls to repository/view-model updates.
- [ ] Wire Dashboard to `FinancialTotals` and label Total Money, Spending Money, Savings, Available After Upcoming Bills distinctly.
- [ ] Run Android unit tests and build debug APK.

### Task A4: Household sync payload compatibility

**Files:**
- Modify: sync DTO/serialization files from the foundation plan.
- Test: Worker sync test and Android sync mapping test.

**Interfaces:**
- Account payload includes `role`, `includeInTotalMoney`, `includeInSpendingMoney`, `displayOrder`, `ownerLabel`.
- Existing clients without these fields receive deterministic defaults on Android migration.

- [ ] Write failing round-trip tests.
- [ ] Verify failure.
- [ ] Implement payload mapping and server pass-through validation.
- [ ] Re-run Worker and Android tests.

## Completion Verification

Run in CI:

```bash
node --check cloudflare-worker/src/index.js
node --test cloudflare-worker/test/*.test.js
gradle :app:testDebugUnitTest --stacktrace
gradle :app:assembleDebug --stacktrace
```

Manual verification on the APK must confirm: full drawer labels, no bottom nav, savings excluded from Spending Money by default, account order controls persist, and the household sync does not alter the working Plaid connection.
