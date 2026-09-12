package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class IncomeRulesTest {
    private val referenceDate = LocalDate.of(2026, 9, 12)

    @Test
    fun recentIncomeWindowIncludesTodayAndTwentyNineDaysAgo() {
        val today = FinanceTransaction(
            id = "today",
            name = "Today payroll",
            amount = -1200.0,
            dateIso = "2026-09-12",
            source = TransactionSource.PLAID,
            income = true
        )
        val dayTwentyNine = today.copy(
            id = "day-29",
            name = "Older payroll",
            dateIso = "2026-08-14"
        )
        val dayThirty = today.copy(
            id = "day-30",
            name = "Too old payroll",
            dateIso = "2026-08-13"
        )

        val result = recentVisibleIncomeTransactions(
            transactions = listOf(dayThirty, dayTwentyNine, today),
            referenceDate = referenceDate
        )

        assertEquals(listOf("day-29", "today"), result.map { it.id })
    }

    @Test
    fun recentIncomeWindowExcludesFutureAndInvalidDates() {
        val valid = FinanceTransaction(
            id = "valid",
            name = "Payroll",
            amount = -1000.0,
            dateIso = "2026-09-01",
            source = TransactionSource.PLAID,
            income = true
        )
        val future = valid.copy(id = "future", dateIso = "2026-09-13")
        val invalid = valid.copy(id = "invalid", dateIso = "not-a-date")

        val result = recentVisibleIncomeTransactions(
            listOf(valid, future, invalid),
            referenceDate
        )

        assertEquals(listOf("valid"), result.map { it.id })
    }

    @Test
    fun recentIncomeWindowStillHonorsManualSpendingAndTransferOverrides() {
        val base = FinanceTransaction(
            id = "income",
            name = "Deposit",
            amount = -100.0,
            dateIso = "2026-09-10",
            source = TransactionSource.PLAID,
            income = true
        )
        val spendingOverride = base.copy(
            id = "spending",
            category = "Other",
            income = false,
            userClassificationOverride = true
        )
        val transferOverride = base.copy(
            id = "transfer",
            category = "Transfer",
            income = false,
            transfer = true,
            userClassificationOverride = true
        )

        val result = recentVisibleIncomeTransactions(
            listOf(base, spendingOverride, transferOverride),
            referenceDate
        )

        assertEquals(listOf("income"), result.map { it.id })
        assertFalse(result.any { it.transfer })
    }
}
