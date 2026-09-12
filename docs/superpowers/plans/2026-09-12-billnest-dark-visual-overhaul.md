# BillNest Dark Visual Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace BillNest's stock Material appearance with a cohesive premium dark-only finance UI while preserving all finance, Plaid, household, persistence, navigation, and signing behavior.

**Architecture:** Centralize the visual system in `ui/theme/Theme.kt` so every Material component inherits dark colors, typography, shapes, dialog/menu surfaces, and semantic accents. Then polish the main shell and high-value finance screens using existing data/callbacks without changing repositories/models/APIs. Finally restyle auth/household supporting surfaces and publish alpha14 through the existing signed GitHub Actions build.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android 36, existing Plaid Link SDK, GitHub Actions/Gradle.

**Spec:** `docs/superpowers/specs/2026-09-12-billnest-dark-visual-overhaul-design.md`

## Global Constraints

- Dark-only UI; no white/light app surfaces.
- Preserve `com.baylee.billnest`, Plaid Production, Cloudflare backend, encrypted/persisted data, account/debt behavior, household behavior, and Android back navigation.
- No fake financial values or user-specific hardcoding.
- Keep existing tests and signing certificate checks.
- Build as versionCode 21 / `2.0.0-alpha14` with matching artifact name.

---

### Task 1: Dark design system foundation

**Files:**
- Modify: `app/src/main/java/com/baylee/billnest/ui/theme/Theme.kt`

**Interfaces:**
- Produces: `BillNestTheme(content)` with an explicit dark-only `ColorScheme`, typography, and shapes consumed by every existing Compose screen.

- [ ] Replace the system light/dark switch with one explicit dark color scheme using near-black background, graphite surfaces, teal primary, green tertiary, amber warning-compatible secondary roles, and coral error.
- [ ] Define typography with stronger headline/title weights and readable body/label contrast.
- [ ] Define consistent rounded shapes for small/medium/large Material components.
- [ ] Ensure `background`, `surface`, `surfaceVariant`, containers, outline, scrim, and inverse roles are all dark so dialogs, menus, drawers, cards, text fields, and scaffolds cannot fall back to white.
- [ ] Preserve the public `BillNestTheme(content)` signature.

### Task 2: Main app shell and primary finance hierarchy

**Files:**
- Modify: `app/src/main/java/com/baylee/billnest/MainActivity.kt`

**Interfaces:**
- Consumes: existing `AppData`, `MainViewModel`, `BankConnectionIssue`, dialogs and navigation callbacks.
- Produces: same screen functions/signatures with polished presentation only.

- [ ] Restyle drawer branding, selected navigation state, top app bar, scaffold background, and quick-add actions without changing destination history/back behavior.
- [ ] Redesign Dashboard summary hierarchy with stronger total/available money emphasis and semantic cards for spending, savings, retirement, reserves, upcoming bills, and overdue state using only existing real values.
- [ ] Polish Bills, Accounts, Calendar, Income, and Settings cards/headers/metadata/action hierarchy while preserving every action and data path.
- [ ] Keep account list filtering behavior intact and leave Plaid credit accounts available internally for Debt sync.
- [ ] Update Settings display version to `BillNest v2.0.0-alpha14`.

### Task 3: Finance workspace polish

**Files:**
- Modify: `app/src/main/java/com/baylee/billnest/V2FinanceScreens.kt`

**Interfaces:**
- Consumes: current finance models and `MainViewModel` methods.
- Produces: same public screen composables (`TransactionsPage`, `BudgetsPage`, `DebtPage`, `SavingsGoalsPage`, `ReservedFundsPage`, `SubscriptionsPage`).

- [ ] Make shared finance list/row presentation more premium and consistent.
- [ ] Add clear transaction type hierarchy, budget progress, savings-goal progress, reserve hierarchy, and subscription review state using existing values only.
- [ ] Strengthen Debt with total debt summary, utilization progress, readable APR/minimum/due-date metadata, and separate Snowball/Avalanche comparison surfaces.
- [ ] Keep all existing add/edit/delete/link/refund/save behavior and dialog data fields unchanged.

### Task 4: Auth, household, and supporting screens

**Files:**
- Modify: `app/src/main/java/com/baylee/billnest/AuthActivity.kt`
- Modify: `app/src/main/java/com/baylee/billnest/ui/auth/AuthGate.kt`
- Modify: `app/src/main/java/com/baylee/billnest/ui/household/HouseholdSettings.kt`
- Modify: `app/src/main/java/com/baylee/billnest/V2ComingSoonPage.kt`

**Interfaces:**
- Consume all existing view models/APIs/callbacks unchanged.

- [ ] Give auth a premium dark card hierarchy and clearer selected mode treatment without changing validation/login/bootstrap/join behavior.
- [ ] Give signed-in hub, household status/invite/bank/member cards, and empty/supporting screens the same visual language.
- [ ] Preserve owner-only actions, sync behavior, member removal, bank disconnect confirmation, and navigation.

### Task 5: Versioning and CI artifact

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/build-billnest-apk.yml`

**Interfaces:**
- Produces: `BillNest-v2.0.0-alpha14-debug.apk` signed with the existing stable BillNest certificate.

- [ ] Set versionCode to 21 and versionName to `2.0.0-alpha14`.
- [ ] Rename CI output and artifact to alpha14 without changing signing fingerprints or build/test steps.
- [ ] Push all UI/version changes to `billnest-apk-build`.
- [ ] Verify Cloudflare Worker syntax/tests, Android unit tests, stable keystore fingerprint, APK assembly, APK certificate digest, rename, and artifact upload all pass in the new GitHub Actions run.
- [ ] Download the finished artifact and provide it to the user only after the run is green.
