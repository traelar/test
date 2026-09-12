package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class BudgetEngineTest {
    private val referenceDate = LocalDate.of(2026, 9, 12)

    @Test
    fun calculatesWeeklyBiweeklyMonthlyYearlyAndCustomWindows() {
        assertEquals(
            BudgetPeriodWindow(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13)),
            budgetPeriodWindow(Budget(name = "Weekly", amount = 100.0, period = BudgetPeriod.WEEKLY), emptyList(), referenceDate)
        )
        assertEquals(
            BudgetPeriodWindow(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 13)),
            budgetPeriodWindow(Budget(name = "Biweekly", amount = 100.0, period = BudgetPeriod.BIWEEKLY), emptyList(), referenceDate)
        )
        assertEquals(
            BudgetPeriodWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
            budgetPeriodWindow(Budget(name = "Monthly", amount = 100.0, period = BudgetPeriod.MONTHLY), emptyList(), referenceDate)
        )
        assertEquals(
            BudgetPeriodWindow(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            budgetPeriodWindow(Budget(name = "Yearly", amount = 100.0, period = BudgetPeriod.YEARLY), emptyList(), referenceDate)
        )
        assertEquals(
            BudgetPeriodWindow(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 19)),
            budgetPeriodWindow(
                Budget(name = "Trip", amount = 100.0, period = BudgetPeriod.CUSTOM, startDateIso = "2026-09-05", endDateIso = "2026-09-19"),
                emptyList(),
                referenceDate
            )
        )
    }

    @Test
    fun paycheckBudgetUsesLinkedPaydayAsPeriodAnchor() {
        val payday = Payday(
            id = "work",
            label = "Work",
            amount = 1500.0,
            nextDateIso = "2026-09-18",
            frequency = Frequency.BIWEEKLY
        )
        val budget = Budget(
            name = "Paycheck groceries",
            amount = 300.0,
            period = BudgetPeriod.PAYCHECK,
            paydayId = payday.id
        )

        assertEquals(
            BudgetPeriodWindow(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 17)),
            budgetPeriodWindow(budget, listOf(payday), referenceDate)
        )
    }

    @Test
    fun invalidCustomAndMissingPaydayWindowsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            budgetPeriodWindow(
                Budget(name = "Bad", amount = 100.0, period = BudgetPeriod.CUSTOM, startDateIso = "2026-09-20", endDateIso = "2026-09-10"),
                emptyList(),
                referenceDate
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            budgetPeriodWindow(Budget(name = "Paycheck", amount = 100.0, period = BudgetPeriod.PAYCHECK), emptyList(), referenceDate)
        }
    }

    @Test
    fun variableSpendingExcludesIncomeTransfersDebtSavingsAndFixedBills() {
        val fixedBill = Bill(name = "Electric Company", amount = 105.0, dueDateIso = "2026-09-10")
        val eligible = FinanceTransaction(id = "groceries", name = "Aldi", amount = 80.0, dateIso = "2026-09-09", category = "Groceries")
        val income = eligible.copy(id = "income", name = "Payroll", income = true, category = "Income")
        val transfer = eligible.copy(id = "transfer", name = "Transfer", transfer = true, category = "Transfer")
        val debt = eligible.copy(id = "debt", name = "Card payment", category = "Debt Payment")
        val savings = eligible.copy(id = "savings", name = "Savings", category = "Savings")
        val billPayment = eligible.copy(id = "bill", name = "Electric Company Payment", amount = 105.0, category = "Utilities")
        val data = AppData(
            bills = listOf(fixedBill),
            transactions = listOf(eligible, income, transfer, debt, savings, billPayment)
        )

        val ids = eligibleVariableSpendingTransactions(data, referenceDate.minusMonths(1), referenceDate).map { it.id }

        assertEquals(listOf("groceries"), ids)
    }

    @Test
    fun matchingUsesManualThenMerchantThenCategoryThenAccountAndAssignsOnlyOnce() {
        val checking = Account(id = "checking", name = "Checking")
        val groceries = Budget(
            id = "groceries",
            name = "Groceries",
            amount = 600.0,
            includedCategories = listOf("Groceries")
        )
        val walmart = Budget(
            id = "walmart",
            name = "Walmart",
            amount = 200.0,
            includedMerchants = listOf("Walmart")
        )
        val checkingBudget = Budget(
            id = "checking-budget",
            name = "Checking spending",
            amount = 100.0,
            includedAccountIds = listOf(checking.id)
        )
        val row = FinanceTransaction(
            id = "tx",
            name = "Walmart Supercenter",
            amount = 55.0,
            dateIso = "2026-09-08",
            category = "Groceries",
            accountId = checking.id
        )
        val data = AppData(accounts = listOf(checking), transactions = listOf(row), budgets = listOf(groceries, walmart, checkingBudget))

        val automatic = resolveBudgetAssignments(data, referenceDate)
        assertEquals(1, automatic.size)
        assertEquals("walmart", automatic.single().budgetId)
        assertEquals("Merchant rule", automatic.single().reason)

        val manuallyAssigned = assignTransactionToBudget(data, row.id, groceries.id, referenceDate)
        val manual = resolveBudgetAssignments(manuallyAssigned, referenceDate)
        assertEquals("groceries", manual.single().budgetId)
        assertEquals("Manual assignment", manual.single().reason)
    }

    @Test
    fun includeAndExcludeRulesRespectMerchantCategoryAndAccountVetoes() {
        val budget = Budget(
            id = "food",
            name = "Food",
            amount = 500.0,
            includedCategories = listOf("Groceries"),
            excludedCategories = listOf("Pharmacy"),
            includedMerchants = listOf("Walmart"),
            excludedMerchants = listOf("Walmart Pharmacy"),
            includedAccountIds = listOf("checking"),
            excludedAccountIds = listOf("business")
        )
        val included = FinanceTransaction(id = "included", name = "Walmart", amount = 40.0, dateIso = "2026-09-10", category = "Groceries", accountId = "checking")
        val merchantExcluded = included.copy(id = "merchant-excluded", name = "Walmart Pharmacy")
        val categoryExcluded = included.copy(id = "category-excluded", name = "Other Store", category = "Pharmacy")
        val accountExcluded = included.copy(id = "account-excluded", name = "Walmart", accountId = "business")
        val data = AppData(transactions = listOf(included, merchantExcluded, categoryExcluded, accountExcluded), budgets = listOf(budget))

        assertEquals(listOf("included"), resolveBudgetAssignments(data, referenceDate).map { it.transactionId })
    }

    @Test
    fun manualExclusionWinsForCurrentPeriodOnly() {
        val budget = Budget(id = "groceries", name = "Groceries", amount = 500.0, includedCategories = listOf("Groceries"))
        val row = FinanceTransaction(id = "tx", name = "Aldi", amount = 40.0, dateIso = "2026-09-10", category = "Groceries")
        val oldOverride = BudgetTransactionOverride(
            id = "old",
            transactionId = row.id,
            budgetId = null,
            periodStartIso = "2026-08-01",
            action = BudgetOverrideAction.EXCLUDE
        )
        val data = AppData(transactions = listOf(row), budgets = listOf(budget), budgetTransactionOverrides = listOf(oldOverride))
        assertEquals(1, resolveBudgetAssignments(data, referenceDate).size)

        val excludedNow = assignTransactionToBudget(data, row.id, null, referenceDate)
        assertTrue(resolveBudgetAssignments(excludedNow, referenceDate).isEmpty())
    }

    @Test
    fun currentPeriodRebalanceMovesAllocationWithoutChangingTotalPlan() {
        val groceries = Budget(id = "groceries", name = "Groceries", amount = 600.0)
        val dining = Budget(id = "dining", name = "Dining", amount = 200.0)
        val data = AppData(budgets = listOf(groceries, dining))

        val moved = moveBudgetMoney(data, dining.id, groceries.id, 50.0, referenceDate)
        val grocerySummary = calculateBudgetSummary(moved, groceries, referenceDate)
        val diningSummary = calculateBudgetSummary(moved, dining, referenceDate)

        assertEquals(650.0, grocerySummary.effectiveAmount, 0.001)
        assertEquals(150.0, diningSummary.effectiveAmount, 0.001)
        assertEquals(800.0, grocerySummary.effectiveAmount + diningSummary.effectiveAmount, 0.001)
    }

    @Test
    fun invalidRebalanceAmountsAreRejected() {
        val groceries = Budget(id = "groceries", name = "Groceries", amount = 600.0)
        val dining = Budget(id = "dining", name = "Dining", amount = 200.0)
        val data = AppData(budgets = listOf(groceries, dining))

        assertThrows(IllegalArgumentException::class.java) { moveBudgetMoney(data, dining.id, groceries.id, 0.0, referenceDate) }
        assertThrows(IllegalArgumentException::class.java) { moveBudgetMoney(data, dining.id, groceries.id, 250.0, referenceDate) }
    }

    @Test
    fun summaryCalculatesSpentRemainingDailyAllowanceProjectionAndPace() {
        val budget = Budget(id = "groceries", name = "Groceries", amount = 600.0, includedCategories = listOf("Groceries"), warningPercent = 90)
        val rows = listOf(
            FinanceTransaction(id = "a", name = "Aldi", amount = 100.0, dateIso = "2026-09-03", category = "Groceries"),
            FinanceTransaction(id = "b", name = "Festival", amount = 80.0, dateIso = "2026-09-10", category = "Groceries")
        )
        val summary = calculateBudgetSummary(AppData(transactions = rows, budgets = listOf(budget)), budget, referenceDate)

        assertEquals(180.0, summary.spent, 0.001)
        assertEquals(420.0, summary.remaining, 0.001)
        assertEquals(18, summary.daysRemaining)
        assertTrue(summary.dailyAllowance > 0.0)
        assertTrue(summary.projectedSpend >= summary.spent)
        assertTrue(summary.percentUsed > 0.0)
    }

    @Test
    fun rolloverCanCarryUnusedOrOverspentBalanceFromPreviousPeriod() {
        val carryUnused = Budget(
            id = "unused",
            name = "Groceries",
            amount = 500.0,
            includedCategories = listOf("Groceries"),
            rolloverMode = BudgetRolloverMode.CARRY_UNUSED
        )
        val previousSpend = FinanceTransaction(id = "aug", name = "Aldi", amount = 300.0, dateIso = "2026-08-10", category = "Groceries")
        val unusedSummary = calculateBudgetSummary(AppData(transactions = listOf(previousSpend), budgets = listOf(carryUnused)), carryUnused, referenceDate)
        assertEquals(700.0, unusedSummary.effectiveAmount, 0.001)

        val carryBalance = carryUnused.copy(id = "balance", rolloverMode = BudgetRolloverMode.CARRY_BALANCE)
        val overspent = previousSpend.copy(id = "overspent", amount = 650.0)
        val balanceSummary = calculateBudgetSummary(AppData(transactions = listOf(overspent), budgets = listOf(carryBalance)), carryBalance, referenceDate)
        assertEquals(350.0, balanceSummary.effectiveAmount, 0.001)
    }

    @Test
    fun variableSpendingSummaryTracksBudgetedAndUnbudgetedSpending() {
        val budget = Budget(id = "groceries", name = "Groceries", amount = 500.0, includedCategories = listOf("Groceries"))
        val rows = listOf(
            FinanceTransaction(id = "food", name = "Aldi", amount = 100.0, dateIso = "2026-09-10", category = "Groceries"),
            FinanceTransaction(id = "shop", name = "Store", amount = 50.0, dateIso = "2026-09-10", category = "Shopping")
        )
        val summary = calculateVariableSpendingSummary(AppData(transactions = rows, budgets = listOf(budget)), referenceDate)

        assertEquals(500.0, summary.planned, 0.001)
        assertEquals(100.0, summary.budgetedSpent, 0.001)
        assertEquals(50.0, summary.unbudgetedSpent, 0.001)
        assertEquals(400.0, summary.remaining, 0.001)
    }

    @Test
    fun smartSuggestionsUseRecentEligibleSpendingAndIgnoreFixedMovement() {
        val rows = listOf(
            FinanceTransaction(id = "sep", name = "Aldi", amount = 400.0, dateIso = "2026-09-01", category = "Groceries"),
            FinanceTransaction(id = "aug", name = "Aldi", amount = 500.0, dateIso = "2026-08-01", category = "Groceries"),
            FinanceTransaction(id = "jul", name = "Aldi", amount = 600.0, dateIso = "2026-07-01", category = "Groceries"),
            FinanceTransaction(id = "transfer", name = "Savings transfer", amount = 1000.0, dateIso = "2026-09-02", category = "Savings", transfer = true)
        )

        val suggestion = suggestBudgets(AppData(transactions = rows), referenceDate).single { it.category == "Groceries" }

        assertEquals(400.0, suggestion.last30Days, 0.001)
        assertEquals(500.0, suggestion.recentMonthlyAverage, 0.001)
        assertEquals(400.0, suggestion.recommendedAmount, 0.001)
    }
}
