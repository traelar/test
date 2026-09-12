package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFinanceTest {
    @Test
    fun `Plaid account identity is stable across reinstall`() {
        val firstInstall = Account(
            id = "random-one",
            name = "Checking",
            type = AccountType.CHECKING,
            source = AccountSource.PLAID,
            plaidAccountId = "plaid-account-123"
        )
        val secondInstall = firstInstall.copy(id = "different-random-id")

        assertEquals("plaid:plaid-account-123", AccountFinance.stableKey(firstInstall))
        assertEquals(AccountFinance.stableKey(firstInstall), AccountFinance.stableKey(secondInstall))
    }

    @Test
    fun `checking defaults to spendable while savings does not`() {
        val checking = Account(name = "Checking", type = AccountType.CHECKING)
        val savings = Account(name = "Savings", type = AccountType.SAVINGS)

        val checkingPreference = AccountFinance.defaultPreference(checking, 0)
        val savingsPreference = AccountFinance.defaultPreference(savings, 1)

        assertEquals(AccountRole.SPENDING, checkingPreference.role)
        assertTrue(checkingPreference.includeInTotal)
        assertTrue(checkingPreference.includeInSpending)

        assertEquals(AccountRole.SAVINGS, savingsPreference.role)
        assertTrue(savingsPreference.includeInTotal)
        assertFalse(savingsPreference.includeInSpending)
    }

    @Test
    fun `summary separates total spending and savings money`() {
        val checking = Account(id = "checking", name = "Checking", type = AccountType.CHECKING, balance = 1200.0)
        val savings = Account(id = "savings", name = "Savings", type = AccountType.SAVINGS, balance = 5000.0)
        val hidden = Account(id = "hidden", name = "Hidden", type = AccountType.OTHER, balance = 900.0)
        val accounts = listOf(checking, savings, hidden)
        val preferences = listOf(
            AccountPreference("manual:checking", 0, AccountRole.SPENDING, includeInTotal = true, includeInSpending = true),
            AccountPreference("manual:savings", 1, AccountRole.SAVINGS, includeInTotal = true, includeInSpending = false),
            AccountPreference("manual:hidden", 2, AccountRole.OTHER, includeInTotal = false, includeInSpending = false)
        )

        val summary = AccountFinance.summarize(accounts, preferences)

        assertEquals(6200.0, summary.totalMoney, 0.001)
        assertEquals(1200.0, summary.spendingMoney, 0.001)
        assertEquals(5000.0, summary.savingsMoney, 0.001)
    }

    @Test
    fun `display order follows saved account preferences`() {
        val first = Account(id = "a", name = "A")
        val second = Account(id = "b", name = "B")
        val third = Account(id = "c", name = "C")
        val preferences = listOf(
            AccountPreference("manual:a", 2, AccountRole.SPENDING, true, true),
            AccountPreference("manual:b", 0, AccountRole.SPENDING, true, true),
            AccountPreference("manual:c", 1, AccountRole.SPENDING, true, true)
        )

        assertEquals(
            listOf("B", "C", "A"),
            AccountFinance.sortAccounts(listOf(first, second, third), preferences).map { it.name }
        )
    }
}
