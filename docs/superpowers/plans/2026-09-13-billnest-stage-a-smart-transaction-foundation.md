# BillNest Stage A Smart Transaction Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the first stage of the approved BillNest smart-finance expansion as Alpha30: a Financial Review Inbox, Merchant Manager, deterministic multi-condition transaction rules with preview, and household-synced merchant/rule/review metadata without breaking Plaid Production, existing transaction overrides, category behavior, household sync, or update-in-place signing.

**Architecture:** Keep Plaid-owned transaction fields authoritative and layer BillNest-owned merchant/display/classification metadata on top. Add pure Kotlin engines for merchant identity, smart rules, and review generation; persist only user-owned metadata/resolutions in `AppData`; reuse the existing generic household `finance_records` sync path for three new record kinds; then wire focused Compose screens into the existing `FinanceActivity` shell. Legacy `TransactionRule` records remain supported and can be converted instead of destructively migrated.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Gson, encrypted local `AppData`, existing SQLite sync outbox, Cloudflare Worker + D1 generic finance-record sync, Node test runner, Gradle/JUnit, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-13-billnest-smart-finance-expansion-design.md`

## Global Constraints

- Work on `billnest-apk-build`; do not start over or replace the current architecture.
- Preserve Android package `com.baylee.billnest`.
- Preserve Plaid Production behavior and current Cloudflare Worker/D1 backend.
- Do not mutate raw Plaid merchant/name fields to achieve a BillNest display rename. New renames must use BillNest-owned metadata.
- Explicit per-transaction user classification/category overrides remain higher priority than automatic merchant profiles or smart rules.
- Preserve Alpha29 shared category behavior: every new category selector must reuse `CategoryPickerField` / `canonicalCategoryName`.
- Keep legacy `TransactionRule` support for existing users; do not delete old rules during Stage A.
- Do not add household privacy yet; that is Stage E. Stage A records are household-scoped like the existing finance metadata.
- Do not implement recurring price-change/missed-renewal logic yet; those belong to Stage B. Stage A may surface the current recurring suggestions as `POSSIBLE_RECURRING`.
- Keep the dark-only visual system and phone-safe action layouts.
- Every code change follows TDD: write the failing test, run it and confirm the intended failure, implement minimally, rerun to green, then commit.
- Alpha30 release target: `versionCode 37`, `versionName "2.0.0-alpha30"`.
- Do not call Alpha30 complete until Worker syntax/tests, Android unit tests, signing pre-check, APK build, APK certificate verification, artifact upload, and Live Auth all pass on the exact release HEAD.

---

## File Structure

### New model/engine files

- `app/src/main/java/com/baylee/billnest/model/SmartTransactionModels.kt` — Stage A data contracts.
- `app/src/main/java/com/baylee/billnest/model/MerchantEngine.kt` — merchant normalization, profile resolution, profile application.
- `app/src/main/java/com/baylee/billnest/model/SmartTransactionRuleEngine.kt` — smart matching, priority, preview, application.
- `app/src/main/java/com/baylee/billnest/model/ReviewInboxEngine.kt` — review detection, fingerprints, suppression.

### New UI files

- `app/src/main/java/com/baylee/billnest/ReviewInboxPage.kt`
- `app/src/main/java/com/baylee/billnest/MerchantManagerPage.kt`
- `app/src/main/java/com/baylee/billnest/SmartRuleEditorDialog.kt`

### New tests

- `app/src/test/java/com/baylee/billnest/model/SmartTransactionModelsTest.kt`
- `app/src/test/java/com/baylee/billnest/model/MerchantEngineTest.kt`
- `app/src/test/java/com/baylee/billnest/model/SmartTransactionRuleEngineTest.kt`
- `app/src/test/java/com/baylee/billnest/model/ReviewInboxEngineTest.kt`
- `app/src/test/java/com/baylee/billnest/model/SmartTransactionSyncMapperTest.kt`
- `cloudflare-worker/test/smart-transaction-sync-kinds.test.js`

### Existing files to modify

- `app/src/main/java/com/baylee/billnest/model/FinanceModels.kt`
- `app/src/main/java/com/baylee/billnest/model/Models.kt`
- `app/src/main/java/com/baylee/billnest/model/TransactionMerge.kt`
- `app/src/main/java/com/baylee/billnest/model/TransactionRules.kt`
- `app/src/main/java/com/baylee/billnest/model/BillCategoryRules.kt`
- `app/src/main/java/com/baylee/billnest/model/SyncModels.kt`
- `app/src/main/java/com/baylee/billnest/data/EncryptedStore.kt`
- `app/src/main/java/com/baylee/billnest/data/BillRepository.kt`
- `app/src/main/java/com/baylee/billnest/data/HouseholdSyncRepository.kt`
- `app/src/main/java/com/baylee/billnest/ui/MainViewModel.kt`
- `app/src/main/java/com/baylee/billnest/Alpha19FinanceScreens.kt`
- `app/src/main/java/com/baylee/billnest/InsightsPageV5.kt`
- `app/src/main/java/com/baylee/billnest/DashboardV4.kt`
- `app/src/main/java/com/baylee/billnest/FinanceActivity.kt`
- `cloudflare-worker/src/sync.js`
- `app/build.gradle.kts`
- `.github/workflows/build-billnest-apk.yml`

---

## Task 1 — Add Stage A models and preserve BillNest-owned transaction metadata across Plaid refresh

**Files:**
- Create `app/src/main/java/com/baylee/billnest/model/SmartTransactionModels.kt`
- Modify `app/src/main/java/com/baylee/billnest/model/FinanceModels.kt`
- Modify `app/src/main/java/com/baylee/billnest/model/Models.kt`
- Modify `app/src/main/java/com/baylee/billnest/model/TransactionMerge.kt`
- Create `app/src/test/java/com/baylee/billnest/model/SmartTransactionModelsTest.kt`

- [ ] **Step 1: Write the failing metadata-preservation test.**

Add a test proving a fresh Plaid row refreshes the raw bank name/amount/date while keeping BillNest display/merchant metadata and existing manual classification metadata:

```kotlin
@Test
fun plaidRefreshPreservesBillNestOwnedMerchantMetadata() {
    val saved = FinanceTransaction(
        id = "tx-1",
        name = "WM SUPERCENTER 1234",
        amount = 42.0,
        dateIso = "2026-09-12",
        category = "Groceries",
        source = TransactionSource.PLAID,
        displayNameOverride = "Walmart",
        merchantProfileId = "merchant-walmart",
        appliedSmartRuleId = "rule-walmart"
    )
    val fresh = saved.copy(
        name = "WAL-MART #1234",
        amount = 44.0,
        displayNameOverride = null,
        merchantProfileId = null,
        appliedSmartRuleId = null
    )

    val merged = mergePlaidTransactions(listOf(saved), listOf(fresh)).single()

    assertEquals("WAL-MART #1234", merged.name)
    assertEquals(44.0, merged.amount, 0.001)
    assertEquals("Walmart", merged.displayNameOverride)
    assertEquals("merchant-walmart", merged.merchantProfileId)
    assertEquals("rule-walmart", merged.appliedSmartRuleId)
}
```

Run:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionModelsTest --stacktrace
```

Expected: RED because the new metadata fields do not exist yet.

- [ ] **Step 2: Add focused Stage A contracts.**

Create `SmartTransactionModels.kt` with these shapes:

```kotlin
package com.baylee.billnest.model

import java.util.UUID

enum class TransactionDirection { INFLOW, OUTFLOW }
enum class MerchantConfirmation { AUTO, USER_CONFIRMED }
enum class ReviewDisposition { RESOLVED, DISMISSED }
enum class ReviewType {
    UNCATEGORIZED,
    UNKNOWN_MERCHANT,
    POSSIBLE_TRANSFER,
    POSSIBLE_INCOME,
    POSSIBLE_RECURRING,
    POTENTIAL_DUPLICATE,
    CATEGORY_CONFLICT,
    UNUSUAL_AMOUNT
}

data class MerchantProfile(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val preferredCategory: String? = null,
    val defaultClassification: TransactionClassification? = null,
    val confirmation: MerchantConfirmation = MerchantConfirmation.USER_CONFIRMED,
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class SmartRuleMatch(
    val rawNameContains: String? = null,
    val merchantProfileId: String? = null,
    val accountId: String? = null,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val category: String? = null,
    val classification: TransactionClassification? = null,
    val direction: TransactionDirection? = null
)

data class SmartRuleAction(
    val displayName: String? = null,
    val category: String? = null,
    val classification: TransactionClassification? = null,
    val excludeFromSpending: Boolean? = null,
    val recurringStatus: SubscriptionStatus? = null
)

data class SmartTransactionRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val match: SmartRuleMatch,
    val action: SmartRuleAction,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class RulePreviewRow(
    val transactionId: String,
    val before: FinanceTransaction,
    val after: FinanceTransaction,
    val winningRuleId: String
)

data class SmartRuleBatchResult(
    val transactions: List<FinanceTransaction>,
    val subscriptionPreferences: List<SubscriptionPreference>
)

data class ReviewResolution(
    val fingerprint: String,
    val disposition: ReviewDisposition,
    val resolvedAtEpochMs: Long = System.currentTimeMillis()
)

data class ReviewItem(
    val fingerprint: String,
    val type: ReviewType,
    val transactionIds: List<String>,
    val title: String,
    val explanation: String,
    val confidence: Double,
    val suggestedCategory: String? = null,
    val suggestedClassification: TransactionClassification? = null,
    val suggestedMerchantName: String? = null
)
```

- [ ] **Step 3: Add BillNest-owned transaction metadata.**

Append defaulted fields to `FinanceTransaction` so old encrypted JSON remains source-compatible:

```kotlin
val displayNameOverride: String? = null,
val merchantProfileId: String? = null,
val appliedSmartRuleId: String? = null
```

Add:

```kotlin
fun FinanceTransaction.effectiveDisplayName(): String =
    displayNameOverride?.trim()?.takeIf { it.isNotBlank() } ?: name
```

- [ ] **Step 4: Add Stage A collections to `AppData`.**

```kotlin
val merchantProfiles: List<MerchantProfile> = emptyList(),
val smartTransactionRules: List<SmartTransactionRule> = emptyList(),
val reviewResolutions: List<ReviewResolution> = emptyList(),
```

- [ ] **Step 5: Update Plaid merge preservation.**

In `mergePlaidTransactions`, always carry BillNest-owned metadata from the saved row when IDs match, independently of `userClassificationOverride`. Classification/splits remain preserved only under the existing explicit-user-override rule.

Core merge shape:

```kotlin
val base = fresh.copy(
    displayNameOverride = saved?.displayNameOverride,
    merchantProfileId = saved?.merchantProfileId,
    appliedSmartRuleId = saved?.appliedSmartRuleId
)
if (saved?.userClassificationOverride == true) {
    base.copy(
        category = saved.category,
        transfer = saved.transfer,
        income = saved.income,
        transferFromAccountId = saved.transferFromAccountId,
        transferToAccountId = saved.transferToAccountId,
        userClassificationOverride = true,
        splits = saved.splits
    )
} else base
```

- [ ] **Step 6: Rerun the focused test and existing transaction tests.**

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionModelsTest --tests com.baylee.billnest.model.FinanceModelsTest --stacktrace
```

Expected: GREEN.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/model/SmartTransactionModels.kt app/src/main/java/com/baylee/billnest/model/FinanceModels.kt app/src/main/java/com/baylee/billnest/model/Models.kt app/src/main/java/com/baylee/billnest/model/TransactionMerge.kt app/src/test/java/com/baylee/billnest/model/SmartTransactionModelsTest.kt
git commit -m "feat: add smart transaction metadata foundation"
```

---

## Task 2 — Build the Merchant Manager engine

**Files:**
- Create `app/src/main/java/com/baylee/billnest/model/MerchantEngine.kt`
- Create `app/src/test/java/com/baylee/billnest/model/MerchantEngineTest.kt`
- Modify `app/src/main/java/com/baylee/billnest/model/BillCategoryRules.kt`

- [ ] **Step 1: Write failing alias/canonical-name tests.**

Cover at minimum:
- `WM SUPERCENTER #1234` and `WAL-MART 1234` can resolve to one user-confirmed Walmart profile when aliases contain `WM SUPERCENTER` / `WAL MART`.
- longest matching alias wins when two profiles could match;
- a profile preferred category uses `canonicalCategoryName` and does not overwrite an explicit transaction user classification override;
- `effectiveDisplayName()` returns the profile display name after application.

Representative test:

```kotlin
@Test
fun profileAliasResolvesUglyBankMerchantToCanonicalMerchant() {
    val profile = MerchantProfile(
        id = "walmart",
        displayName = "Walmart",
        aliases = listOf("WM SUPERCENTER", "WAL MART"),
        preferredCategory = "Groceries"
    )
    val tx = FinanceTransaction(
        id = "tx",
        name = "WM SUPERCENTER #1234",
        amount = 51.25,
        dateIso = "2026-09-13"
    )

    val applied = applyMerchantProfiles(listOf(tx), listOf(profile), AppData()).single()

    assertEquals("WM SUPERCENTER #1234", applied.name)
    assertEquals("Walmart", applied.displayNameOverride)
    assertEquals("walmart", applied.merchantProfileId)
    assertEquals("Groceries", applied.category)
}
```

Run and confirm RED:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.MerchantEngineTest --stacktrace
```

- [ ] **Step 2: Implement deterministic merchant normalization/resolution.**

Expose:

```kotlin
fun merchantIdentityKey(value: String): String
fun resolveMerchantProfile(transaction: FinanceTransaction, profiles: List<MerchantProfile>): MerchantProfile?
fun applyMerchantProfile(transaction: FinanceTransaction, profile: MerchantProfile, data: AppData): FinanceTransaction
fun applyMerchantProfiles(transactions: List<FinanceTransaction>, profiles: List<MerchantProfile>, data: AppData): List<FinanceTransaction>
```

Normalization rules:
1. lowercase;
2. replace punctuation with spaces;
3. collapse whitespace;
4. remove standalone numeric store/terminal fragments from the comparison key;
5. do not mutate the raw transaction name.

Profile selection order: longest normalized matching alias, then newest `updatedAtEpochMs`, then lexical profile ID as final deterministic tie-breaker.

- [ ] **Step 3: Extend category catalog with smart profiles/rules.**

`categoryOptions(data)` must include:

```kotlin
data.merchantProfiles.forEach { add(it.preferredCategory) }
data.smartTransactionRules.forEach { add(it.action.category) }
```

This keeps Alpha29 category reuse working in new Stage A screens.

- [ ] **Step 4: Run tests.**

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.MerchantEngineTest --tests com.baylee.billnest.model.CategoryCatalogTest --stacktrace
```

Expected: GREEN.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/model/MerchantEngine.kt app/src/main/java/com/baylee/billnest/model/BillCategoryRules.kt app/src/test/java/com/baylee/billnest/model/MerchantEngineTest.kt
git commit -m "feat: add merchant identity engine"
```

---

## Task 3 — Replace simple-only automation with deterministic smart rules and non-mutating preview

**Files:**
- Create `app/src/main/java/com/baylee/billnest/model/SmartTransactionRuleEngine.kt`
- Create `app/src/test/java/com/baylee/billnest/model/SmartTransactionRuleEngineTest.kt`
- Modify `app/src/main/java/com/baylee/billnest/model/TransactionRules.kt`

- [ ] **Step 1: Write failing tests for matching, priority, preview, and user overrides.**

Tests must prove:
- conditions can combine raw merchant text + account + amount range + category + classification + direction;
- higher `priority` wins over a lower-priority matching rule;
- equal priority uses greater condition specificity, then newest update time, then stable ID tie-break;
- preview returns before/after rows without mutating the input list;
- a smart rule never overwrites category/classification when `userClassificationOverride == true`;
- display rename is stored in `displayNameOverride`, leaving `name` unchanged;
- recurring action upserts a `SubscriptionPreference` rather than mutating a transaction into a fake subscription;
- legacy rule rename now writes `displayNameOverride` rather than replacing the raw `name`.

Representative priority test:

```kotlin
@Test
fun higherPriorityRuleWinsAndPreviewDoesNotMutateSource() {
    val tx = FinanceTransaction(id = "tx", name = "TARGET 123", amount = 60.0, dateIso = "2026-09-13")
    val low = SmartTransactionRule(
        id = "low",
        name = "Generic Target",
        priority = 1,
        match = SmartRuleMatch(rawNameContains = "TARGET"),
        action = SmartRuleAction(category = "Shopping")
    )
    val high = SmartTransactionRule(
        id = "high",
        name = "Target groceries",
        priority = 10,
        match = SmartRuleMatch(rawNameContains = "TARGET", minAmount = 50.0),
        action = SmartRuleAction(category = "Groceries")
    )

    val preview = previewSmartRuleSet(listOf(tx), listOf(low, high), emptyList(), AppData())

    assertEquals("Groceries", preview.single().after.category)
    assertEquals("high", preview.single().winningRuleId)
    assertEquals("Other", tx.category)
}
```

Run and confirm RED:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionRuleEngineTest --stacktrace
```

- [ ] **Step 2: Implement exact classification/direction helpers and match logic.**

Expose:

```kotlin
fun transactionClassification(transaction: FinanceTransaction): TransactionClassification
fun transactionDirection(transaction: FinanceTransaction): TransactionDirection
fun smartRuleMatches(transaction: FinanceTransaction, rule: SmartTransactionRule): Boolean
fun winningSmartRule(transaction: FinanceTransaction, rules: List<SmartTransactionRule>): SmartTransactionRule?
```

Direction rule: negative amount is `INFLOW`, zero/positive is `OUTFLOW`. Classification always uses explicit `transfer`/`income` flags first and otherwise `SPENDING`.

- [ ] **Step 3: Implement rule application without creating manual overrides.**

Rule-applied classification must not set `userClassificationOverride = true`. Use a dedicated helper rather than `reclassifyTransaction`, because `reclassifyTransaction` correctly marks real user edits as manual.

Pseudo-shape to implement exactly:

```kotlin
private fun applyAutomaticClassification(
    transaction: FinanceTransaction,
    classification: TransactionClassification,
    category: String?
): FinanceTransaction {
    if (transaction.userClassificationOverride) return transaction
    return when (classification) {
        TransactionClassification.SPENDING -> transaction.copy(
            category = category ?: transaction.category,
            transfer = false,
            income = false,
            transferFromAccountId = null,
            transferToAccountId = null
        )
        TransactionClassification.INCOME -> transaction.copy(
            category = "Income",
            transfer = false,
            income = true,
            transferFromAccountId = null,
            transferToAccountId = null,
            splits = null
        )
        TransactionClassification.TRANSFER -> transaction.copy(
            category = "Transfer",
            transfer = true,
            income = false,
            splits = null
        )
    }
}
```

- [ ] **Step 4: Implement batch application and preview.**

Expose:

```kotlin
fun applySmartRuleSet(
    transactions: List<FinanceTransaction>,
    rules: List<SmartTransactionRule>,
    profiles: List<MerchantProfile>,
    subscriptions: List<SubscriptionPreference>,
    data: AppData
): SmartRuleBatchResult

fun previewSmartRuleSet(
    transactions: List<FinanceTransaction>,
    rules: List<SmartTransactionRule>,
    profiles: List<MerchantProfile>,
    data: AppData
): List<RulePreviewRow>
```

The preview must operate on copies and never mutate `AppData` or lists in place.

- [ ] **Step 5: Make legacy rules raw-data-safe.**

Change legacy `applyTransactionRules()` rename behavior from:

```kotlin
name = matching.renameTo ?: transaction.name
```

to:

```kotlin
displayNameOverride = matching.renameTo?.trim()?.takeIf { it.isNotEmpty() } ?: transaction.displayNameOverride
```

Leave legacy category/exclusion semantics intact for compatibility.

- [ ] **Step 6: Run focused and regression tests.**

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionRuleEngineTest --tests com.baylee.billnest.model.FinanceModelsTest --stacktrace
```

Expected: GREEN.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/model/SmartTransactionRuleEngine.kt app/src/main/java/com/baylee/billnest/model/TransactionRules.kt app/src/test/java/com/baylee/billnest/model/SmartTransactionRuleEngineTest.kt
git commit -m "feat: add deterministic smart transaction rules"
```

---

## Task 4 — Build the Review Inbox engine with stable resolution fingerprints

**Files:**
- Create `app/src/main/java/com/baylee/billnest/model/ReviewInboxEngine.kt`
- Create `app/src/test/java/com/baylee/billnest/model/ReviewInboxEngineTest.kt`

- [ ] **Step 1: Write failing detector and suppression tests.**

Create tests for all Stage A review types:
1. `UNCATEGORIZED`: spending transaction category is blank/Other.
2. `UNKNOWN_MERCHANT`: no user-confirmed merchant profile resolves for the normalized merchant identity.
3. `POSSIBLE_TRANSFER`: opposite-signed same-amount rows on different accounts within three days.
4. `POSSIBLE_INCOME`: unclassified inflow with payroll/direct-deposit language or a repeated payday-like payer.
5. `POSSIBLE_RECURRING`: current `detectSubscriptions()` finds a candidate without a confirmed/ignored subscription preference.
6. `POTENTIAL_DUPLICATE`: same normalized merchant, account, absolute amount, and date within one day.
7. `CATEGORY_CONFLICT`: current merchant category differs from a dominant historical category supported by at least two prior rows.
8. `UNUSUAL_AMOUNT`: merchant has at least three prior amounts and current absolute amount is at least 2x the median and at least $25 above it.
9. Exact resolved/dismissed fingerprints suppress an item; changed evidence produces a new fingerprint and is visible.

Representative suppression test:

```kotlin
@Test
fun resolvedFingerprintStaysHiddenUntilEvidenceChanges() {
    val tx = FinanceTransaction(id = "tx", name = "New Cafe", amount = 20.0, dateIso = "2026-09-13", category = "Other")
    val first = generateReviewItems(AppData(transactions = listOf(tx))).first { it.type == ReviewType.UNCATEGORIZED }
    val resolvedData = AppData(
        transactions = listOf(tx),
        reviewResolutions = listOf(ReviewResolution(first.fingerprint, ReviewDisposition.RESOLVED))
    )

    assertTrue(visibleReviewItems(resolvedData).none { it.fingerprint == first.fingerprint })

    val changed = resolvedData.copy(transactions = listOf(tx.copy(amount = 39.0)))
    assertTrue(visibleReviewItems(changed).any { it.type == ReviewType.UNCATEGORIZED })
}
```

Run and confirm RED:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.ReviewInboxEngineTest --stacktrace
```

- [ ] **Step 2: Implement stable fingerprints.**

Use SHA-256 from `java.security.MessageDigest`:

```kotlin
fun reviewFingerprint(type: ReviewType, entityKey: String, evidenceKey: String): String {
    val raw = "${type.name}|$entityKey|$evidenceKey"
    return MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
```

Fingerprint evidence rules:
- unknown merchant: normalized merchant key only, so “keep as is” suppresses repeat noise for the same raw identity;
- uncategorized/unusual/category conflict: transaction ID plus amount/category evidence;
- duplicate: sorted pair of transaction IDs plus amount/date evidence;
- transfer: sorted pair IDs plus amount evidence;
- recurring: normalized merchant + detected frequency + typical amount bucket.

- [ ] **Step 3: Implement review generation and filtering.**

Expose:

```kotlin
fun generateReviewItems(data: AppData): List<ReviewItem>
fun visibleReviewItems(data: AppData): List<ReviewItem>
fun reviewAttentionCount(data: AppData): Int = visibleReviewItems(data).size
```

Sort visible items by confidence descending, then newest associated transaction date, then fingerprint for deterministic UI order.

- [ ] **Step 4: Run tests.**

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.ReviewInboxEngineTest --stacktrace
```

Expected: GREEN.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/model/ReviewInboxEngine.kt app/src/test/java/com/baylee/billnest/model/ReviewInboxEngineTest.kt
git commit -m "feat: add financial review inbox engine"
```

---

## Task 5 — Persist and household-sync merchant profiles, smart rules, and review resolutions

**Files:**
- Modify `app/src/main/java/com/baylee/billnest/model/SyncModels.kt`
- Modify `app/src/main/java/com/baylee/billnest/data/EncryptedStore.kt`
- Modify `app/src/main/java/com/baylee/billnest/data/BillRepository.kt`
- Modify `app/src/main/java/com/baylee/billnest/data/HouseholdSyncRepository.kt`
- Modify `cloudflare-worker/src/sync.js`
- Create `app/src/test/java/com/baylee/billnest/model/SmartTransactionSyncMapperTest.kt`
- Create `cloudflare-worker/test/smart-transaction-sync-kinds.test.js`

- [ ] **Step 1: Write failing Android mapper tests.**

Test JSON round-trip + mutation kinds for all three new records:

```kotlin
@Test
fun stageASyncKindsRoundTrip() {
    val merchant = MerchantProfile(id = "m1", displayName = "Walmart", aliases = listOf("WM SUPERCENTER"))
    val rule = SmartTransactionRule(
        id = "r1",
        name = "Walmart groceries",
        match = SmartRuleMatch(merchantProfileId = "m1"),
        action = SmartRuleAction(category = "Groceries")
    )
    val resolution = ReviewResolution("fingerprint-1", ReviewDisposition.DISMISSED)

    assertEquals(merchant, SyncMapper.decodeMerchantProfile(SyncMapper.encodeMerchantProfile(merchant)))
    assertEquals("merchant_profile", SyncMapper.merchantProfileMutation(merchant).kind)
    assertEquals("smart_transaction_rule", SyncMapper.smartTransactionRuleMutation(rule).kind)
    assertEquals("review_resolution", SyncMapper.reviewResolutionMutation(resolution).kind)
}
```

Run and confirm RED:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionSyncMapperTest --stacktrace
```

- [ ] **Step 2: Add codecs and mutation helpers.**

In `SyncMapper`, add encode/decode/mutation methods with record IDs:
- merchant profile → `profile.id`
- smart rule → `rule.id`
- review resolution → `resolution.fingerprint`

- [ ] **Step 3: Make encrypted Alpha29 data backward-compatible.**

In `EncryptedStore.load()`, inject empty JSON arrays when absent:

```kotlin
listOf("merchantProfiles", "smartTransactionRules", "reviewResolutions").forEach { field ->
    if (!root.has(field) || root.get(field).isJsonNull) root.add(field, JsonArray())
}
```

- [ ] **Step 4: Write failing Worker allow-list test.**

`smart-transaction-sync-kinds.test.js` must assert that `merchant_profile`, `smart_transaction_rule`, and `review_resolution` are accepted through the existing authenticated household sync endpoint, while a made-up kind remains rejected.

Run and confirm RED:

```bash
node --test cloudflare-worker/test/smart-transaction-sync-kinds.test.js
```

- [ ] **Step 5: Add the three Worker kinds only.**

Extend `ALLOWED_KINDS` in `cloudflare-worker/src/sync.js`:

```js
'merchant_profile',
'smart_transaction_rule',
'review_resolution'
```

Do not add D1 tables: existing `finance_records` already stores arbitrary allowed kinds.

- [ ] **Step 6: Add repository CRUD and remote replay.**

Add methods:

```kotlin
fun saveMerchantProfile(value: MerchantProfile)
fun deleteMerchantProfile(id: String)
fun saveSmartTransactionRule(value: SmartTransactionRule)
fun deleteSmartTransactionRule(id: String)
fun saveReviewResolution(value: ReviewResolution)
fun deleteReviewResolution(fingerprint: String)
```

Each local mutation queues exactly one corresponding sync mutation. Remote replay updates `AppData` without queueing another local mutation.

For remote profile/rule updates, re-run the local merchant + smart-rule pipeline after changing the list so another household member sees the effect immediately.

- [ ] **Step 7: Extend legacy household snapshot/migration replay.**

In `HouseholdSyncRepository`, include these collections when seeding a household snapshot and add change handlers for the new kinds.

- [ ] **Step 8: Run focused/full sync tests.**

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionSyncMapperTest --tests com.baylee.billnest.data.SyncMappingTest --stacktrace
node --test cloudflare-worker/test/smart-transaction-sync-kinds.test.js cloudflare-worker/test/sync.test.js
```

Expected: GREEN.

- [ ] **Step 9: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/model/SyncModels.kt app/src/main/java/com/baylee/billnest/data/EncryptedStore.kt app/src/main/java/com/baylee/billnest/data/BillRepository.kt app/src/main/java/com/baylee/billnest/data/HouseholdSyncRepository.kt cloudflare-worker/src/sync.js app/src/test/java/com/baylee/billnest/model/SmartTransactionSyncMapperTest.kt cloudflare-worker/test/smart-transaction-sync-kinds.test.js
git commit -m "feat: sync smart transaction metadata"
```

---

## Task 6 — Integrate the smart pipeline into repository and ViewModel behavior

**Files:**
- Modify `app/src/main/java/com/baylee/billnest/data/BillRepository.kt`
- Modify `app/src/main/java/com/baylee/billnest/ui/MainViewModel.kt`
- Add/extend tests under `app/src/test/java/com/baylee/billnest/model/`

- [ ] **Step 1: Write a failing pipeline regression test.**

Prove this order on a Plaid refresh:
1. raw Plaid row mapped to BillNest account ID;
2. legacy rule compatibility layer;
3. merchant profile application;
4. smart-rule application;
5. merge with saved per-transaction user overrides/metadata.

The test must also prove a manual category override survives even when a matching merchant profile and smart rule disagree.

- [ ] **Step 2: Extract one reusable pure pipeline helper.**

Implement in a focused model file or `SmartTransactionRuleEngine.kt`:

```kotlin
fun processIncomingTransactions(data: AppData, incoming: List<FinanceTransaction>): SmartRuleBatchResult {
    val legacy = applyTransactionRules(incoming, data.transactionRules)
    val profiled = applyMerchantProfiles(legacy, data.merchantProfiles, data)
    return applySmartRuleSet(
        transactions = profiled,
        rules = data.smartTransactionRules,
        profiles = data.merchantProfiles,
        subscriptions = data.subscriptionPreferences,
        data = data
    )
}
```

Repository `syncPlaidTransactions()` maps account IDs before calling this helper, then uses `mergePlaidTransactions()`.

- [ ] **Step 3: Apply rules/profiles immediately when they are saved or remotely received.**

`saveMerchantProfile()` and `saveSmartTransactionRule()` must recalculate local derived transaction metadata. When smart-rule application changes subscription preferences, upsert and queue only preferences whose stored value actually changed.

- [ ] **Step 4: Add ViewModel wrappers.**

```kotlin
fun saveMerchantProfile(v: MerchantProfile) = repo.saveMerchantProfile(v)
fun deleteMerchantProfile(id: String) = repo.deleteMerchantProfile(id)
fun saveSmartTransactionRule(v: SmartTransactionRule) = repo.saveSmartTransactionRule(v)
fun deleteSmartTransactionRule(id: String) = repo.deleteSmartTransactionRule(id)
fun resolveReview(fingerprint: String, disposition: ReviewDisposition) =
    repo.saveReviewResolution(ReviewResolution(fingerprint, disposition))
```

- [ ] **Step 5: Run tests.**

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionRuleEngineTest --tests com.baylee.billnest.model.SmartTransactionModelsTest --stacktrace
```

Expected: GREEN.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/data/BillRepository.kt app/src/main/java/com/baylee/billnest/ui/MainViewModel.kt app/src/main/java/com/baylee/billnest/model/SmartTransactionRuleEngine.kt app/src/test/java/com/baylee/billnest/model
git commit -m "feat: wire smart transaction processing pipeline"
```

---

## Task 7 — Build the Review Inbox UI and resolution actions

**Files:**
- Create `app/src/main/java/com/baylee/billnest/ReviewInboxPage.kt`
- Reuse `TransactionEditorDialog.kt`, `CategoryPicker.kt`, `SmartRuleEditorDialog.kt` once Task 8 creates it
- Modify `app/src/main/java/com/baylee/billnest/ui/MainViewModel.kt` only if a thin action wrapper is missing

- [ ] **Step 1: Implement a focused dark Review Inbox page.**

Signature:

```kotlin
@Composable
fun ReviewInboxPage(
    data: AppData,
    vm: MainViewModel,
    modifier: Modifier = Modifier
)
```

Derive items with `remember(data) { visibleReviewItems(data) }`. Use one `LazyColumn`; no nested whole-page scrolling.

Each card shows:
- type label;
- transaction effective display name + amount/date when available;
- the engine explanation;
- confidence only as human-readable `High`, `Medium`, or `Low`, not fake precision percentages;
- phone-safe action rows.

- [ ] **Step 2: Wire one-tap resolutions by review type.**

Required actions:
- `UNCATEGORIZED`: choose existing/new category via `CategoryPickerField`, save transaction, resolve fingerprint.
- `UNKNOWN_MERCHANT`: open merchant-profile editor prefilled from raw merchant; saving profile resolves fingerprint.
- `POSSIBLE_TRANSFER`: mark the paired rows as transfer using existing reclassification behavior when both sides are present; otherwise open transaction editor; resolve after save.
- `POSSIBLE_INCOME`: mark as Income or dismiss.
- `POSSIBLE_RECURRING`: Track subscription or dismiss.
- `POTENTIAL_DUPLICATE`: open both details; delete selected duplicate or dismiss as legitimate.
- `CATEGORY_CONFLICT`: apply suggested category to this transaction, create rule, or keep current/dismiss.
- `UNUSUAL_AMOUNT`: edit transaction or `Looks right` dismiss.

Never silently delete/reclassify from detection alone.

- [ ] **Step 3: Add empty state and resolved behavior.**

Empty copy: `You're all caught up. BillNest has nothing that needs review right now.`

After a resolution is saved, the card must disappear because the same `AppData` recomputes through `visibleReviewItems()`.

- [ ] **Step 4: Compile-test UI wiring.**

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Expected: GREEN.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/ReviewInboxPage.kt app/src/main/java/com/baylee/billnest/ui/MainViewModel.kt
git commit -m "feat: add financial review inbox UI"
```

---

## Task 8 — Build Merchant Manager and smart rule editor with preview

**Files:**
- Create `app/src/main/java/com/baylee/billnest/MerchantManagerPage.kt`
- Create `app/src/main/java/com/baylee/billnest/SmartRuleEditorDialog.kt`
- Modify `app/src/main/java/com/baylee/billnest/Alpha19FinanceScreens.kt`
- Modify `app/src/main/java/com/baylee/billnest/InsightsPageV5.kt`

- [ ] **Step 1: Build Merchant Manager page.**

Signature:

```kotlin
@Composable
fun MerchantManagerPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier)
```

Page sections:
- search;
- confirmed merchant profiles with display name, aliases, preferred category/classification;
- smart rules with enabled state, priority, concise match summary, action summary;
- `+ Merchant` and `+ Rule` actions.

Merchant editor requirements:
- canonical display name;
- multiple aliases/patterns;
- `CategoryPickerField` for preferred category;
- optional default classification;
- save/delete using ViewModel.

- [ ] **Step 2: Build smart rule editor with all Stage A conditions/actions.**

The dialog must support:
- raw name contains;
- canonical merchant;
- account;
- minimum/maximum absolute amount;
- current category;
- current classification;
- inflow/outflow;
- priority;
- enabled toggle;
- actions: display rename, category, classification, exclusion, recurring status.

Use `CategoryPickerField`; do not add a second custom category implementation.

- [ ] **Step 3: Make preview a required step before save when the rule has matches.**

Compute:

```kotlin
val preview = previewSmartRuleSet(
    transactions = data.transactions,
    rules = data.smartTransactionRules.filterNot { it.id == candidate.id } + candidate,
    profiles = data.merchantProfiles,
    data = data
)
```

Show only rows where `winningRuleId == candidate.id`, including before/after merchant display name, category/classification, and exclusion state. Saving is permitted after the user sees the preview; if there are zero current matches, clearly state `No current transactions match. The rule will still apply to future matches.`

- [ ] **Step 4: Replace current “Make rule” entry points with the smart editor.**

In `TransactionsPageV3` and Insights category transaction actions, `Make rule` opens `SmartRuleEditorDialog` prefilled from the source transaction. Keep existing `TransactionRule` rows visible as `Legacy rules` until converted/deleted.

Provide a conversion helper/action:

```kotlin
fun TransactionRule.toSmartRule(): SmartTransactionRule = SmartTransactionRule(
    name = "Legacy: $merchantContains",
    match = SmartRuleMatch(rawNameContains = merchantContains),
    action = SmartRuleAction(
        displayName = renameTo,
        category = category,
        excludeFromSpending = excludeFromSpending
    )
)
```

Conversion must create the smart rule first, then delete the legacy rule only after save succeeds locally.

- [ ] **Step 5: Display effective merchant names.**

Transactions and Insights should render `row.effectiveDisplayName()` instead of raw `row.name`, while raw name remains available inside detail/editor context when useful.

- [ ] **Step 6: Compile and run all Android unit tests.**

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Expected: GREEN.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/MerchantManagerPage.kt app/src/main/java/com/baylee/billnest/SmartRuleEditorDialog.kt app/src/main/java/com/baylee/billnest/Alpha19FinanceScreens.kt app/src/main/java/com/baylee/billnest/InsightsPageV5.kt
git commit -m "feat: add merchant manager and smart rule preview"
```

---

## Task 9 — Add navigation and Dashboard attention entry points

**Files:**
- Modify `app/src/main/java/com/baylee/billnest/FinanceActivity.kt`
- Modify `app/src/main/java/com/baylee/billnest/DashboardV4.kt`
- Modify transaction header in `app/src/main/java/com/baylee/billnest/Alpha19FinanceScreens.kt`

- [ ] **Step 1: Add routes without replacing the current shell.**

Add destinations:
- `Review Inbox`
- `Merchants & Rules`

Route them to `ReviewInboxPage(data, vm, Modifier.padding(pad))` and `MerchantManagerPage(data, vm, Modifier.padding(pad))`.

- [ ] **Step 2: Add a Dashboard review-attention card only when count > 0.**

Change signature to:

```kotlin
@Composable
fun DashboardV4(
    data: AppData,
    modifier: Modifier = Modifier,
    onOpenReviewInbox: (() -> Unit)? = null
)
```

Compute `reviewAttentionCount(data)` and show a compact `Needs review` card near the top. The button changes the internal FinanceActivity route to `Review Inbox`; it must not start another activity or reset authenticated state.

- [ ] **Step 3: Add transaction shortcuts.**

At the top of Transactions, add phone-safe buttons for `Review` and `Merchants & Rules`, using navigation callbacks passed from `FinanceActivity` rather than hardcoding activities.

- [ ] **Step 4: Verify navigation and compile.**

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Expected: GREEN and no duplicate/nested scroll regression.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/baylee/billnest/FinanceActivity.kt app/src/main/java/com/baylee/billnest/DashboardV4.kt app/src/main/java/com/baylee/billnest/Alpha19FinanceScreens.kt
git commit -m "feat: surface smart review navigation"
```

---

## Task 10 — Full regression pass and Alpha30 release

**Files:**
- Modify `app/build.gradle.kts`
- Modify `.github/workflows/build-billnest-apk.yml`
- Modify tests only if a real regression is found; do not weaken assertions to make CI green.

- [ ] **Step 1: Run the entire local test suite before version bump.**

```bash
node --check cloudflare-worker/src/index.js
node --test cloudflare-worker/test/*.test.js
gradle :app:testDebugUnitTest --stacktrace
```

Expected: all GREEN.

- [ ] **Step 2: Explicitly regression-check existing critical behavior.**

Confirm tests cover and still pass:
- Alpha29 category normalization/shared category catalog;
- split transactions;
- current-vs-projected Dashboard cash behavior;
- Plaid transaction manual classification override preservation;
- Plaid account/debt merge behavior;
- transaction tombstones;
- household sync generic kinds;
- budget calculations excluding transfers/income;
- paycheck calculator unchanged.

- [ ] **Step 3: Bump Alpha30 only after tests are green.**

`app/build.gradle.kts`:

```kotlin
versionCode = 37
versionName = "2.0.0-alpha30"
```

`.github/workflows/build-billnest-apk.yml`:

```yaml
cp app/build/outputs/apk/debug/app-debug.apk BillNest-v2.0.0-alpha30-debug.apk
```

Artifact name:

```yaml
BillNest-v2.0.0-alpha30-debug-apk
```

Do not alter the signing config or expected fingerprints.

- [ ] **Step 4: Commit release metadata.**

```bash
git add app/build.gradle.kts .github/workflows/build-billnest-apk.yml
git commit -m "release: package BillNest alpha30"
```

- [ ] **Step 5: Verify exact branch HEAD before judging CI.**

Record the exact `billnest-apk-build` HEAD SHA. Only workflow runs whose `head_sha` equals that SHA qualify as Alpha30 verification.

- [ ] **Step 6: Require all GitHub Actions release gates on the exact HEAD.**

The Build BillNest APK job must show success for:
1. Validate Cloudflare Worker syntax
2. Run Cloudflare Worker regression tests
3. Accept Android licenses
4. Install Android SDK
5. Run Android unit tests
6. Verify stable BillNest signing certificate
7. Build debug APK
8. Verify APK uses stable signing certificate
9. Rename APK
10. Upload artifact

Required signing values remain:

```text
Keystore SHA-256:
0A:A4:71:98:7E:2D:6B:34:AD:D2:CE:81:1D:F5:37:FE:8D:AE:20:3A:7B:B4:74:BB:90:CB:96:06:E3:03:65:4C

APK signer SHA-256:
0aa471987e2d6b34add2ce811df537fe8dae203a7bb474bb90cb9606e303654c
```

Also require the exact-HEAD `Verify BillNest Live Auth` workflow to conclude `success`.

- [ ] **Step 7: Download and verify the built artifact before delivery.**

Download the exact run artifact `BillNest-v2.0.0-alpha30-debug-apk`, extract it under `/mnt/data/billnest-alpha30/`, confirm the APK exists, compute SHA-256, and provide the user the sandbox link to that exact APK.

- [ ] **Step 8: Do not claim future stages are implemented.**

Alpha30 completion language must say Stage A includes Review Inbox + Merchant Manager + smart rules/preview/sync. Stage B recurring price-change/missed-renewal and credit-card statement intelligence remain the next stage.

---

## Stage A Completion Checklist

- [ ] Raw Plaid merchant names remain authoritative and are not overwritten by new rename logic.
- [ ] Merchant aliases resolve deterministically and preferred categories reuse the shared category catalog.
- [ ] Smart rules support all approved Stage A match/action fields.
- [ ] Rule conflicts have deterministic winners and previews show the winning rule.
- [ ] Rule preview is non-mutating.
- [ ] Explicit user transaction overrides beat automatic rules.
- [ ] Review Inbox detects all eight Stage A review types and never auto-mutates from detection alone.
- [ ] Review resolutions suppress unchanged issues and allow changed evidence to reappear.
- [ ] Merchant profiles, smart rules, and review resolutions household-sync through existing generic sync infrastructure.
- [ ] Legacy transaction rules still load/work and can be converted deliberately.
- [ ] Transactions and Insights display BillNest effective merchant names while retaining raw bank names.
- [ ] Dashboard/Transactions provide clear entry points to Review Inbox and Merchant Manager.
- [ ] No Stage B–G feature is falsely represented as finished.
- [ ] Alpha30 exact HEAD passes all Worker/Android/signing/artifact/Live Auth gates.
