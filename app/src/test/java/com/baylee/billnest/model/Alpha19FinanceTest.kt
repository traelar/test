package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class Alpha19FinanceTest {
    private val referenceDate = LocalDate.of(2026, 9, 12)

    @Test
    fun plaidRefreshPreservesExistingDebtMetadataAndEditedCreditLimit() {
        val linked = Account(
            id = "hidden-card-account",
            name = "Bank Card",
            type = AccountType.CREDIT,
            balance = 425.0,
            creditLimit = 2000.0,
            source = AccountSource.PLAID,
            plaidAccountId = "plaid-card"
        )
        val existing = Debt(
            id = "debt-1",
            name = "My Card",
            type = DebtType.CREDIT_CARD,
            balance = 500.0,
            apr = 24.9,
            minimumPayment = 75.0,
            dueDay = 17,
            creditLimit = 1500.0,
            plaidAccountId = "plaid-card"
        )

        val merged = mergePlaidCreditDebts(listOf(existing), listOf(linked)).single()

        assertEquals(existing.id, merged.id)
        assertEquals("My Card", merged.name)
        assertEquals(425.0, merged.balance, 0.001)
        assertEquals(1500.0, merged.creditLimit, 0.001)
        assertEquals(24.9, merged.apr, 0.001)
        assertEquals(75.0, merged.minimumPayment, 0.001)
        assertEquals(17, merged.dueDay)
    }

    @Test
    fun transactionRulesRenameCategorizeAndExcludeMerchantSpending() {
        val groceryRule = TransactionRule(
            id = "grocery-rule",
            merchantContains = "walmart",
            renameTo = "Walmart Grocery",
            category = "Groceries"
        )
        val pharmacyRule = TransactionRule(
            id = "pharmacy-rule",
            merchantContains = "walmart pharmacy",
            category = "Medical",
            excludeFromSpending = true,
            updatedAtEpochMs = groceryRule.updatedAtEpochMs + 1
        )
        val rows = listOf(
            FinanceTransaction(id = "food", name = "WALMART SUPERCENTER 123", amount = 80.0, dateIso = "2026-09-10", source = TransactionSource.PLAID),
            FinanceTransaction(id = "rx", name = "Walmart Pharmacy", amount = 25.0, dateIso = "2026-09-10", source = TransactionSource.PLAID)
        )

        val applied = applyTransactionRules(rows, listOf(groceryRule, pharmacyRule)).associateBy { it.id }

        assertEquals("Walmart Grocery", applied.getValue("food").name)
        assertEquals("Groceries", applied.getValue("food").category)
        assertFalse(applied.getValue("food").excludedFromSpending)
        assertEquals("Medical", applied.getValue("rx").category)
        assertTrue(applied.getValue("rx").excludedFromSpending)
    }

    @Test
    fun plaidDeletionCreatesHouseholdTombstoneAndRefreshCannotRestoreIt() {
        val row = FinanceTransaction(id = "plaid-tx", name = "Test deposit", amount = -50.0, dateIso = "2026-09-10", source = TransactionSource.PLAID)
        val deleted = deleteFinanceTransaction(AppData(transactions = listOf(row)), row.id)

        assertEquals(row.id, deleted.transactionTombstones.single().transactionId)
        assertTrue(row.id in deleted.deletedPlaidTransactionIds)

        val refreshed = mergePlaidTransactions(
            existing = deleted.transactions,
            incoming = listOf(row),
            deletedPlaidTransactionIds = deleted.transactionTombstones.mapTo(mutableSetOf()) { it.transactionId }
        )
        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun spendingExclusionRemovesTransactionFromVariableBudgets() {
        val included = FinanceTransaction(id = "included", name = "Aldi", amount = 50.0, dateIso = "2026-09-10", category = "Groceries")
        val excluded = FinanceTransaction(id = "excluded", name = "Special purchase", amount = 100.0, dateIso = "2026-09-10", category = "Groceries", excludedFromSpending = true)
        val data = AppData(transactions = listOf(included, excluded))

        assertEquals(listOf("included"), eligibleVariableSpendingTransactions(data, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).map { it.id })
    }

    @Test
    fun budgetHistoryReturnsRecentPeriodsAndAlertsWarnOnFastSpending() {
        val budget = Budget(id = "food", name = "Groceries", amount = 500.0, includedCategories = listOf("Groceries"), warningPercent = 90)
        val data = AppData(
            budgets = listOf(budget),
            transactions = listOf(
                FinanceTransaction(id = "jul", name = "Aldi", amount = 300.0, dateIso = "2026-07-10", category = "Groceries"),
                FinanceTransaction(id = "aug", name = "Aldi", amount = 400.0, dateIso = "2026-08-10", category = "Groceries"),
                FinanceTransaction(id = "sep", name = "Aldi", amount = 480.0, dateIso = "2026-09-10", category = "Groceries")
            )
        )

        val history = budgetHistory(data, budget, periods = 3, referenceDate = referenceDate)
        assertEquals(
            listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 1)),
            history.map { it.window.start }
        )
        assertEquals(listOf(480.0, 400.0, 300.0), history.map { it.spent })

        val alerts = budgetAlerts(data, referenceDate)
        assertEquals("food", alerts.single().budgetId)
        assertTrue(alerts.single().message.isNotBlank())
    }

    @Test
    fun monthlyRecapComparesVariableSpendingWithoutTransfersOrExcludedRows() {
        val data = AppData(transactions = listOf(
            FinanceTransaction(id = "aug", name = "August groceries", amount = 500.0, dateIso = "2026-08-10", category = "Groceries"),
            FinanceTransaction(id = "sep", name = "September groceries", amount = 350.0, dateIso = "2026-09-10", category = "Groceries"),
            FinanceTransaction(id = "transfer", name = "Move money", amount = 1000.0, dateIso = "2026-09-11", transfer = true, category = "Transfer"),
            FinanceTransaction(id = "excluded", name = "Ignored", amount = 200.0, dateIso = "2026-09-11", category = "Shopping", excludedFromSpending = true)
        ))

        val recap = monthlyRecap(data, YearMonth.of(2026, 9))

        assertEquals(350.0, recap.spending, 0.001)
        assertEquals(500.0, recap.priorMonthSpending, 0.001)
        assertEquals(-150.0, recap.spendingDelta, 0.001)
        assertEquals("Groceries", recap.topCategory)
    }

    @Test
    fun financialSnapshotsReplaceSameDayAndNetWorthHistoryUsesLatestMonthSnapshot() {
        val initial = AppData(
            accounts = listOf(Account(name = "Checking", balance = 2000.0, role = AccountRole.SPENDING)),
            debts = listOf(Debt(name = "Card", type = DebtType.CREDIT_CARD, balance = 500.0))
        )
        val first = captureFinancialSnapshot(initial, LocalDate.of(2026, 8, 31))
        val changed = first.copy(
            accounts = listOf(Account(name = "Checking", balance = 2500.0, role = AccountRole.SPENDING)),
            debts = listOf(Debt(name = "Card", type = DebtType.CREDIT_CARD, balance = 400.0))
        )
        val augustUpdated = captureFinancialSnapshot(changed, LocalDate.of(2026, 8, 31))
        val september = captureFinancialSnapshot(augustUpdated, LocalDate.of(2026, 9, 12))

        assertEquals(2, september.financialSnapshots.size)
        val history = netWorthHistory(september)
        assertEquals(2, history.size)
        assertEquals(2100.0, history.first { it.month == YearMonth.of(2026, 8) }.netWorth, 0.001)
        assertEquals(2100.0, history.first { it.month == YearMonth.of(2026, 9) }.netWorth, 0.001)
    }

    @Test
    fun detectsLinkedDebtPaymentFromTransferToCreditAccount() {
        val cardAccount = Account(id = "card-account", name = "Card", type = AccountType.CREDIT, source = AccountSource.PLAID, plaidAccountId = "card-plaid")
        val debt = Debt(id = "debt", name = "Card", type = DebtType.CREDIT_CARD, balance = 1000.0, minimumPayment = 50.0, plaidAccountId = "card-plaid")
        val payment = FinanceTransaction(
            id = "payment",
            name = "Card payment",
            amount = 250.0,
            dateIso = "2026-09-08",
            transfer = true,
            transferFromAccountId = "checking",
            transferToAccountId = cardAccount.id
        )
        val result = detectDebtPayments(AppData(accounts = listOf(cardAccount), debts = listOf(debt), transactions = listOf(payment)), referenceDate)

        assertEquals(1, result.size)
        assertEquals(debt.id, result.single().debtId)
        assertEquals(250.0, result.single().amount, 0.001)
    }

    @Test
    fun paycheckPlanCombinesBillsContributionsDebtBudgetsAndUnassignedMoney() {
        val payday = Payday(id = "pay", label = "Work", amount = 1800.0, nextDateIso = "2026-09-18", frequency = Frequency.BIWEEKLY)
        val data = AppData(
            paydays = listOf(payday),
            bills = listOf(Bill(name = "Rent", amount = 500.0, dueDateIso = "2026-09-25")),
            budgets = listOf(Budget(name = "Groceries", amount = 300.0, period = BudgetPeriod.PAYCHECK, paydayId = payday.id, includedCategories = listOf("Groceries"))),
            reservedFunds = listOf(ReservedFund(name = "Insurance", amount = 0.0, paydayContribution = 100.0)),
            savingsGoals = listOf(SavingsGoal(name = "Emergency", targetAmount = 5000.0, paydayContribution = 50.0)),
            debts = listOf(Debt(name = "Card", type = DebtType.CREDIT_CARD, balance = 1000.0, minimumPayment = 100.0))
        )

        val plan = calculateNextPaycheckPlan(data, referenceDate)!!

        assertEquals(payday.id, plan.paydayId)
        assertEquals(1800.0, plan.income, 0.001)
        assertEquals(500.0, plan.bills, 0.001)
        assertEquals(100.0, plan.reserves, 0.001)
        assertEquals(50.0, plan.savings, 0.001)
        assertEquals(300.0, plan.variableBudgets, 0.001)
        assertTrue(plan.debtMinimums > 0.0)
        assertTrue(plan.unassigned < plan.income)
    }
}
