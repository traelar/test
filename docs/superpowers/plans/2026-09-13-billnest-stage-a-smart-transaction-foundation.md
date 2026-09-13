# BillNest Stage A Smart Transaction Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Alpha30 with the first approved smart-finance stage: Financial Review Inbox, Merchant Manager, deterministic multi-condition transaction rules with preview, and household-synced merchant/rule/review metadata, while preserving Plaid Production, Alpha29 category behavior, existing user transaction overrides, household sync, and update-in-place signing.

**Architecture:** Raw Plaid transaction fields stay authoritative. BillNest adds separate display/merchant/rule metadata, pure Kotlin engines generate derived merchant/rule/review state, and only user-owned profiles/rules/review resolutions are persisted and household-synced. The existing generic `finance_records` sync table handles three new kinds, so Stage A requires no new D1 tables. Legacy `TransactionRule` records remain supported and are converted only when the user chooses.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Gson, encrypted local `AppData`, existing SQLite sync outbox, Cloudflare Worker + D1 generic finance-record sync, Node test runner, Gradle/JUnit, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-13-billnest-smart-finance-expansion-design.md`

## Global Constraints

- Work on `billnest-apk-build`; do not rewrite the app.
- Keep package `com.baylee.billnest` and working Plaid Production support.
- Never replace a raw Plaid merchant/name to implement a BillNest rename; use BillNest-owned display metadata.
- Explicit user transaction classification/category overrides always beat automatic merchant/rule behavior.
- Reuse `CategoryPickerField`, `CategoryMultiPickerField`, `canonicalCategoryName`, and category normalization everywhere.
- Keep legacy `TransactionRule` support during Stage A.
- Household privacy is Stage E; Stage A metadata follows the current household-shared model.
- Advanced recurring price-change/missed-renewal intelligence is Stage B; Stage A only reuses current recurring detection for review suggestions.
- Keep the current dark UI and phone-safe action layouts.
- TDD for every behavioral task: RED test first, minimal implementation, GREEN test, commit.
- Alpha30 target: `versionCode 37`, `versionName "2.0.0-alpha30"`.
- Do not claim release completion until exact-HEAD Worker tests, Android tests, signing precheck, APK build, APK certificate verification, artifact upload, and Live Auth succeed.

## File Map

**Create**
- `app/src/main/java/com/baylee/billnest/model/SmartTransactionModels.kt`
- `app/src/main/java/com/baylee/billnest/model/MerchantEngine.kt`
- `app/src/main/java/com/baylee/billnest/model/SmartTransactionRuleEngine.kt`
- `app/src/main/java/com/baylee/billnest/model/ReviewInboxEngine.kt`
- `app/src/main/java/com/baylee/billnest/MerchantManagerPage.kt`
- `app/src/main/java/com/baylee/billnest/SmartRuleEditorDialog.kt`
- `app/src/main/java/com/baylee/billnest/ReviewInboxPage.kt`
- `app/src/test/java/com/baylee/billnest/model/SmartTransactionModelsTest.kt`
- `app/src/test/java/com/baylee/billnest/model/MerchantEngineTest.kt`
- `app/src/test/java/com/baylee/billnest/model/SmartTransactionRuleEngineTest.kt`
- `app/src/test/java/com/baylee/billnest/model/ReviewInboxEngineTest.kt`
- `app/src/test/java/com/baylee/billnest/model/SmartTransactionSyncMapperTest.kt`
- `cloudflare-worker/test/smart-transaction-sync-kinds.test.js`

**Modify**
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

## Task 1 — Add Stage A models and preserve BillNest-owned transaction metadata

**Files:** create `SmartTransactionModels.kt`, modify `FinanceModels.kt`, `Models.kt`, `TransactionMerge.kt`, create `SmartTransactionModelsTest.kt`.

- [ ] Write a failing test proving a fresh Plaid row updates raw name/amount while retaining saved `displayNameOverride`, `merchantProfileId`, `appliedSmartRuleId`, and any explicit user classification/splits.

```kotlin
@Test
fun plaidRefreshPreservesBillNestOwnedMetadata() {
    val saved = FinanceTransaction(
        id = "tx-1", name = "WM SUPERCENTER 1234", amount = 42.0,
        dateIso = "2026-09-12", category = "Groceries", source = TransactionSource.PLAID,
        displayNameOverride = "Walmart", merchantProfileId = "merchant-walmart",
        appliedSmartRuleId = "rule-walmart"
    )
    val fresh = saved.copy(
        name = "WAL-MART #1234", amount = 44.0,
        displayNameOverride = null, merchantProfileId = null, appliedSmartRuleId = null
    )
    val merged = mergePlaidTransactions(listOf(saved), listOf(fresh)).single()
    assertEquals("WAL-MART #1234", merged.name)
    assertEquals(44.0, merged.amount, 0.001)
    assertEquals("Walmart", merged.displayNameOverride)
    assertEquals("merchant-walmart", merged.merchantProfileId)
    assertEquals("rule-walmart", merged.appliedSmartRuleId)
}
```

Run and confirm RED:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionModelsTest --stacktrace
```

- [ ] Add exact Stage A contracts:

```kotlin
enum class TransactionDirection { INFLOW, OUTFLOW }
enum class MerchantConfirmation { AUTO, USER_CONFIRMED }
enum class ReviewDisposition { RESOLVED, DISMISSED }
enum class ReviewType {
    UNCATEGORIZED, UNKNOWN_MERCHANT, POSSIBLE_TRANSFER, POSSIBLE_INCOME,
    POSSIBLE_RECURRING, POTENTIAL_DUPLICATE, CATEGORY_CONFLICT, UNUSUAL_AMOUNT,
    ACCOUNT_METADATA_MISSING, DEBT_METADATA_MISSING
}

data class MerchantProfile(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val preferredCategory: String? = null,
    val defaultClassification: TransactionClassification? = null,
    val linkedBillId: String? = null,
    val subscriptionMerchantKey: String? = null,
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
    val transactionIds: List<String> = emptyList(),
    val accountId: String? = null,
    val debtId: String? = null,
    val title: String,
    val explanation: String,
    val confidence: Double,
    val suggestedCategory: String? = null,
    val suggestedClassification: TransactionClassification? = null,
    val suggestedMerchantName: String? = null
)
```

- [ ] Append defaulted fields to `FinanceTransaction`:

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

- [ ] Add to `AppData`:

```kotlin
val merchantProfiles: List<MerchantProfile> = emptyList(),
val smartTransactionRules: List<SmartTransactionRule> = emptyList(),
val reviewResolutions: List<ReviewResolution> = emptyList(),
```

- [ ] In `mergePlaidTransactions`, always preserve the three BillNest metadata fields from a matching saved transaction. Preserve category/classification/splits only when the existing `userClassificationOverride` rule says to do so.

- [ ] Rerun:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionModelsTest --tests com.baylee.billnest.model.FinanceModelsTest --stacktrace
```

- [ ] Commit:

```bash
git add app/src/main/java/com/baylee/billnest/model app/src/test/java/com/baylee/billnest/model/SmartTransactionModelsTest.kt
git commit -m "feat: add smart transaction metadata foundation"
```

---

## Task 2 — Build merchant identity/profile engine

**Files:** create `MerchantEngine.kt`, `MerchantEngineTest.kt`; modify `BillCategoryRules.kt`.

- [ ] RED tests must cover alias normalization, longest-alias winner, category canonicalization, user override protection, and raw-name preservation.

```kotlin
@Test
fun profileAliasCreatesBillNestDisplayNameWithoutChangingRawName() {
    val profile = MerchantProfile(
        id = "walmart", displayName = "Walmart",
        aliases = listOf("WM SUPERCENTER", "WAL MART"), preferredCategory = "Groceries"
    )
    val tx = FinanceTransaction(id = "tx", name = "WM SUPERCENTER #1234", amount = 51.25, dateIso = "2026-09-13")
    val result = applyMerchantProfiles(listOf(tx), listOf(profile), AppData()).single()
    assertEquals("WM SUPERCENTER #1234", result.name)
    assertEquals("Walmart", result.displayNameOverride)
    assertEquals("walmart", result.merchantProfileId)
    assertEquals("Groceries", result.category)
}
```

Run RED:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.MerchantEngineTest --stacktrace
```

- [ ] Implement:

```kotlin
fun merchantIdentityKey(value: String): String
fun resolveMerchantProfile(transaction: FinanceTransaction, profiles: List<MerchantProfile>): MerchantProfile?
fun applyMerchantProfile(transaction: FinanceTransaction, profile: MerchantProfile, data: AppData): FinanceTransaction
fun applyMerchantProfiles(transactions: List<FinanceTransaction>, profiles: List<MerchantProfile>, data: AppData): List<FinanceTransaction>
```

Normalization: lowercase → punctuation to spaces → collapse whitespace → remove standalone numeric store/terminal tokens. Never mutate raw transaction `name`.

Resolution order: longest matching normalized alias → newest `updatedAtEpochMs` → lexical profile ID.

Preferred category uses `canonicalCategoryName(data, value)` and must not override a transaction with `userClassificationOverride == true`. Default classification is also skipped for explicit user overrides.

- [ ] Extend `categoryOptions(data)`:

```kotlin
data.merchantProfiles.forEach { add(it.preferredCategory) }
data.smartTransactionRules.forEach { add(it.action.category) }
```

- [ ] Run GREEN:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.MerchantEngineTest --tests com.baylee.billnest.model.CategoryCatalogTest --stacktrace
```

- [ ] Commit:

```bash
git add app/src/main/java/com/baylee/billnest/model/MerchantEngine.kt app/src/main/java/com/baylee/billnest/model/BillCategoryRules.kt app/src/test/java/com/baylee/billnest/model/MerchantEngineTest.kt
git commit -m "feat: add merchant identity engine"
```

---

## Task 3 — Add deterministic smart rules and non-mutating preview

**Files:** create `SmartTransactionRuleEngine.kt`, `SmartTransactionRuleEngineTest.kt`; modify `TransactionRules.kt`.

- [ ] RED tests must prove combined matching, priority conflict resolution, non-mutating preview, user override protection, display rename metadata, recurring preference action, and legacy raw-name safety.

```kotlin
@Test
fun highPrioritySpecificRuleWinsWithoutMutatingPreviewInput() {
    val tx = FinanceTransaction(id = "tx", name = "TARGET 123", amount = 60.0, dateIso = "2026-09-13")
    val low = SmartTransactionRule(
        id = "low", name = "Generic", priority = 1,
        match = SmartRuleMatch(rawNameContains = "TARGET"),
        action = SmartRuleAction(category = "Shopping")
    )
    val high = SmartTransactionRule(
        id = "high", name = "Groceries", priority = 10,
        match = SmartRuleMatch(rawNameContains = "TARGET", minAmount = 50.0),
        action = SmartRuleAction(category = "Groceries")
    )
    val preview = previewSmartRuleSet(listOf(tx), listOf(low, high), emptyList(), AppData())
    assertEquals("Groceries", preview.single().after.category)
    assertEquals("high", preview.single().winningRuleId)
    assertEquals("Other", tx.category)
}
```

- [ ] Implement:

```kotlin
fun transactionClassification(transaction: FinanceTransaction): TransactionClassification
fun transactionDirection(transaction: FinanceTransaction): TransactionDirection
fun smartRuleMatches(transaction: FinanceTransaction, rule: SmartTransactionRule): Boolean
fun winningSmartRule(transaction: FinanceTransaction, rules: List<SmartTransactionRule>): SmartTransactionRule?
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

Direction: negative amount = `INFLOW`; zero/positive = `OUTFLOW`. Classification uses `transfer`, then `income`, else `SPENDING`.

Winning rule order: enabled only → highest `priority` → most non-null match conditions → newest `updatedAtEpochMs` → lexical ID.

Automatic classification must not call `reclassifyTransaction`, because that marks a user override. Implement a dedicated automatic helper that changes flags/category while leaving `userClassificationOverride = false`.

- [ ] Change legacy rename behavior to write `displayNameOverride` rather than `name`. Preserve legacy category/exclusion semantics.

- [ ] Add converter:

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

- [ ] Run GREEN:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionRuleEngineTest --tests com.baylee.billnest.model.FinanceModelsTest --stacktrace
```

- [ ] Commit:

```bash
git add app/src/main/java/com/baylee/billnest/model/SmartTransactionRuleEngine.kt app/src/main/java/com/baylee/billnest/model/TransactionRules.kt app/src/test/java/com/baylee/billnest/model/SmartTransactionRuleEngineTest.kt
git commit -m "feat: add deterministic smart transaction rules"
```

---

## Task 4 — Build Review Inbox engine and stable suppression

**Files:** create `ReviewInboxEngine.kt`, `ReviewInboxEngineTest.kt`.

- [ ] RED tests cover all ten Stage A review types:
  - uncategorized spending;
  - unknown merchant;
  - possible transfer pair;
  - possible income/paycheck;
  - current recurring suggestion;
  - potential duplicate;
  - category conflict;
  - unusual merchant amount;
  - account metadata missing;
  - debt metadata missing.

Account metadata rule for Stage A: flag an active account whose `role == AccountRole.OTHER`, because BillNest cannot reliably know whether it is spendable/savings/other until classified.

Debt metadata rule for Stage A: flag active debts with any of: `apr <= 0`, `minimumPayment <= 0`, or credit-card `creditLimit <= 0`. Do not require Stage B statement fields yet.

- [ ] RED suppression test:

```kotlin
@Test
fun resolutionSuppressesExactEvidenceButChangedEvidenceCanReturn() {
    val tx = FinanceTransaction(id = "tx", name = "Cafe", amount = 20.0, dateIso = "2026-09-13", category = "Other")
    val first = generateReviewItems(AppData(transactions = listOf(tx))).first { it.type == ReviewType.UNCATEGORIZED }
    val resolved = AppData(
        transactions = listOf(tx),
        reviewResolutions = listOf(ReviewResolution(first.fingerprint, ReviewDisposition.RESOLVED))
    )
    assertTrue(visibleReviewItems(resolved).none { it.fingerprint == first.fingerprint })
    val changed = resolved.copy(transactions = listOf(tx.copy(amount = 39.0)))
    assertTrue(visibleReviewItems(changed).any { it.type == ReviewType.UNCATEGORIZED })
}
```

- [ ] Implement SHA-256 fingerprints:

```kotlin
fun reviewFingerprint(type: ReviewType, entityKey: String, evidenceKey: String): String {
    val raw = "${type.name}|$entityKey|$evidenceKey"
    return MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
```

Fingerprint rules: unknown merchant uses normalized merchant identity; duplicate/transfer uses sorted pair IDs + evidence; transaction issues include transaction ID + material amount/category evidence; account/debt uses record ID + missing-field evidence; recurring uses merchant identity + frequency + typical amount bucket.

- [ ] Implement:

```kotlin
fun generateReviewItems(data: AppData): List<ReviewItem>
fun visibleReviewItems(data: AppData): List<ReviewItem>
fun reviewAttentionCount(data: AppData): Int = visibleReviewItems(data).size
```

Detector thresholds:
- transfer: opposite signs, equal absolute amount within $0.01, different account IDs, dates within 3 days;
- duplicate: same merchant key/account/absolute amount, dates within 1 day;
- category conflict: at least two historical same-merchant rows and one category owns >= 70% of comparable rows;
- unusual amount: at least three prior same-merchant amounts, current >= 2x median and >= $25 above median;
- possible income: unclassified inflow with payroll/direct-deposit language or repeated payday-like payer;
- possible recurring: reuse `detectSubscriptions()` and honor existing confirmed/ignored subscription preferences.

Sort by confidence descending, associated transaction date descending, then fingerprint.

- [ ] Run GREEN:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.ReviewInboxEngineTest --stacktrace
```

- [ ] Commit.

---

## Task 5 — Persist and household-sync Stage A metadata

**Files:** modify `SyncModels.kt`, `EncryptedStore.kt`, `BillRepository.kt`, `HouseholdSyncRepository.kt`, `cloudflare-worker/src/sync.js`; create mapper + Worker tests.

- [ ] RED Android sync test:

```kotlin
@Test
fun stageARecordsRoundTripAndUseExpectedKinds() {
    val merchant = MerchantProfile(id = "m1", displayName = "Walmart", aliases = listOf("WM SUPERCENTER"))
    val rule = SmartTransactionRule(
        id = "r1", name = "Walmart groceries",
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

- [ ] Add `SyncMapper` encode/decode/mutation functions. Record IDs: profile ID, rule ID, resolution fingerprint.

- [ ] Backward-compatible encrypted data migration:

```kotlin
listOf("merchantProfiles", "smartTransactionRules", "reviewResolutions").forEach { field ->
    if (!root.has(field) || root.get(field).isJsonNull) root.add(field, JsonArray())
}
```

- [ ] RED Worker test proving the new kinds are rejected before allow-list change, then add exactly:

```js
'merchant_profile',
'smart_transaction_rule',
'review_resolution'
```

No new D1 tables.

Run:

```bash
node --test cloudflare-worker/test/smart-transaction-sync-kinds.test.js
```

- [ ] Repository CRUD:

```kotlin
fun saveMerchantProfile(value: MerchantProfile)
fun deleteMerchantProfile(id: String)
fun saveSmartTransactionRule(value: SmartTransactionRule)
fun deleteSmartTransactionRule(id: String)
fun saveReviewResolution(value: ReviewResolution)
fun deleteReviewResolution(fingerprint: String)
```

Local changes enqueue one mutation; remote replay must not enqueue another mutation.

- [ ] `HouseholdSyncRepository` must seed/replay all three collections. Remote profile/rule changes trigger local transaction re-evaluation after the record is applied.

- [ ] Run GREEN:

```bash
gradle :app:testDebugUnitTest --tests com.baylee.billnest.model.SmartTransactionSyncMapperTest --tests com.baylee.billnest.data.SyncMappingTest --stacktrace
node --test cloudflare-worker/test/smart-transaction-sync-kinds.test.js cloudflare-worker/test/sync.test.js
```

- [ ] Commit.

---

## Task 6 — Wire the smart processing pipeline into repository/ViewModel

**Files:** modify `BillRepository.kt`, `MainViewModel.kt`, `SmartTransactionRuleEngine.kt`; extend model tests.

- [ ] RED pipeline test proves the order: account-ID mapping → legacy compatibility rule → merchant profile → smart rules → Plaid merge/user overrides.

- [ ] Add pure helper:

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

`BillRepository.syncPlaidTransactions()` maps Plaid account IDs first, then calls this helper, then `mergePlaidTransactions()`.

- [ ] Saving or remotely receiving a merchant profile/smart rule immediately re-evaluates local transactions. If a rule changes subscription preferences, queue only actually changed preferences.

- [ ] ViewModel wrappers:

```kotlin
fun saveMerchantProfile(v: MerchantProfile) = repo.saveMerchantProfile(v)
fun deleteMerchantProfile(id: String) = repo.deleteMerchantProfile(id)
fun saveSmartTransactionRule(v: SmartTransactionRule) = repo.saveSmartTransactionRule(v)
fun deleteSmartTransactionRule(id: String) = repo.deleteSmartTransactionRule(id)
fun resolveReview(fingerprint: String, disposition: ReviewDisposition) =
    repo.saveReviewResolution(ReviewResolution(fingerprint, disposition))
```

- [ ] Run focused model tests and commit.

---

## Task 7 — Build Merchant Manager + smart rule editor/preview first

**Files:** create `MerchantManagerPage.kt`, `SmartRuleEditorDialog.kt`; modify `Alpha19FinanceScreens.kt`, `InsightsPageV5.kt`.

This task intentionally precedes Review Inbox UI so Review actions can reuse finished merchant/rule components.

- [ ] Build `MerchantManagerPage(data, vm, modifier)` with search, merchant profiles, smart rules, `+ Merchant`, and `+ Rule`.

Merchant editor fields: display name, aliases, preferred category via `CategoryPickerField`, optional default classification, optional existing bill link, optional subscription identity, save/delete.

- [ ] Build `SmartRuleEditorDialog` with all Stage A conditions/actions: raw text, merchant, account, amount range, current category, current classification, direction, priority, enabled; actions for display rename, category, classification, exclusion, recurring status.

- [ ] Rule preview is mandatory before saving a rule that has current matches:

```kotlin
val preview = previewSmartRuleSet(
    transactions = data.transactions,
    rules = data.smartTransactionRules.filterNot { it.id == candidate.id } + candidate,
    profiles = data.merchantProfiles,
    data = data
).filter { it.winningRuleId == candidate.id }
```

Show before/after display name, category/classification, and exclusion. For zero matches show: `No current transactions match. The rule will still apply to future matches.`

- [ ] Replace `Make rule` in Transactions and Insights with `SmartRuleEditorDialog` prefilled from source transaction.

- [ ] Existing simple rules appear under `Legacy rules`. Conversion saves the new smart rule first, then deletes the old rule only after local save succeeds.

- [ ] Transactions and Insights render `effectiveDisplayName()` while raw bank name remains available in edit/details.

- [ ] Run:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

- [ ] Commit.

---

## Task 8 — Build Review Inbox UI using the finished shared components

**Files:** create `ReviewInboxPage.kt`; reuse `TransactionEditorDialog.kt`, `CategoryPicker.kt`, `SmartRuleEditorDialog.kt`, merchant editor component from Task 7.

- [ ] Implement:

```kotlin
@Composable
fun ReviewInboxPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier)
```

Use `remember(data) { visibleReviewItems(data) }` in one `LazyColumn`. Each card shows review type, affected record/transaction, explanation, human-readable confidence (`High`/`Medium`/`Low`), and phone-safe actions.

- [ ] Wire actions:
  - uncategorized → shared category picker, save current transaction, resolve;
  - unknown merchant → merchant editor prefilled with raw alias, save profile, resolve;
  - possible transfer → confirm paired transfer or edit; no automatic mutation;
  - possible income → mark Income or dismiss;
  - possible recurring → Track or dismiss;
  - duplicate → inspect/delete selected duplicate or dismiss;
  - category conflict → use suggested category, open smart rule editor, or keep current;
  - unusual amount → edit or `Looks right` dismiss;
  - account metadata → open/focus existing account edit flow to set role, or dismiss;
  - debt metadata → open/focus existing debt edit flow for APR/minimum/limit, or dismiss.

Detection alone never deletes, reclassifies, tracks, or edits anything.

Empty state: `You're all caught up. BillNest has nothing that needs review right now.`

- [ ] Run:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

- [ ] Commit.

---

## Task 9 — Navigation and Dashboard attention entry points

**Files:** modify `FinanceActivity.kt`, `DashboardV4.kt`, Transactions header in `Alpha19FinanceScreens.kt`.

- [ ] Add destinations `Review Inbox` and `Merchants & Rules` to the existing authenticated shell; do not create a new Activity.

- [ ] Update Dashboard signature:

```kotlin
@Composable
fun DashboardV4(
    data: AppData,
    modifier: Modifier = Modifier,
    onOpenReviewInbox: (() -> Unit)? = null
)
```

When `reviewAttentionCount(data) > 0`, show a compact `Needs review` card with count and action. No empty attention card when count is zero.

- [ ] Add phone-safe `Review` and `Merchants & Rules` shortcuts to Transactions via navigation callbacks from `FinanceActivity`.

- [ ] Compile/test and commit.

---

## Task 10 — Full regression + Alpha30 release

**Files:** modify `app/build.gradle.kts`, `.github/workflows/build-billnest-apk.yml` only after all implementation tests are green.

- [ ] Full pre-release tests:

```bash
node --check cloudflare-worker/src/index.js
node --test cloudflare-worker/test/*.test.js
gradle :app:testDebugUnitTest --stacktrace
```

- [ ] Explicitly preserve regressions: Alpha29 category normalization, split transactions, Dashboard current-vs-projected cash, manual transaction overrides across Plaid refresh, Plaid account/debt merge, tombstones, household sync, budgets excluding transfer/income, and paycheck calculator behavior.

- [ ] Version bump:

```kotlin
versionCode = 37
versionName = "2.0.0-alpha30"
```

Workflow output:

```text
BillNest-v2.0.0-alpha30-debug.apk
BillNest-v2.0.0-alpha30-debug-apk
```

Do not change signing configuration.

- [ ] Commit release metadata:

```bash
git add app/build.gradle.kts .github/workflows/build-billnest-apk.yml
git commit -m "release: package BillNest alpha30"
```

- [ ] Record exact release HEAD and accept only CI runs matching that SHA.

Build workflow must pass: Worker syntax, all Worker tests, Android SDK setup, all Android unit tests, stable keystore fingerprint, APK build, APK certificate verification, rename, artifact upload.

Expected fingerprints remain:

```text
Keystore SHA-256:
0A:A4:71:98:7E:2D:6B:34:AD:D2:CE:81:1D:F5:37:FE:8D:AE:20:3A:7B:B4:74:BB:90:CB:96:06:E3:03:65:4C

APK signer SHA-256:
0aa471987e2d6b34add2ce811df537fe8dae203a7bb474bb90cb9606e303654c
```

Also require exact-HEAD `Verify BillNest Live Auth` success.

- [ ] Download the exact `BillNest-v2.0.0-alpha30-debug-apk` artifact, extract to `/mnt/data/billnest-alpha30/`, verify the APK exists, compute SHA-256, and deliver that exact APK.

- [ ] Alpha30 completion message must accurately state Stage A only. Stage B recurring price-change/missed-renewal and credit-card statement intelligence are next; Stages C–G remain unimplemented until their own plans execute.

## Completion Checklist

- [ ] Raw Plaid names remain raw; BillNest display renames are separate metadata.
- [ ] Merchant alias resolution is deterministic.
- [ ] Merchant profiles support category/classification and optional bill/subscription linkage.
- [ ] Smart rules support all approved Stage A match/action fields.
- [ ] Conflicts have deterministic winners and preview shows the winner.
- [ ] Preview is non-mutating.
- [ ] Explicit transaction user overrides beat automation.
- [ ] Review Inbox covers transaction, account, and debt attention cases and never auto-mutates from detection alone.
- [ ] Resolution fingerprints suppress unchanged issues and changed evidence can return.
- [ ] Profiles/rules/resolutions household-sync through the existing generic sync system.
- [ ] Legacy rules still work and can be deliberately converted.
- [ ] Transactions/Insights show effective merchant names without losing raw names.
- [ ] Dashboard and Transactions expose clear Review/Merchant entry points.
- [ ] No Stage B–G feature is falsely represented as finished.
- [ ] Exact Alpha30 HEAD passes all Worker/Android/signing/artifact/Live Auth gates.
