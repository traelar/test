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

    @Test fun detectsBiweeklyPaydaysEvenWhenCheckAmountsVary() {
        val transactions = listOf(
            FinanceTransaction(name = "ACME Payroll", amount = 1264.22, dateIso = "2026-07-31", income = true),
            FinanceTransaction(name = "ACME Payroll", amount = 1188.40, dateIso = "2026-08-14", income = true),
            FinanceTransaction(name = "ACME Payroll", amount = 1301.05, dateIso = "2026-08-28", income = true),
            FinanceTransaction(name = "Grocery store", amount = 90.0, dateIso = "2026-08-29")
        )

        val result = detectPaydayPatterns(transactions, referenceDate = java.time.LocalDate.parse("2026-09-01"))

        assertEquals(1, result.size)
        assertEquals(Frequency.BIWEEKLY, result.single().frequency)
        assertEquals("2026-09-11", result.single().nextDateIso)
        assertEquals(1264.22, result.single().typicalAmount, 0.001)
    }

    @Test fun ignoresIrregularDepositsAndTransfers() {
        val transactions = listOf(
            FinanceTransaction(name = "Transfer", amount = 800.0, dateIso = "2026-08-01", income = true, transfer = true),
            FinanceTransaction(name = "Marketplace sale", amount = 30.0, dateIso = "2026-08-04", income = true),
            FinanceTransaction(name = "Marketplace sale", amount = 90.0, dateIso = "2026-08-23", income = true)
        )

        assertEquals(0, detectPaydayPatterns(transactions).size)
    }

    @Test fun detectsMonthlySubscriptionWithSmallPriceChanges() {
        val transactions = listOf(
            FinanceTransaction(name = "Video Stream", amount = 14.99, dateIso = "2026-06-02"),
            FinanceTransaction(name = "Video Stream", amount = 14.99, dateIso = "2026-07-02"),
            FinanceTransaction(name = "Video Stream", amount = 15.49, dateIso = "2026-08-02")
        )

        val suggestions = detectSubscriptions(transactions)

        assertEquals(1, suggestions.size)
        assertEquals(Frequency.MONTHLY, suggestions.single().frequency)
        assertEquals(14.99, suggestions.single().typicalAmount, 0.001)
    }

    @Test fun findsHighConfidenceBillPaymentMatch() {
        val bill = Bill(name = "Electric Company", amount = 104.50, dueDateIso = "2026-09-10")
        val transaction = FinanceTransaction(name = "Electric Company Payment", amount = 104.50, dateIso = "2026-09-09")

        val match = findBillMatches(listOf(bill), listOf(transaction)).single()

        assertEquals(bill.id, match.billId)
        assertEquals(transaction.id, match.transactionId)
        assertEquals(true, match.highConfidence)
    }

    @Test fun receivingPaycheckFundsConfiguredGoalsAndReservesOnlyOnce() {
        val payday = Payday(label = "Work", amount = 1200.0, nextDateIso = "2026-09-11", frequency = Frequency.BIWEEKLY)
        val data = AppData(
            paydays = listOf(payday),
            reservedFunds = listOf(ReservedFund(name = "Insurance", amount = 100.0, paydayContribution = 75.0)),
            savingsGoals = listOf(SavingsGoal(name = "Emergency", targetAmount = 1000.0, savedAmount = 200.0, paydayContribution = 50.0))
        )

        val once = applyPaydayContributions(data, payday.id, java.time.LocalDate.parse("2026-09-11"))
        val twice = applyPaydayContributions(once, payday.id, java.time.LocalDate.parse("2026-09-11"))

        assertEquals(175.0, twice.reservedFunds.single().amount, 0.001)
        assertEquals(250.0, twice.savingsGoals.single().savedAmount, 0.001)
        assertEquals("2026-09-25", twice.paydays.single().nextDateIso)
        assertEquals(1, twice.paydays.single().receivedDates.size)
    }
}
