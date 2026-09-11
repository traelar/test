# BillNest Reserved Funds + Mortgage Amendment

Date: 2026-09-11
Status: Approved
Amends: `docs/superpowers/specs/2026-09-11-billnest-full-finance-household-design.md`

## Purpose

Add configurable Reserved Funds and a dedicated Mortgage finance type without embedding the user's personal mortgage amount, account choices, or schedule into the application.

## No Personal Hard-Coding

BillNest must not automatically create a mortgage, reserve, savings rule, account mapping, or `$1,060` contribution. The application ships only the generic capability. Every household configures its own values manually.

The user's current mortgage workflow is a product example only and must never appear as seeded production data.

## Mortgage Type

`MORTGAGE` is a first-class debt/account option alongside other debt types such as credit card, auto loan, student loan, personal loan, and other debt.

A manually configured mortgage can store, where provided by the user:

- display name;
- current principal balance;
- APR / interest rate;
- required payment;
- payment due date;
- original loan amount;
- optional loan start/end or term information;
- optional escrow portion;
- optional principal/interest breakdown;
- optional linked Bill record;
- optional linked payment account;
- optional linked Reserved Fund;
- notes.

Mortgage fields remain editable and optional where the household does not know the value. BillNest must not invent lender data or mortgage terms.

## Reserved Funds

Reserved Funds are configurable household envelopes that earmark money already held in one or more cash/depository accounts.

A Reserved Fund has:

- stable ID;
- household ID;
- name;
- optional linked account ID;
- current reserved amount;
- optional target amount;
- contribution rule;
- optional linked bill or debt ID;
- optional notes;
- active/inactive state;
- sync/version metadata.

Supported contribution rules must include:

- manual only;
- fixed amount per payday;
- fixed amount weekly;
- fixed amount biweekly;
- fixed amount monthly;
- custom recurring schedule where supported by the normal recurrence engine.

Creating a Reserved Fund never moves real bank money by itself. It is an accounting designation inside BillNest unless a future banking-transfer feature is explicitly designed and approved.

## Money Calculations

Reserved money is still owned by the household, so it remains in `Total Money` when its underlying account is included in Total Money.

Reserved money is not freely spendable. Calculations must distinguish:

- `Total Money`: total included cash/depository balances before reserve subtraction;
- `Reserved Money`: active reserved amounts, capped so reserves cannot make an account appear to contain more cash than its actual included balance;
- `Spending Money`: spendable-account balances after subtracting reserved amounts that belong to spendable accounts;
- `Savings`: included savings balances;
- `Free Savings`: savings balances minus active reserved amounts assigned to savings accounts;
- `Available After Upcoming Bills`: Spending Money minus relevant upcoming unpaid obligations, without double-counting obligations already represented by an active linked reserve.

The UI must make the relationship visible. Example presentation: an account may show `Balance`, `Reserved`, and `Free` values rather than implying the full balance is available.

## Mortgage Reserve Workflow

A household may manually create a Reserved Fund, choose a savings account, set a contribution amount/frequency, and optionally link it to a Mortgage bill/debt.

If a linked mortgage payment transaction is later matched to the mortgage bill, BillNest may reduce/consume the associated reserve as part of the same auditable payment event. This linkage is optional and user-configured.

Transfers between household accounts that fund a reserve are internal transfers, not expenses. The reserve designation itself also is not spending.

## Household Sharing

Reserved Funds, mortgage definitions, contribution rules, links, and balances are shared household data. There are no private reserves under the current approved household model.

## Verification

Tests must prove:

- no mortgage or reserve is created automatically for a new household;
- a user can manually create a Mortgage debt;
- a user can manually create a Reserved Fund with arbitrary amount and schedule;
- Total Money does not decrease when money becomes reserved;
- Spending Money / Free Savings exclude applicable reserved balances;
- internal transfers used to fund reserves are not treated as spending;
- a linked mortgage payment consumes the reserve once and does not double-count the payment;
- reserved configuration syncs across household members;
- no test fixture or production seed relies on the user's personal `$1,060` value.