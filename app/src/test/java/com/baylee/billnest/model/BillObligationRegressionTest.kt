package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BillObligationRegressionTest {
    private val today = LocalDate.of(2026, 9, 12)

    @Test
    fun matchingClearedTransactionPreventsAlreadyPaidBillFromBeingSubtractedAgain() {
        val checking = Account(
            name = "Checking",
            balance = 1000.0,
            role = AccountRole.SPENDING,
            includeInSpendable = true
        )
        val mortgage = Bill(
            name = "Mortgage",
            amount = 700.0,
            dueDateIso = "2026-09-01",
            frequency = Frequency.MONTHLY,
            category = "Mortgage"
        )
        val cleared = FinanceTransaction(
            id = "mortgage-payment",
            name = "Mortgage Payment",
            amount = 700.0,
            dateIso = "2026-09-01",
            category = "Mortgage",
            source = TransactionSource.PLAID
        )

        val summary = calculateMoneySummary(
            AppData(accounts = listOf(checking), bills = listOf(mortgage), transactions = listOf(cleared)),
            referenceDate = today
        )

        assertEquals(0.0, summary.upcomingBills, 0.001)
        assertEquals(1000.0, summary.availableAfterUpcomingBills, 0.001)
    }

    @Test
    fun trulyUnpaidBillStillCountsAsAnObligationForCurrentMonth() {
        val checking = Account(
            name = "Checking",
            balance = 1000.0,
            role = AccountRole.SPENDING,
            includeInSpendable = true
        )
        val electric = Bill(
            name = "Electric Company",
            amount = 100.0,
            dueDateIso = "2026-09-15",
            category = "Utilities"
        )

        val summary = calculateMoneySummary(
            AppData(accounts = listOf(checking), bills = listOf(electric)),
            referenceDate = today
        )

        assertEquals(100.0, summary.upcomingBills, 0.001)
        assertEquals(900.0, summary.availableAfterUpcomingBills, 0.001)
    }

    @Test
    fun nextMonthBillDoesNotReduceCurrentMonthAvailableMoney() {
        val checking = Account(
            name = "Checking",
            balance = 1000.0,
            role = AccountRole.SPENDING,
            includeInSpendable = true
        )
        val octoberBill = Bill(
            name = "October rent",
            amount = 700.0,
            dueDateIso = "2026-10-01",
            category = "Housing"
        )

        val summary = calculateMoneySummary(
            AppData(accounts = listOf(checking), bills = listOf(octoberBill)),
            referenceDate = today
        )

        assertEquals(0.0, summary.upcomingBills, 0.001)
        assertEquals(1000.0, summary.availableAfterUpcomingBills, 0.001)
    }

    @Test
    fun exactAmountDateAndMeaningfulCategoryCanConfirmBillPaymentEvenWhenMerchantTextDiffers() {
        val mortgage = Bill(
            name = "Home Loan",
            amount = 700.0,
            dueDateIso = "2026-09-01",
            category = "Mortgage"
        )
        val cleared = FinanceTransaction(
            id = "ach",
            name = "ACH Withdrawal 4839",
            amount = 700.0,
            dateIso = "2026-09-01",
            category = "Mortgage",
            source = TransactionSource.PLAID
        )

        val match = findBillMatches(listOf(mortgage), listOf(cleared)).single()

        assertTrue(match.highConfidence)
    }

    @Test
    fun reconcilingAlreadyClearedRecurringBillRecordsPaidOccurrenceAndAdvancesNextDueDate() {
        val mortgage = Bill(
            name = "Mortgage",
            amount = 700.0,
            dueDateIso = "2026-09-01",
            frequency = Frequency.MONTHLY,
            category = "Mortgage"
        )
        val cleared = FinanceTransaction(
            id = "mortgage-payment",
            name = "Mortgage Payment",
            amount = 700.0,
            dateIso = "2026-09-01",
            category = "Mortgage",
            source = TransactionSource.PLAID
        )

        val reconciled = reconcileBillPaymentState(mortgage, listOf(cleared))

        assertTrue("2026-09-01" in reconciled.paidDates)
        assertEquals("2026-10-01", reconciled.dueDateIso)
    }
}
