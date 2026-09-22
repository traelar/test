package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DebtBillLinkTest {
    private val today = LocalDate.of(2026, 9, 22)

    @Test
    fun debtWithDueDateCreatesOneLinkedMonthlyBill() {
        val debt = Debt(
            id = "car-loan",
            name = "Car Loan",
            type = DebtType.LOAN,
            balance = 12450.0,
            minimumPayment = 389.0,
            dueDay = 5,
            dueDateIso = "2026-10-05"
        )

        val data = reconcileDebtBills(AppData(debts = listOf(debt)), today)
        val bill = data.bills.single()

        assertEquals(debtBillId(debt.id), bill.id)
        assertEquals(debt.id, bill.sourceDebtId)
        assertEquals("Car Loan", bill.name)
        assertEquals(389.0, bill.amount, 0.001)
        assertEquals("2026-10-05", bill.dueDateIso)
        assertEquals(Frequency.MONTHLY, bill.frequency)
    }

    @Test
    fun reconcilingAgainDoesNotDuplicateDebtBillAndKeepsPaymentHistory() {
        val original = Debt(
            id = "visa",
            name = "Visa",
            type = DebtType.CREDIT_CARD,
            balance = 2400.0,
            minimumPayment = 90.0,
            dueDay = 12,
            dueDateIso = "2026-10-12"
        )
        val first = reconcileDebtBills(AppData(debts = listOf(original)), today)
        val paidHistory = first.bills.single().copy(paidDates = listOf("2026-09-12"))
        val edited = original.copy(minimumPayment = 105.0, dueDay = 14, dueDateIso = "2026-10-14")

        val second = reconcileDebtBills(
            first.copy(debts = listOf(edited), bills = listOf(paidHistory)),
            today
        )

        assertEquals(1, second.bills.size)
        assertEquals(105.0, second.bills.single().amount, 0.001)
        assertEquals("2026-10-14", second.bills.single().dueDateIso)
        assertTrue("2026-09-12" in second.bills.single().paidDates)
    }

    @Test
    fun clearingDebtDueDateRemovesGeneratedBill() {
        val debt = Debt(
            id = "loan",
            name = "Loan",
            type = DebtType.LOAN,
            balance = 1000.0,
            minimumPayment = 75.0,
            dueDay = 28,
            dueDateIso = "2026-09-28"
        )
        val linked = linkedDebtBill(debt, today = today)!!
        val cleared = debt.copy(dueDay = 0, dueDateIso = null)

        val result = reconcileDebtBills(
            AppData(debts = listOf(cleared), bills = listOf(linked)),
            today
        )

        assertTrue(result.bills.isEmpty())
        assertNull(nextDebtDueDateOrNull(cleared, today))
    }

    @Test
    fun legacyDebtDueDayStillProducesNextPaymentDate() {
        val legacy = Debt(
            id = "legacy",
            name = "Legacy Card",
            type = DebtType.CREDIT_CARD,
            balance = 500.0,
            minimumPayment = 35.0,
            dueDay = 25,
            dueDateIso = null
        )

        val bill = linkedDebtBill(legacy, today = today)

        assertEquals("2026-09-25", bill?.dueDateIso)
    }
}
