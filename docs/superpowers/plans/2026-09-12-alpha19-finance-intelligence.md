# BillNest Alpha19 Finance Intelligence Implementation Plan

Date: 2026-09-12
Branch: `billnest-apk-build`
Baseline: `2.0.0-alpha18` / versionCode 25
Target: `2.0.0-alpha19` / versionCode 26

## References

This plan executes the already-approved architecture in:
- `docs/superpowers/specs/2026-09-11-billnest-full-finance-household-design.md`
- `docs/superpowers/specs/2026-09-12-budget-overhaul-design.md`

The goal is to make the major finance areas work together: linked debt, transaction rules, budgets, planning, household sync, history/insights, and the dashboard. Preserve Plaid Production, package `com.baylee.billnest`, encrypted local persistence, offline-first household sync, the existing dark UI, and the stable signing certificate.

## Task 1 — Debt/Plaid correctness

Files:
- Modify `app/src/main/java/com/baylee/billnest/model/FinanceModels.kt`
- Test `app/src/test/java/com/baylee/billnest/model/Alpha19FinanceTest.kt`

TDD behavior:
1. Existing linked debt always receives the newest Plaid balance.
2. If BillNest already has a positive credit limit, preserve it instead of replacing it with Plaid's value.
3. If BillNest has no saved limit, initialize it from Plaid.
4. Preserve APR, minimum payment, due day, debt name, and record ID.
5. Keep one Debt row per Plaid credit account.

Expected core logic:
```kotlin
val refreshedLimit = if (debt.creditLimit > 0.0) debt.creditLimit else linked.creditLimit.coerceAtLeast(0.0)
debt.copy(balance = linked.balance.coerceAtLeast(0.0), creditLimit = refreshedLimit)
```

## Task 2 — Reusable transaction rules + spending exclusions

Files:
- Add `app/src/main/java/com/baylee/billnest/model/TransactionRules.kt`
- Modify `FinanceModels.kt`, `Models.kt`, `TransactionMerge.kt`, `BudgetEngine.kt`
- Test `Alpha19FinanceTest.kt`

Add:
```kotlin
data class TransactionRule(
    val id: String = UUID.randomUUID().toString(),
    val merchantContains: String,
    val renameTo: String? = null,
    val category: String? = null,
    val excludeFromSpending: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
```

Add `excludedFromSpending: Boolean = false` to `FinanceTransaction` and `transactionRules` to `AppData`.

`applyTransactionRules()` must:
- match merchant text case-insensitively;
- apply rename/category/exclude rules before incoming Plaid rows are merged;
- keep explicit user classification as the final authority during merge;
- make excluded rows disappear from variable-spending/budget totals without deleting them.

## Task 3 — Household-synced Plaid deletion tombstones

Files:
- Add tombstone model in `TransactionRules.kt` or focused model file
- Modify `Models.kt`, `TransactionMerge.kt`, `SyncModels.kt`
- Modify `BillRepository.kt`, `HouseholdSyncRepository.kt`, `EncryptedStore.kt`
- Modify `cloudflare-worker/src/sync.js`
- Add Worker regression test `cloudflare-worker/test/alpha19-sync-kinds.test.js`
- Test Android model behavior in `Alpha19FinanceTest.kt`

Add:
```kotlin
data class TransactionTombstone(
    val transactionId: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
```

Keep `deletedPlaidTransactionIds` for migration compatibility, but use synced `transactionTombstones` as the authoritative household deletion set going forward. A remote tombstone removes that transaction locally and prevents the next Plaid refresh from restoring it.

New sync kinds:
- `transaction_rule`
- `transaction_tombstone`
- `financial_snapshot`

Worker tests must be RED before adding these kinds to `ALLOWED_KINDS`.

## Task 4 — Budget history and alerts

Files:
- Modify `BudgetEngine.kt`
- Test `Alpha19FinanceTest.kt`

Add derived models/functions:
```kotlin
data class BudgetHistoryEntry(...)
data class BudgetAlert(...)
fun budgetHistory(data: AppData, budget: Budget, periods: Int, referenceDate: LocalDate): List<BudgetHistoryEntry>
fun budgetAlerts(data: AppData, referenceDate: LocalDate): List<BudgetAlert>
```

History should use the existing period engine, including paycheck periods, and return closed/current period totals without mutating data. Custom-range budgets may return their one explicit range. Alerts are derived from percent used, warning threshold, projection, and over-budget state. `eligibleVariableSpendingTransactions` must exclude `excludedFromSpending` rows.

## Task 5 — Monthly recap, net-worth history, debt payment detection, paycheck planning

Files:
- Add `app/src/main/java/com/baylee/billnest/model/FinanceInsights.kt`
- Modify `Models.kt`
- Test `Alpha19FinanceTest.kt`

Add pure result types/functions:
```kotlin
data class MonthlyRecap(...)
data class FinancialSnapshot(...)
data class NetWorthPoint(...)
data class DebtPaymentMatch(...)
data class PaycheckPlan(...)

fun monthlyRecap(data: AppData, month: YearMonth): MonthlyRecap
fun captureFinancialSnapshot(data: AppData, date: LocalDate): AppData
fun netWorthHistory(data: AppData): List<NetWorthPoint>
fun detectDebtPayments(data: AppData, referenceDate: LocalDate): List<DebtPaymentMatch>
fun calculateNextPaycheckPlan(data: AppData, referenceDate: LocalDate): PaycheckPlan?
```

Rules:
- recap spending excludes income, transfers, fixed/debt/savings movements, and explicit spending exclusions;
- snapshots store assets, debt, and net worth at most once per calendar day and replace the same day's value;
- history groups snapshots by month using the latest snapshot in each month;
- debt payment detection recognizes explicit transfers to a linked credit account and reasonable payment-name matches;
- paycheck planning combines next paycheck income, unpaid bills through the following payday, payday reserve/savings contributions, prorated debt minimums, and variable budget allocation, exposing the remainder as unassigned.

## Task 6 — Persist and sync alpha19 models

Files:
- Modify `SyncModels.kt`
- Modify `EncryptedStore.kt`
- Modify `BillRepository.kt`
- Modify `HouseholdSyncRepository.kt`
- Modify Worker sync allow-list and regression tests

Requirements:
- new lists load as empty from alpha18 encrypted data;
- rules, tombstones, and snapshots round-trip through `SyncMapper`;
- repository create/delete operations enqueue real household mutations;
- Plaid transaction sync applies rules and household tombstones;
- relevant account/debt updates capture a daily snapshot;
- legacy migration queues existing new records when present;
- remote replay updates AppData without generating a duplicate local mutation.

## Task 7 — Transaction and debt UI cleanup

Files:
- Modify `V2FinanceScreens.kt`
- Modify `TransactionEditorDialog.kt` as needed
- Modify the file containing `AccountDialog`
- Modify `MainViewModel.kt`

Transactions:
- display the absolute dollar amount for income and transfers;
- keep source → destination visible for transfers;
- add a `Rules` action with create/edit/delete merchant rules;
- allow a rule to rename, categorize, and/or exclude matching spending.

Debt:
- keep credit card creation/editing in Debt;
- remove manual `CREDIT` creation from the normal Accounts flow and explain that cards belong in Debt;
- show detected current-month payments on linked debts;
- retain editable APR/minimum/due/credit limit.

## Task 8 — New Dashboard, Planning and Insights experiences

Files:
- Add `DashboardPageV2.kt`
- Add `PlanningPage.kt`
- Add `InsightsPage.kt`
- Modify `FinanceActivity.kt`

Dashboard must use visible asset accounts only for account counts and show:
- Spending Money
- Savings
- Retirement
- Debt
- Available after obligations
- next payday/paycheck plan summary
- upcoming bills
- active budget warnings
- debt progress
- recent monthly recap

Add drawer destinations:
- `Planning` → paycheck allocation detail
- `Insights` → monthly recap, budget history, and net-worth history

Do not route the authenticated app back to legacy `MainActivity` screens.

## Task 9 — Settings cleanup

Files:
- Add `SettingsPageV2.kt` or update current settings implementation
- Modify `FinanceActivity.kt`

Requirements:
- app version comes from `BuildConfig.VERSION_NAME`;
- normal settings prioritize reminder/notification and bank-management controls;
- raw backend URL/API-key fields move under an explicit Advanced section rather than dominating normal setup;
- preserve backend configuration for compatibility and support.

## Task 10 — Release alpha19

Files:
- Modify `app/build.gradle.kts`
- Modify `.github/workflows/build-billnest-apk.yml`

Set:
- versionCode `26`
- versionName `2.0.0-alpha19`
- artifact/file names to alpha19

Verification gates:
1. RED Android/Worker tests must first fail for the intended missing behavior.
2. Model/sync tests green.
3. Worker syntax and all Worker tests green.
4. Android unit tests green.
5. Stable keystore fingerprint equals `0A:A4:71:98:7E:2D:6B:34:AD:D2:CE:81:1D:F5:37:FE:8D:AE:20:3A:7B:B4:74:BB:90:CB:96:06:E3:03:65:4C`.
6. APK builds successfully.
7. APK signer digest equals `0aa471987e2d6b34add2ce811df537fe8dae203a7bb474bb90cb9606e303654c`.
8. Verify exact branch head and Live Auth workflow success.
9. Download artifact, extract APK, hash it, and deliver it directly.
