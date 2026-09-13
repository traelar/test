package com.baylee.billnest.model

import org.junit.Assert.*
import org.junit.Test

class Alpha25PendingAndPlanningTest {
    @Test
    fun pendingPlaidRowsDoNotCountAsIncomeOrVariableSpending() {
        val pendingIncome = FinanceTransaction(
            id = "plaid:pending-income",
            name = "Payroll",
            amount = -1000.0,
            dateIso = "2026-09-12",
            source = TransactionSource.PLAID,
            income = true,
            pending = true
        )
        val pendingSpend = FinanceTransaction(
            id = "plaid:pending-spend",
            name = "Store",
            amount = 50.0,
            dateIso = "2026-09-12",
            source = TransactionSource.PLAID,
            category = "Groceries",
            pending = true
        )

        assertTrue(visibleIncomeTransactions(listOf(pendingIncome)).isEmpty())
        assertFalse(isVariableSpendingForInsights(pendingSpend))
    }

    @Test
    fun missingPendingPlaidRowIsDroppedWhenRefreshNoLongerReturnsIt() {
        val pending = FinanceTransaction(
            id = "plaid:pending",
            name = "Pending store",
            amount = 20.0,
            dateIso = "2026-09-12",
            source = TransactionSource.PLAID,
            pending = true
        )
        val merged = mergePlaidTransactions(listOf(pending), emptyList())
        assertTrue(merged.isEmpty())
    }

    @Test
    fun manualSplitSurvivesPlaidRefresh() {
        val saved = FinanceTransaction(
            id = "plaid:split",
            name = "Target",
            amount = 100.0,
            dateIso = "2026-09-12",
            source = TransactionSource.PLAID,
            category = "Other",
            userClassificationOverride = true,
            splits = listOf(
                TransactionSplit(category = "Groceries", amount = 60.0),
                TransactionSplit(category = "Kids", amount = 40.0)
            )
        )
        val fresh = saved.copy(category = "General merchandise", userClassificationOverride = false, splits = null)
        val merged = mergePlaidTransactions(listOf(saved), listOf(fresh)).single()

        assertEquals(2, merged.splits.orEmpty().size)
        assertEquals(60.0, merged.splits.orEmpty().first().amount, 0.001)
    }

    @Test
    fun savingsPriorityOrdersEmergencyBeforeNormalAndLow() {
        val rows = listOf(
            SavingsGoal(name = "Vacation", targetAmount = 1000.0, priority = SavingsPriority.LOW),
            SavingsGoal(name = "Emergency", targetAmount = 3000.0, priority = SavingsPriority.EMERGENCY),
            SavingsGoal(name = "Car", targetAmount = 1500.0, priority = SavingsPriority.NORMAL)
        )
        assertEquals(listOf("Emergency", "Car", "Vacation"), recommendedSavingsOrder(rows).map { it.name })
    }

    @Test
    fun plannedSavingsRouteStillCountsInPaycheckAllocation() {
        val data = AppData(
            paydays = listOf(Payday(id = "pay", label = "Work", amount = 1000.0, nextDateIso = "2026-09-18")),
            savingsGoals = listOf(
                SavingsGoal(
                    name = "Emergency",
                    targetAmount = 5000.0,
                    paydayContribution = 100.0,
                    transferFromAccountId = "checking",
                    transferToAccountId = "savings"
                )
            )
        )
        val plan = calculateNextPaycheckPlan(data, java.time.LocalDate.of(2026, 9, 12))!!
        assertEquals(100.0, plan.savings, 0.001)
        assertEquals(900.0, plan.unassigned, 0.001)
    }
}
