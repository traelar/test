# BillNest Navigation + Account Totals Amendment

Date: 2026-09-11
Status: Approved
Amends: `docs/superpowers/specs/2026-09-11-billnest-full-finance-household-design.md`

## Purpose

This amendment locks the user-approved navigation and account-balance behavior for BillNest v2. Where this document conflicts with the original design, this amendment wins.

## Navigation

- Remove the six-item bottom navigation bar.
- Use a Material3 left navigation drawer opened from a clearly visible hamburger/menu button in the top app bar.
- Use full, readable labels; do not abbreviate labels to `Accts`, `Cal`, or similar.
- Primary drawer destinations are: Dashboard, Accounts, Transactions, Bills, Budgets, Debt, Goals, Income, Calendar, Settings.
- Destinations that are not implemented in the current delivery slice may render a polished coming-soon/empty state only until their dedicated implementation task lands; navigation structure itself should be stable.
- No drawer labels may wrap, clip, or require tiny text on the target phone size.

## Account Ordering

- Every account has a persistent `displayOrder` integer.
- Accounts are shown in ascending `displayOrder`, with stable ID as a final deterministic tiebreaker.
- Users can change account order directly from the Accounts screen using simple Move Up / Move Down controls in the first implementation slice; a drag handle may replace these controls later if it is equally accessible and reliable.
- Reordering is shared household data and syncs to every household member.

## Account Role and Financial Inclusion

Each account has a role:

- `SPENDING`
- `SAVINGS`
- `CREDIT_DEBT`
- `OTHER`

Each account also has two independent inclusion flags:

- `includeInTotalMoney`: whether the account contributes to the household Total Money figure.
- `includeInSpendingMoney`: whether the account contributes to Spending Money / Safe-to-Spend / bills-before-payday calculations.

Defaults:

- Checking/manual cash-like spending accounts: role `SPENDING`, included in both totals.
- Savings/money-market accounts: role `SAVINGS`, included in Total Money but excluded from Spending Money.
- Credit/loan accounts: role `CREDIT_DEBT`, excluded from cash totals; debt balances are presented in debt/credit views instead.
- Other accounts: explicit user choice; conservative default is excluded from Spending Money.

These flags control calculations without hiding the account. A savings account can remain visible in Accounts and Total Money while not inflating day-to-day available spending.

## Dashboard Money Figures

The fixed dashboard must distinguish at least:

- `Total Money`: sum of eligible positive cash/depository/manual account balances where `includeInTotalMoney == true`.
- `Spending Money`: sum of eligible account balances where `includeInSpendingMoney == true`.
- `Savings`: sum of accounts with role `SAVINGS` that are included in Total Money.
- `Available After Upcoming Bills`: Spending Money minus relevant unpaid upcoming obligations for the selected forecast window.

The app must not use a single all-account total as the basis for safe-to-spend calculations.

## Household Sharing

Account order, role, inclusion flags, display name, and owner label are shared household metadata. Both household members may edit normal account presentation/inclusion metadata. Existing owner-only bank disconnection remains unchanged.

## Migration

Existing v1.x accounts migrate deterministically:

- `AccountType.CHECKING` -> `SPENDING`, included in Total Money + Spending Money.
- `AccountType.SAVINGS` -> `SAVINGS`, included in Total Money, excluded from Spending Money.
- `AccountType.CASH` -> `SPENDING`, included in both.
- Plaid `depository/checking` -> `SPENDING`, included in both.
- Plaid `depository/savings` and `money market` -> `SAVINGS`, Total Money only.
- unsupported/other -> `OTHER`, Total Money included only when it represents a positive cash balance; Spending Money false by default.

Existing account list order becomes initial `displayOrder` so the upgrade does not visibly scramble accounts.

## Verification

Tests must cover:

- savings excluded from Spending Money but included in Total Money;
- credit/debt excluded from cash totals;
- account ordering survives persistence/sync;
- account reorder metadata is household-scoped;
- drawer contains full labels and no bottom-navigation implementation remains in the v2 app shell;
- migration assigns deterministic role/inclusion defaults.
