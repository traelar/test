# BillNest Reserved Funds + Mortgage Plan Amendment

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manually configured Mortgage debt type and generic Reserved Funds that affect spendable/free balances without hard-coding any household-specific amount or schedule.

**Architecture:** Reserved Funds are shared household records layered on top of real account balances. They never move bank money; they only earmark portions of balances for BillNest calculations. Mortgage is a normal debt type that may optionally link to a Bill, payment account, and Reserved Fund.

**Spec:** `docs/superpowers/specs/2026-09-11-billnest-reserved-funds-mortgage-amendment.md`

## Constraints

- Never seed `$1,060` or any other personal mortgage amount.
- Never auto-create a mortgage or reserve.
- Reserved money remains in Total Money but is excluded from applicable spendable/free figures.
- Internal transfers are not expenses.
- Mortgage/reserve linking is optional and user-configured.
- All records sync as shared household data.

---

### Task R1: Reserved Fund models and math

**Files:**
- Create/modify domain model files for `ReservedFund` and `ContributionRule`.
- Modify `FinancialTotals.kt`.
- Add focused unit tests.

**Interfaces:**
- `ReservedFund(id, name, accountId, currentAmount, targetAmount, contributionRule, linkedBillId, linkedDebtId, active, notes, ...)`.
- `ContributionRule` supports manual, payday-fixed, weekly, biweekly, monthly, and normal custom recurrence.
- `FinancialTotals` adds `reservedMoney` and `freeSavings`.

- [ ] Write failing tests proving reserve creation is explicit, Total Money stays unchanged, Spending Money/Free Savings subtract applicable reserves, and reserve amounts are capped by underlying balance for availability calculations.
- [ ] Verify focused tests fail.
- [ ] Implement minimal models and pure calculations.
- [ ] Verify focused and full Android tests pass.

### Task R2: Mortgage as a first-class debt type

**Files:**
- Modify debt model/domain files from the debt-plan stage.
- Add mortgage-focused tests.

**Interfaces:**
- Add `DebtType.MORTGAGE`.
- Mortgage supports optional principal, APR, required payment, due date, original amount, term/start/end data, escrow, principal/interest breakdown, linked Bill, linked payment account, linked Reserved Fund, and notes.

- [ ] Write failing tests that no mortgage exists by default and an arbitrary manually entered mortgage round-trips through persistence/sync.
- [ ] Verify failure.
- [ ] Implement the type and optional fields.
- [ ] Verify tests pass.

### Task R3: Manual Reserved Funds UI

**Files:**
- Add Reserved Funds screen/editor under the full-label navigation drawer.
- Modify Accounts/Dashboard presentation to show Balance / Reserved / Free where applicable.

- [ ] Add navigation/model tests first.
- [ ] Implement Add Reserved Fund, edit, deactivate/delete, linked account, arbitrary contribution amount/frequency, and optional bill/debt link.
- [ ] Ensure no pre-filled personal amount or mortgage setup exists.
- [ ] Verify Android tests/build.

### Task R4: Mortgage payment and reserve reconciliation

**Files:**
- Extend transaction/bill matching domain from the transaction stage.
- Add tests for internal transfer and linked payment behavior.

- [ ] Write failing tests proving a checking-to-savings transfer used for a reserve is not spending.
- [ ] Write failing test proving a matched linked mortgage payment reduces the reserve exactly once and does not double-count the bill obligation.
- [ ] Implement deterministic reconciliation/audit records.
- [ ] Verify transaction, bill-match, reserve, and full suites pass.

### Task R5: Household sync

**Files:**
- Extend Android sync mapping and Worker record validation/tests.

- [ ] Add failing round-trip household tests for Mortgage and Reserved Fund records.
- [ ] Implement sync mapping and server-side household scoping.
- [ ] Verify two-member visibility and no private records.
- [ ] Run Worker tests, Android tests, and APK build.