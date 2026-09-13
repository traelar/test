# BillNest Smart Finance Expansion Design

Date: 2026-09-13
Branch: `billnest-apk-build`
Status: Architecture approved; pending user review of this written spec

## Goal

Expand the existing BillNest Android app into a substantially smarter personal and household finance assistant without rewriting the current Plaid, household, savings, debt, budget, transaction, signing, or Cloudflare foundations.

The expansion should reduce manual cleanup after bank sync, improve cash-flow awareness, add better debt and recurring-charge intelligence, support household privacy, add synced receipts, and expose key information through Android widgets.

## Non-negotiable constraints

- Keep the current Android package `com.baylee.billnest`.
- Preserve working Plaid Production support.
- Preserve the current Cloudflare Worker + D1 backend architecture.
- Preserve the stable BillNest signing certificate so APK upgrades continue to install over previous builds.
- Continue producing APKs through GitHub Actions; do not require Android Studio for release builds.
- Every release must pass Worker syntax/tests, Android unit tests, stable signing verification, APK build, APK certificate verification, and artifact upload before it is called complete.
- Keep the app dark-only and avoid white UI surfaces.
- Do not hardcode personal financial values.
- Keep existing paycheck calculator behavior intact unless a later requirement explicitly changes it.
- Plaid remains authoritative for bank-synced financial fields; BillNest-owned metadata may be edited and synced separately.
- Existing households remain shared by default. Privacy is opt-in per account/transaction.

## Architectural direction

Use a modular staged expansion of the existing app rather than a rewrite.

New capability should be split into focused model/engine/UI files rather than continuing to grow large mixed-responsibility files such as `Alpha19FinanceScreens.kt` or `MainActivity.kt`.

Core modules:

1. Review Inbox and transaction-quality engine
2. Merchant aliases and transaction rules
3. Recurring bill/subscription intelligence
4. Credit-card statement intelligence
5. Cash-flow timeline and warnings
6. Monthly reports and what-if simulation
7. Household privacy and permissions
8. Receipt/photo attachments with Cloudflare R2
9. Android home-screen widgets

Each module owns its models/calculations and exposes stable functions to UI/ViewModel code.

## 1. Financial Review Inbox

Add a Review Inbox that surfaces transactions and account events requiring user attention after Plaid/manual sync.

Review item types:

- Uncategorized or low-confidence category
- New/unknown merchant
- Possible transfer
- Possible income/paycheck
- Possible recurring subscription/bill
- Potential duplicate charge
- Category conflict with existing merchant history
- Large/unusual amount relative to normal merchant history
- Price increase on recurring charge
- Account or debt metadata missing information BillNest needs

Each item should explain why BillNest flagged it and provide one-tap resolution actions where practical.

Resolution may update only the current transaction or create/update a reusable rule. The user must be able to preview the effect of a rule before saving it.

Resolved items should not repeatedly return unless new evidence materially changes the situation.

## 2. Merchant Manager and stronger transaction rules

Add a persistent merchant alias layer so ugly/raw bank merchant names can map to a clean BillNest merchant identity.

Merchant records should support:

- Canonical display name
- Known raw-name patterns/aliases
- Preferred category
- Optional default transaction classification
- Optional subscription/bill identity
- User-confirmed/automatic state
- Last-seen timestamp

Transaction rules should expand beyond simple merchant-text/category mapping.

Supported match conditions should include:

- Merchant/raw text contains
- Canonical merchant
- Account
- Amount range
- Existing category
- Transaction classification
- Direction/sign where useful

Supported actions should include:

- Rename merchant
- Assign category
- Mark as spending/income/transfer
- Exclude from spending
- Track/untrack recurring status

Rules require deterministic priority/order. Conflicting rules should show which rule wins.

Before saving a rule, show a preview of matching current transactions and the changes that will be applied.

## 3. Recurring bill and subscription intelligence

Extend the existing recurring detection rather than replace it.

BillNest should detect and surface:

- New likely recurring charges
- Monthly/yearly recurring patterns
- Price increases/decreases
- Missing expected recurring charge
- Duplicate same-period recurring charge
- Upcoming renewal
- Subscription that has not appeared for an expected period

Detection must not silently create tracked bills/subscriptions. Automatic detections should enter the Review Inbox until the user confirms them.

Confirmed recurring items should feed the cash-flow timeline.

## 4. Credit-card statement intelligence

Extend `Debt` for credit-card-specific metadata without breaking loan/mortgage/other debt types.

Credit-card fields should support:

- Statement balance
- Statement closing day/date
- Payment due day/date
- Minimum payment
- APR
- Credit limit
- Available credit
- Utilization percentage
- Optional last payment amount/date
- Optional manual “amount to avoid interest” override when issuer data is unavailable

BillNest should calculate estimated monthly interest and provide a clear payment target when sufficient data exists.

Plaid-connected card balances continue to refresh from Plaid while BillNest-owned APR, due date, statement metadata, and user overrides remain persistent.

## 5. Upcoming Money Timeline

Build a unified dated cash-flow projection from existing accounts, paydays, bills, confirmed subscriptions, planned savings contributions, and debt payments.

Each day can include multiple events and a projected running balance.

The timeline should answer:

- What money is expected in?
- What is expected out?
- What bills/subscriptions occur before the next paycheck?
- What is the projected spendable balance after each event?
- What planned savings/debt actions are included?

Do not pretend planned bank transfers execute automatically unless BillNest actually has an authorized transfer integration. Planned actions must be labeled as planned.

## 6. Cash-flow warnings

Warnings should be generated from the timeline and stored/displayed separately from raw transaction alerts.

Examples:

- Projected spendable balance below user safety buffer
- Bill due before next paycheck with insufficient projected cash
- Credit-card utilization above configured threshold
- Unusually large recurring-charge increase
- Savings contribution would push cash below buffer

Warnings should explain the underlying events and the projected date/amount.

Avoid noisy repeated alerts for unchanged conditions.

## 7. Monthly Financial Report

Generate a monthly report from authoritative BillNest data.

Include:

- Income
- Variable spending
- Bills
- Subscription spending
- Savings contributions
- Debt reduction/payments
- Net-worth movement when snapshots are available
- Largest category increases/decreases
- Merchant highlights
- Recurring price changes
- Comparison with previous month

Reports should remain viewable in-app and support export/share as a later presentation-layer feature without changing core calculations.

## 8. Purchase / What-If Simulator

Add a simulator that takes a hypothetical purchase/payment/savings move and reruns the cash-flow projection without mutating real data.

Show:

- Immediate spendable balance impact
- Lowest projected balance through the selected horizon
- Bills/paychecks occurring afterward
- Savings/debt-plan impact where relevant
- Whether the safety buffer is breached

The simulator must never save changes unless the user explicitly converts a scenario into a real BillNest record/action.

## 9. Household privacy and permissions

Existing households remain shared-by-default.

Add an optional privacy state to accounts and transactions.

Privacy rules:

- Shared is the default for existing and new items unless the user explicitly marks an item private.
- Private accounts are visible only to the owning household member.
- Transactions from private accounts inherit private visibility by default.
- Individual shared-account transactions may be marked private when allowed by the data model.
- Private transactions and their receipt attachments must not be exposed to other household members through API responses, reports, Insights, review items, exports, or widgets.

Add household permissions gradually and conservatively. Initial permissions should focus on whether a member can edit shared bills, debts, budgets, rules, and household settings. Do not turn BillNest into enterprise accounting software.

## 10. Receipt/photo attachments

Allow receipts/photos to be attached to transactions.

Storage design:

- Cloudflare R2 stores image/file bytes.
- D1 stores attachment metadata, transaction association, household/user ownership, privacy state, MIME type, size, timestamps, and R2 object key.
- Android compresses/re-encodes receipt images before upload to keep storage small.
- Backend must authorize upload/download/delete against the authenticated user, household membership, item ownership, and privacy state.
- Private transaction attachments remain private.
- Deleting a transaction should either delete or safely orphan/clean attachments according to an explicit cleanup path; no permanent unreferenced R2 accumulation.

R2 is preferred over D1 blobs because image bytes should not live in the relational database.

## 11. Android widgets

Add dark BillNest home-screen widgets after the underlying data modules stabilize.

Initial widgets:

- Spendable Now
- Upcoming Bills / Next Payday
- Savings Goal Progress
- Debt Progress

Widgets should read from a lightweight local snapshot produced by the app rather than performing direct network/Plaid calls from the widget process.

Privacy-sensitive household/private data must respect the signed-in local user and should avoid exposing private transaction details on the lock screen where Android configuration makes that relevant.

## Shared data and sync model

New household-scoped data should sync through the existing Worker/D1 system using the same authenticated household model already used by BillNest.

Likely new backend entities include:

- merchant_aliases
- transaction_rules_v2 or compatible extension of current rules
- review_items / review_resolution metadata
- recurring_profiles
- card_statement_metadata
- cashflow_preferences
- household_item_privacy / ownership fields
- attachment_metadata

Where possible, extend current entities instead of duplicating authoritative data.

Rules, aliases, categories, recurring confirmations, and shared metadata should be household-scoped unless they belong to a private account/transaction or explicitly user-scoped preference.

Migration behavior must be backward-compatible. Existing rows default to shared visibility and preserve current classifications/categories.

## Data-flow principles

Plaid sync flow:

Plaid data -> existing merge/reconciliation -> BillNest classification/alias/rule layer -> recurring/review analysis -> household privacy filter -> UI/Insights/timeline/report consumers.

Receipt flow:

Android compress/select image -> authenticated Worker upload request -> R2 object write -> D1 attachment metadata -> authorized retrieval through Worker-signed/controlled path.

What-if flow:

Current local AppData snapshot -> clone/scenario overlay -> cash-flow projection -> result UI. No backend write unless explicitly confirmed.

## UI direction

Keep the current premium dark visual language.

Do not create a giant new dashboard containing everything. Add focused entry points:

- Review Inbox should be reachable prominently from Dashboard/Transactions when attention is needed.
- Merchant Manager and Rules belong under transaction-management/settings territory.
- Cash-flow timeline can extend current planning/paycheck areas.
- Credit-card statement details belong in Debt.
- Monthly report and simulator fit naturally under Insights.
- Receipt actions live on transaction detail/editor flows.
- Household privacy controls live with account/transaction editors and Household settings.

Use internal scrolling, phone-safe action layouts, and shared components rather than bespoke picker/button implementations.

## Error handling and offline behavior

- Existing financial data remains usable if network sync fails.
- Review analysis and what-if calculations should run locally where practical.
- Failed receipt uploads should remain retryable without creating duplicate metadata.
- R2/D1 partial failures must be recoverable and idempotent.
- Rule previews must not mutate transactions.
- Backend migrations must be safe to rerun or guarded against duplicate application.

## Testing strategy

Every module requires model-level unit tests before UI wiring.

Required coverage includes:

- Rule matching/priority/conflicts
- Merchant normalization/aliases
- Review-item creation and resolution suppression
- Recurring detection and price changes
- Card utilization/interest/payment targets
- Cash-flow projection ordering and running balances
- Warning deduplication
- Monthly report totals
- What-if non-mutation
- Shared/private household filtering
- Attachment authorization metadata logic
- Existing Plaid merge behavior remains intact
- Existing category normalization remains intact

Backend tests must cover household authorization and privacy leakage prevention for new endpoints.

Release CI continues to verify Worker tests, Android tests, stable signing certificate, APK build, APK signing certificate, and artifact upload.

## Delivery sequence

### Stage A — Smart transaction foundation

Build Review Inbox, Merchant Manager, stronger rules, rule preview, and supporting sync models. This is the highest-leverage first stage because it improves every Plaid refresh and provides shared infrastructure for later recurring intelligence.

### Stage B — Recurring and card intelligence

Add recurring bill/subscription detections and expanded credit-card statement tracking.

### Stage C — Forecasting

Add unified cash-flow timeline, user safety buffer, and cash-flow warnings.

### Stage D — Analysis tools

Add monthly reports and what-if simulator using the forecasting engine.

### Stage E — Household privacy

Add shared-by-default item privacy, owner-aware filtering, and initial household edit permissions. Privacy filtering must be completed before synced private receipts are enabled.

### Stage F — Receipts and R2

Add R2 backend integration, attachment metadata, Android image compression/upload, authorization, viewing, and cleanup.

### Stage G — Widgets

Add Android home-screen widgets backed by safe local snapshots after the underlying data is stable.

Each stage should ship as one or more independently verified incremental APK releases rather than waiting for one enormous final release.

## Success criteria

The expansion is successful when:

- Plaid refreshes require substantially less manual transaction cleanup.
- Existing and newly added categories/merchant identities remain consistent across the app.
- Users can see and resolve uncertain financial data from one Review Inbox.
- Recurring charges and credit-card obligations are materially easier to understand.
- BillNest can explain future cash position day-by-day.
- Reports and what-if simulations reconcile with the same authoritative engines used elsewhere.
- Household-private data cannot leak to other members.
- Receipt images sync safely without bloating D1.
- Android widgets expose useful high-level data without destabilizing the app.
- Existing production Plaid, current finance logic, signing, and update-in-place behavior continue to work throughout the rollout.
