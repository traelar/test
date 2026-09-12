# BillNest Smart Budget Overhaul Design

Date: 2026-09-12
Branch: `billnest-apk-build`

## Goal

Replace the current single-category Budget flow with a hybrid smart-budget system focused on variable spending while preserving BillNest's existing dark premium UI, Plaid Production support, household sync, and encrypted local persistence.

This release also limits the Income screen to transactions from the most recent 30 days without deleting older transaction history.

## Product principles

- Budgets are for variable spending, not fixed bills.
- Income, account transfers, savings movements, debt payments, and recognized fixed bills must not consume variable-spending budgets.
- Automatic matching should do most of the work, but explicit user choices always win.
- Existing Budget records must continue to load after the model expands.
- Older transactions remain available for historical calculations even when the Income screen only displays the latest 30 days.

## Budget model

Extend `Budget` instead of replacing it so existing records migrate safely. Keep the existing `id`, `name`, `amount`, `period`, `rollover`, `startDateIso`, and `endDateIso` fields. Add fields for:

- included categories
- excluded categories
- included merchant match rules
- excluded merchant match rules
- included account IDs
- excluded account IDs
- optional linked payday ID for paycheck-based budgeting
- rollover mode
- warning thresholds
- pace tracking enabled/disabled
- optional budget-group/envelope metadata where needed for multi-category budgets

Add explicit transaction-level budget overrides in `AppData`, keyed by transaction ID. An override can assign a transaction to one budget or exclude it from all budgets for the active period. Manual overrides take precedence over merchant and category rules.

Persist budget-to-budget rebalancing as explicit current-period adjustments rather than silently changing the base recurring amount. Historical completed periods must not be rewritten by later rebalancing.

## Matching precedence

For each eligible variable-spending transaction in the active budget period, use this precedence:

1. Manual transaction override.
2. Explicit merchant include/exclude rules.
3. Explicit category include/exclude rules.
4. Account include/exclude rules.
5. Default category match for migrated legacy budgets.
6. Unbudgeted spending if no budget matches.

A transaction must never count against more than one budget. When multiple automatic rules would match, choose the most specific rule and expose the reason in the UI.

## Variable-spending eligibility

A transaction is eligible for a budget only when it represents actual variable spending. Exclude:

- income
- transfers between accounts
- savings/reserve movements
- debt payments
- transactions matched to known fixed bills
- transactions explicitly excluded by the user

Use transaction classification and existing bill matching data where available. Do not mutate the original bank transaction merely to make budgeting work.

## Budget periods

Support:

- weekly
- biweekly
- paycheck-linked
- monthly
- yearly
- custom date range

Paycheck-linked budgets use a saved `Payday` schedule as their anchor. If the linked payday is edited, future periods follow the updated schedule; already completed periods remain unchanged.

## Rollover

Rollover is configured per budget. Supported behaviors:

- reset each period
- carry unused money forward
- carry both unused money and overspending forward

Rollover calculations operate on closed prior periods and must not rewrite historical transaction assignment.

## Budget suggestions

Add a pure calculation layer that analyzes recent eligible spending and returns suggestions without changing user data.

For each suggested budget, expose:

- last 30 days of eligible spending
- recent multi-month average when enough history exists
- a recommended rounded target
- the categories/merchants contributing to the suggestion

The recommendation should be a reasonable target, not simply a copy of average spending. Users may always enter their own amount.

## Budget summary calculations

For each active budget expose:

- base amount
- current-period adjustments
- effective available amount
- spent
- remaining
- percent used
- elapsed percent of period
- days remaining
- daily allowance when meaningful
- projected end-of-period spending
- pace state: under budget, on track, warning, or over budget

Also calculate a top-level Variable Spending summary and an Unbudgeted Spending total.

## Budget-to-budget transfers

Allow users to move money from one budget to another inside the same active period.

Rules:

- total planned variable spending does not change
- source and destination amounts change only for the active period
- adjustments are persisted and synced
- completed historical periods remain unchanged
- reject transfers that would make the source effective allocation negative

## Current-period rule recalculation

Editing merchant/category/account rules immediately recalculates the active period. Older completed periods retain the assignments and totals that were in effect for those periods.

To support historical stability, store enough per-period adjustment/override data to avoid recomputing closed periods from today's rules.

## Budget UI

### Budget home

Replace the current simple list with:

- Variable Spending summary card
- total planned, spent, and remaining
- projected end-of-period spend
- days remaining
- overall pace state
- individual budget cards
- Unbudgeted Spending section

Each budget card shows amount, spent, remaining, progress, period label, days remaining, projection, and pace state.

### Guided Add/Edit Budget flow

Use a full-screen or sheet-style guided flow rather than the current small dialog.

1. Choose budget type: standard category, custom, or multi-category/envelope.
2. Optional `Start from my spending` suggestion.
3. Choose amount and period.
4. Configure included/excluded categories, merchants, and accounts.
5. Configure rollover, warnings, and pace tracking.
6. Review and save.

The flow must remain compact and understandable; advanced rules can live in expandable sections.

### Budget detail

Show:

- remaining and spent totals
- days left
- daily allowance
- projected spend
- pace state
- all transactions currently counted
- why each transaction matched
- `Move to another budget`
- `Exclude from budget`
- current-period adjustment history

Manual transaction assignment overrides automatic matching.

### Rebalance flow

Provide `Move Money` from a budget detail or Budget home action. Select source, destination, and amount, then show the resulting allocations before saving.

## Income 30-day display rule

Add a pure helper used by `IncomePageV2` that returns income transactions whose parsed `dateIso` is within the inclusive window from `referenceDate.minusDays(29)` through `referenceDate`.

Behavior:

- display only the latest 30 calendar days
- do not delete older transactions
- keep manual saved Payday schedules unchanged
- detected payday suggestions on the Income screen use only the visible 30-day income set, matching what the user sees
- invalid dates are excluded from this view rather than crashing

## Persistence and sync

All new Budget fields and related override/adjustment records must survive:

- encrypted local persistence
- app upgrade from existing Alpha17 data
- household sync between devices
- remote mutation replay

Update the sync serialization/deserialization for the expanded models. Defaults must preserve compatibility with existing persisted records that do not contain the new fields.

Do not change Plaid Production credentials, backend URL behavior, Android package name, or stable signing configuration.

## Error handling

- Invalid custom period ranges cannot be saved.
- A paycheck-linked budget cannot be saved without a valid Payday link.
- Budget transfer amounts must be positive and cannot exceed the source's current effective allocation.
- Rule editing must gracefully handle deleted accounts or merchants that no longer appear in current transactions.
- Invalid transaction dates are ignored by period calculations instead of crashing.

## Testing strategy

Use TDD for the model and repository changes.

Add unit tests for:

- Income latest-30-day inclusive cutoff and invalid dates
- legacy budget compatibility
- eligibility exclusions for income/transfers/fixed bills/debt/savings
- matching precedence and one-budget-only behavior
- merchant/category/account include and exclude rules
- manual transaction assignment/exclusion overrides
- weekly, biweekly, monthly, yearly, custom, and paycheck-linked periods
- rollover modes
- current-period rebalancing
- rejection of invalid rebalance amounts
- projection and pace calculations
- smart budget suggestions
- active-period recalculation without altering closed-period snapshots
- serialization/sync of expanded budget data and related records

Existing transaction, Plaid merge, household sync, debt, bills, and signing tests must remain green.

## Release verification

Before claiming completion:

1. Confirm RED tests fail for the expected missing behavior.
2. Implement the minimum production changes to satisfy them.
3. Run Android unit tests and Worker regression checks in GitHub Actions.
4. Verify the stable signing certificate fingerprint remains unchanged.
5. Build and sign the next APK through GitHub Actions.
6. Confirm exact-head CI success.
7. Download the Actions artifact, extract the APK, compute its SHA-256, and provide the APK directly to the user.
