package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FinanceModelsTest {
    @Test fun debtDueDateUsesReadableNextMonthlyOccurrence() {
        val debt = Debt(name = "Loan", type = DebtType.LOAN, balance = 1000.0, dueDay = 15)
        assertEquals(LocalDate.of(2026, 9, 15), nextDebtDueDate(debt, LocalDate.of(2026, 9, 10)))
        assertEquals(LocalDate.of(2026, 10, 15), nextDebtDueDate(debt, LocalDate.of(2026, 9, 20)))
    }

    @Test fun debtDueDateClampsShortMonths() {
        val debt = Debt(name = "Loan", type = DebtType.LOAN, balance = 1000.0, dueDay = 31)
        assertEquals(LocalDate.of(2026, 2, 28), nextDebtDueDate(debt, LocalDate.of(2026, 2, 1)))
    }
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

    @Test fun reserveLinkedToExcludedSavingsIsNotSubtractedTwice() {
        val checking = Account(name = "Checking", balance = 1000.0, role = AccountRole.SPENDING)
        val savings = Account(name = "Savings", balance = 500.0, role = AccountRole.SAVINGS, includeInSpendable = false)
        val data = AppData(accounts = listOf(checking, savings), reservedFunds = listOf(ReservedFund(name = "Emergency", amount = 300.0, accountId = savings.id)))
        val summary = calculateMoneySummary(data)
        assertEquals(300.0, summary.reserved, 0.001)
        assertEquals(0.0, summary.reservedFromSpending, 0.001)
        assertEquals(1000.0, summary.availableAfterUpcomingBills, 0.001)
    }

    @Test fun retirementIsSeparatedFromOrdinarySavings() {
        val data = AppData(accounts = listOf(
            Account(name = "Savings", type = AccountType.SAVINGS, balance = 500.0, role = AccountRole.SAVINGS, includeInSpendable = false),
            Account(name = "401k", type = AccountType.INVESTMENT, balance = 5000.0, role = AccountRole.SAVINGS, includeInSpendable = false)
        ))
        val summary = calculateMoneySummary(data)
        assertEquals(500.0, summary.savings, 0.001)
        assertEquals(5000.0, summary.retirement, 0.001)
        assertEquals(5500.0, summary.totalMoney, 0.001)
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

    @Test fun budgetSpendingExcludesIncomeAndTransfers() {
        val budget = Budget(name = "Food", amount = 300.0, category = "Food", period = BudgetPeriod.MONTHLY)
        val rows = listOf(
            FinanceTransaction(name = "Groceries", amount = 82.0, dateIso = "2026-09-03", category = "Food"),
            FinanceTransaction(name = "Transfer", amount = 100.0, dateIso = "2026-09-04", category = "Food", transfer = true),
            FinanceTransaction(name = "Refund", amount = 20.0, dateIso = "2026-09-05", category = "Food", income = true),
            FinanceTransaction(name = "Old groceries", amount = 55.0, dateIso = "2026-08-05", category = "Food")
        )

        assertEquals(82.0, calculateBudgetSpent(budget, rows, java.time.LocalDate.parse("2026-09-12")), 0.001)
    }

    @Test fun avalancheCostsLessInterestThanSnowballForMixedDebts() {
        val debts = listOf(
            Debt(name = "Small loan", type = DebtType.LOAN, balance = 1800.0, apr = 5.0, minimumPayment = 75.0),
            Debt(name = "Credit card", type = DebtType.CREDIT_CARD, balance = 5000.0, apr = 24.0, minimumPayment = 150.0)
        )

        val snowball = calculateDebtStrategy(debts, 200.0, DebtStrategy.SNOWBALL)
        val avalanche = calculateDebtStrategy(debts, 200.0, DebtStrategy.AVALANCHE)

        assertEquals(true, avalanche.totalInterest < snowball.totalInterest)
        assertEquals(true, avalanche.months > 0)
    }

    @Test fun subscriptionPreferenceSuppressesIgnoredSuggestion() {
        val suggestion = SubscriptionSuggestion("Video Stream", 15.0, Frequency.MONTHLY, "2026-09-01", 3)
        val ignored = SubscriptionPreference(merchantKey = subscriptionKey(suggestion.name), name = suggestion.name, status = SubscriptionStatus.IGNORED)

        assertEquals(0, visibleSubscriptionSuggestions(listOf(suggestion), listOf(ignored)).size)
    }

    @Test fun plaidRefreshPreservesSavedAccountOrderAndBehavior() {
        val existing = listOf(
            Account(name = "Savings", source = AccountSource.PLAID, plaidAccountId = "save", balance = 10.0, role = AccountRole.SAVINGS, includeInSpendable = false, displayOrder = 0),
            Account(name = "Checking", source = AccountSource.PLAID, plaidAccountId = "check", balance = 20.0, role = AccountRole.SPENDING, displayOrder = 1)
        )
        val incoming = listOf(
            Account(name = "Checking from bank", source = AccountSource.PLAID, plaidAccountId = "check", balance = 220.0),
            Account(name = "Savings from bank", source = AccountSource.PLAID, plaidAccountId = "save", balance = 110.0)
        )

        val result = mergePlaidAccounts(existing, incoming)

        assertEquals(listOf("save", "check"), result.map { it.plaidAccountId })
        assertEquals(110.0, result[0].balance, 0.001)
        assertEquals(AccountRole.SAVINGS, result[0].role)
        assertEquals(false, result[0].includeInSpendable)
    }

    @Test fun partialPlaidRefreshRetainsAccountThatNeedsReconnect() {
        val existing = listOf(
            Account(name = "Working", source = AccountSource.PLAID, plaidAccountId = "working", displayOrder = 0),
            Account(name = "Needs reconnect", source = AccountSource.PLAID, plaidAccountId = "broken", displayOrder = 1)
        )
        val incoming = listOf(Account(name = "Working", source = AccountSource.PLAID, plaidAccountId = "working", balance = 55.0))

        val result = mergePlaidAccounts(existing, incoming, retainMissing = true)

        assertEquals(listOf("working", "broken"), result.map { it.plaidAccountId })
    }
}
