package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DashboardRegressionTest {
    private val referenceDate = LocalDate.of(2026, 9, 12)

    @Test
    fun noBudgetsDoesNotReportWarningJustBecauseVariableSpendingExists() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(
                    id = "groceries",
                    name = "Grocery Store",
                    amount = 125.0,
                    dateIso = "2026-09-10",
                    category = "Groceries"
                )
            )
        )

        val summary = calculateVariableSpendingSummary(data, referenceDate)

        assertEquals(0.0, summary.planned, 0.001)
        assertEquals(125.0, summary.unbudgetedSpent, 0.001)
        assertEquals(BudgetPace.ON_TRACK, summary.pace)
    }

    @Test
    fun transferOutCategoryNeverCountsAsVariableSpending() {
        val transferOut = FinanceTransaction(
            id = "transfer-out",
            name = "ONLINE TRANSFER",
            amount = 2308.98,
            dateIso = "2026-09-10",
            category = "Transfer out",
            source = TransactionSource.PLAID,
            transfer = false
        )
        val groceries = FinanceTransaction(
            id = "groceries",
            name = "Aldi",
            amount = 80.0,
            dateIso = "2026-09-11",
            category = "Groceries",
            source = TransactionSource.PLAID
        )
        val data = AppData(transactions = listOf(transferOut, groceries))

        val eligible = eligibleVariableSpendingTransactions(
            data,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30)
        )
        val recap = monthlyRecap(data, YearMonth.of(2026, 9))

        assertEquals(listOf("groceries"), eligible.map { it.id })
        assertEquals(80.0, recap.spending, 0.001)
        assertEquals("Groceries", recap.topCategory)
        assertTrue(recap.categoryTotals.keys.none { it.startsWith("Transfer", ignoreCase = true) })
    }
}
