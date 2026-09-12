package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFinanceTest {
    @Test
    fun savingsCountsInTotalAndSavingsButNotSpendingByDefault() {
        val checking = Account(
            id = "checking",
            name = "Checking",
            type = AccountType.CHECKING,
            balance = 1200.0,
            role = AccountRole.SPENDING,
            includeInTotalMoney = true,
            includeInSpendingMoney = true
        )
        val savings = Account(
            id = "savings",
            name = "Savings",
            type = AccountType.SAVINGS,
            balance = 3500.0,
            role = AccountRole.SAVINGS,
            includeInTotalMoney = true,
            includeInSpendingMoney = false
        )

        val totals = AccountFinance.totals(listOf(checking, savings))

        assertEquals(4700.0, totals.totalMoney, 0.001)
        assertEquals(1200.0, totals.spendingMoney, 0.001)
        assertEquals(3500.0, totals.savings, 0.001)
    }

    @Test
    fun creditDebtNeverInflatesCashTotals() {
        val card = Account(
            id = "card",
            name = "Credit Card",
            type = AccountType.OTHER,
            balance = 900.0,
            role = AccountRole.CREDIT_DEBT,
            includeInTotalMoney = true,
            includeInSpendingMoney = true
        )

        val totals = AccountFinance.totals(listOf(card))

        assertEquals(0.0, totals.totalMoney, 0.001)
        assertEquals(0.0, totals.spendingMoney, 0.001)
        assertEquals(0.0, totals.savings, 0.001)
    }

    @Test
    fun userCanExcludeAnAccountFromBothFinancialTotalsWithoutHidingIt() {
        val hiddenFromMath = Account(
            id = "excluded",
            name = "Excluded Savings",
            type = AccountType.SAVINGS,
            balance = 800.0,
            role = AccountRole.SAVINGS,
            includeInTotalMoney = false,
            includeInSpendingMoney = false
        )

        val totals = AccountFinance.totals(listOf(hiddenFromMath))

        assertEquals(0.0, totals.totalMoney, 0.001)
        assertEquals(0.0, totals.spendingMoney, 0.001)
        assertEquals(0.0, totals.savings, 0.001)
    }

    @Test
    fun orderingUsesDisplayOrderThenStableId() {
        val c = Account(id = "c", name = "C", displayOrder = 1)
        val b = Account(id = "b", name = "B", displayOrder = 0)
        val a = Account(id = "a", name = "A", displayOrder = 1)

        assertEquals(listOf("b", "a", "c"), AccountFinance.sorted(listOf(c, b, a)).map { it.id })
    }

    @Test
    fun deterministicLegacyDefaultsMatchApprovedRoles() {
        val checking = AccountDefaults.forType(AccountType.CHECKING)
        val savings = AccountDefaults.forType(AccountType.SAVINGS)
        val cash = AccountDefaults.forType(AccountType.CASH)
        val other = AccountDefaults.forType(AccountType.OTHER)

        assertEquals(AccountRole.SPENDING, checking.role)
        assertTrue(checking.includeInTotalMoney)
        assertTrue(checking.includeInSpendingMoney)

        assertEquals(AccountRole.SAVINGS, savings.role)
        assertTrue(savings.includeInTotalMoney)
        assertFalse(savings.includeInSpendingMoney)

        assertEquals(AccountRole.SPENDING, cash.role)
        assertTrue(cash.includeInTotalMoney)
        assertTrue(cash.includeInSpendingMoney)

        assertEquals(AccountRole.OTHER, other.role)
        assertTrue(other.includeInTotalMoney)
        assertFalse(other.includeInSpendingMoney)
    }
}
