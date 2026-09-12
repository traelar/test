package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMoneyTest {
    @Test
    fun savingsCountsInTotalButNotSpendingByDefault() {
        val checking = Account(id = "checking", name = "Checking", type = AccountType.CHECKING, balance = 1200.0)
        val savings = Account(id = "savings", name = "Savings", type = AccountType.SAVINGS, balance = 5000.0)

        val summary = MoneyMath.summary(
            accounts = listOf(checking, savings),
            preferences = emptyList(),
            reservedFunds = emptyList()
        )

        assertEquals(6200.0, summary.totalMoney, 0.001)
        assertEquals(1200.0, summary.spendingMoney, 0.001)
        assertEquals(5000.0, summary.savingsMoney, 0.001)
    }

    @Test
    fun accountCanBeExcludedFromAllTotalsWithoutBeingHidden() {
        val account = Account(id = "side", name = "Side Account", type = AccountType.CHECKING, balance = 900.0)
        val preference = AccountPreference(
            accountId = account.id,
            role = AccountRole.SPENDING,
            includeInTotalMoney = false,
            includeInSpendingMoney = false,
            displayOrder = 0
        )

        val summary = MoneyMath.summary(listOf(account), listOf(preference), emptyList())

        assertEquals(0.0, summary.totalMoney, 0.001)
        assertEquals(0.0, summary.spendingMoney, 0.001)
    }

    @Test
    fun reservedFundsReduceFreeSavingsAndSpendableMoneyWhenRelevant() {
        val checking = Account(id = "checking", name = "Checking", type = AccountType.CHECKING, balance = 2000.0)
        val savings = Account(id = "savings", name = "Savings", type = AccountType.SAVINGS, balance = 4000.0)
        val reserves = listOf(
            ReservedFund(id = "rent", name = "Rent", accountId = checking.id, currentReserved = 500.0),
            ReservedFund(id = "mortgage", name = "Mortgage Reserve", accountId = savings.id, currentReserved = 1500.0)
        )

        val summary = MoneyMath.summary(listOf(checking, savings), emptyList(), reserves)

        assertEquals(6000.0, summary.totalMoney, 0.001)
        assertEquals(1500.0, summary.spendingMoney, 0.001)
        assertEquals(4000.0, summary.savingsMoney, 0.001)
        assertEquals(2000.0, summary.reservedMoney, 0.001)
        assertEquals(2500.0, summary.freeSavings, 0.001)
    }

    @Test
    fun availableAfterBillsUsesSpendableMoneyNotTotalMoney() {
        val checking = Account(id = "checking", name = "Checking", type = AccountType.CHECKING, balance = 1300.0)
        val savings = Account(id = "savings", name = "Savings", type = AccountType.SAVINGS, balance = 10000.0)

        val summary = MoneyMath.summary(
            accounts = listOf(checking, savings),
            preferences = emptyList(),
            reservedFunds = emptyList(),
            upcomingBills = 450.0
        )

        assertEquals(850.0, summary.availableAfterUpcomingBills, 0.001)
    }

    @Test
    fun explicitPreferencesControlDisplayOrderAndSpendability() {
        val checking = Account(id = "checking", name = "Checking", type = AccountType.CHECKING, balance = 1000.0)
        val savings = Account(id = "savings", name = "Savings", type = AccountType.SAVINGS, balance = 3000.0)
        val preferences = listOf(
            AccountPreference("checking", AccountRole.SPENDING, true, true, 5),
            AccountPreference("savings", AccountRole.SAVINGS, true, false, 1)
        )

        val ordered = MoneyMath.orderedAccounts(listOf(checking, savings), preferences)
        val savingsPreference = MoneyMath.preferenceFor(savings, preferences, 0)

        assertEquals(listOf("savings", "checking"), ordered.map { it.id })
        assertFalse(savingsPreference.includeInSpendingMoney)
        assertTrue(savingsPreference.includeInTotalMoney)
    }

    @Test
    fun reservedFundCanBeLinkedToPaydayAndBillWithoutHardcodedAmounts() {
        val fund = ReservedFund(
            name = "Housing Reserve",
            accountId = "savings",
            currentReserved = 0.0,
            contributionAmount = 250.0,
            fundingPaydayId = "paycheck-1",
            linkedBillId = "housing-bill"
        )

        assertEquals(250.0, fund.contributionAmount, 0.001)
        assertEquals("paycheck-1", fund.fundingPaydayId)
        assertEquals("housing-bill", fund.linkedBillId)
    }
}
