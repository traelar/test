package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceModelsTest {
    @Test fun reservedMoneyIsNotCountedAsFreeSpending() {
        val data = AppData(
            accounts = listOf(
                Account(name = "Checking", balance = 2000.0, role = AccountRole.SPENDING),
                Account(name = "Savings", balance = 500.0, role = AccountRole.SAVINGS, includeInSpendable = false)
            ),
            bills = listOf(Bill(name = "Power", amount = 100.0, dueDateIso = "2099-01-01")),
            reservedFunds = listOf(ReservedFund(name = "Insurance", amount = 300.0))
        )
        val summary = calculateMoneySummary(data)
        assertEquals(2500.0, summary.totalMoney, 0.001)
        assertEquals(2000.0, summary.spendingMoney, 0.001)
        assertEquals(500.0, summary.savings, 0.001)
        assertEquals(300.0, summary.reserved, 0.001)
        assertEquals(1600.0, summary.availableAfterUpcomingBills, 0.001)
    }

    @Test fun transfersDoNotCountAsSpending() {
        val items = listOf(
            FinanceTransaction(name = "Groceries", amount = 80.0, dateIso = "2026-09-12"),
            FinanceTransaction(name = "Checking to savings", amount = 300.0, dateIso = "2026-09-12", transfer = true)
        )
        assertEquals(80.0, items.filterNot { it.transfer }.sumOf { it.amount }, 0.001)
    }
}
