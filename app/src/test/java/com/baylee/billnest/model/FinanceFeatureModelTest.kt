package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceFeatureModelTest {
    @Test
    fun `mortgage is a dedicated debt type`() {
        assertTrue(DebtType.entries.contains(DebtType.MORTGAGE))
    }

    @Test
    fun `budgets support every requested cadence`() {
        assertEquals(
            setOf(BudgetPeriod.WEEKLY, BudgetPeriod.BIWEEKLY, BudgetPeriod.MONTHLY, BudgetPeriod.YEARLY, BudgetPeriod.CUSTOM),
            BudgetPeriod.entries.toSet()
        )
    }

    @Test
    fun `reserved savings stays in total money but not free savings`() {
        val checking = Account(id = "c", name = "Checking", type = AccountType.CHECKING, balance = 1200.0)
        val savings = Account(id = "s", name = "Savings", type = AccountType.SAVINGS, balance = 3500.0)
        val prefs = listOf(
            AccountPreference("manual:c", 0, AccountRole.SPENDING, true, true),
            AccountPreference("manual:s", 1, AccountRole.SAVINGS, true, false)
        )
        val fund = ReservedFund(
            name = "Mortgage Reserve",
            accountKey = "manual:s",
            reservedAmount = 2120.0,
            contributionPerPaycheck = 1060.0
        )

        val summary = CashPosition.calculate(listOf(checking, savings), prefs, listOf(fund))

        assertEquals(4700.0, summary.totalMoney, 0.001)
        assertEquals(1200.0, summary.spendingMoney, 0.001)
        assertEquals(2120.0, summary.reservedMoney, 0.001)
        assertEquals(1380.0, summary.freeSavings, 0.001)
        assertEquals(1200.0, summary.availableSpending, 0.001)
    }

    @Test
    fun `reserve inside a spending account reduces available spending`() {
        val checking = Account(id = "c", name = "Checking", type = AccountType.CHECKING, balance = 1500.0)
        val prefs = listOf(AccountPreference("manual:c", 0, AccountRole.SPENDING, true, true))
        val fund = ReservedFund(name = "Insurance", accountKey = "manual:c", reservedAmount = 400.0)

        val summary = CashPosition.calculate(listOf(checking), prefs, listOf(fund))

        assertEquals(1500.0, summary.spendingMoney, 0.001)
        assertEquals(1100.0, summary.availableSpending, 0.001)
    }

    @Test
    fun `snowball and avalanche produce different priority ordering`() {
        val smallLowApr = Debt(name = "Small Card", type = DebtType.CREDIT_CARD, balance = 800.0, aprPercent = 12.0)
        val largeLowApr = Debt(name = "Large Loan", type = DebtType.PERSONAL_LOAN, balance = 5000.0, aprPercent = 8.0)
        val largeHigherApr = Debt(name = "Another Card", type = DebtType.CREDIT_CARD, balance = 3000.0, aprPercent = 24.0)
        val debts = listOf(largeLowApr, largeHigherApr, smallLowApr)

        assertEquals("Small Card", DebtPlanner.prioritize(debts, DebtPayoffStrategy.SNOWBALL).first().name)
        assertEquals("Another Card", DebtPlanner.prioritize(debts, DebtPayoffStrategy.AVALANCHE).first().name)
        assertFalse(DebtPlanner.prioritize(debts, DebtPayoffStrategy.SNOWBALL) == DebtPlanner.prioritize(debts, DebtPayoffStrategy.AVALANCHE))
    }
}
