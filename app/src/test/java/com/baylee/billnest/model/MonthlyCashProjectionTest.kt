package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MonthlyCashProjectionTest {
    private val today = LocalDate.of(2026, 9, 12)

    @Test
    fun futurePaychecksOffsetRemainingBillsWithoutMakingCurrentCashNegative() {
        val checking = Account(
            name = "Checking",
            balance = 390.49,
            role = AccountRole.SPENDING,
            includeInSpendable = true
        )
        val payday = Payday(
            id = "work",
            label = "Work payroll",
            amount = 2288.0,
            nextDateIso = "2026-09-25",
            frequency = Frequency.BIWEEKLY
        )
        val remainingBill = Bill(
            name = "Remaining bills",
            amount = 551.89,
            dueDateIso = "2026-09-20",
            category = "Utilities"
        )

        val projection = calculateMonthlyCashProjection(
            AppData(
                accounts = listOf(checking),
                paydays = listOf(payday),
                bills = listOf(remainingBill)
            ),
            referenceDate = today
        )

        assertEquals(390.49, projection.currentSpendable, 0.001)
        assertEquals(2288.0, projection.futurePaychecks, 0.001)
        assertEquals(551.89, projection.remainingBills, 0.001)
        assertEquals(2126.60, projection.projectedMonthEndAvailable, 0.001)
    }

    @Test
    fun recurringPaydayCountsEveryFutureOccurrenceStillInsideCurrentMonth() {
        val payday = Payday(
            id = "weekly",
            label = "Weekly work",
            amount = 200.0,
            nextDateIso = "2026-09-13",
            frequency = Frequency.WEEKLY
        )

        val projection = calculateMonthlyCashProjection(
            AppData(paydays = listOf(payday)),
            referenceDate = today
        )

        assertEquals(600.0, projection.futurePaychecks, 0.001)
    }

    @Test
    fun nextMonthPaycheckDoesNotInflateCurrentMonthProjection() {
        val payday = Payday(
            id = "october",
            label = "October work",
            amount = 2500.0,
            nextDateIso = "2026-10-02",
            frequency = Frequency.BIWEEKLY
        )

        val projection = calculateMonthlyCashProjection(
            AppData(paydays = listOf(payday)),
            referenceDate = today
        )

        assertEquals(0.0, projection.futurePaychecks, 0.001)
    }
}
