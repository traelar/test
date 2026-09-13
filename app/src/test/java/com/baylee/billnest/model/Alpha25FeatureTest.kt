package com.baylee.billnest.model

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class Alpha25FeatureTest {
    @Test
    fun cashFlowProjectionUsesCurrentSpendableFuturePaydaysAndBills() {
        val data = AppData(
            accounts = listOf(Account(name = "Checking", balance = 1000.0, role = AccountRole.SPENDING, includeInSpendable = true)),
            bills = listOf(
                Bill(name = "Rent", amount = 700.0, dueDateIso = "2026-09-20"),
                Bill(name = "Phone", amount = 100.0, dueDateIso = "2026-09-25")
            ),
            paydays = listOf(Payday(label = "Work", amount = 900.0, nextDateIso = "2026-09-18"))
        )
        val result = cashFlowProjection(data, LocalDate.of(2026,9,12), LocalDate.of(2026,9,30))
        assertEquals(1100.0, result.endingBalance, 0.001)
        assertEquals(1000.0, result.startingBalance, 0.001)
        assertTrue(result.days.any { it.date == LocalDate.of(2026,9,18) && it.income == 900.0 })
        assertTrue(result.days.any { it.date == LocalDate.of(2026,9,20) && it.bills == 700.0 })
    }

    @Test
    fun transactionSplitMustEqualTransactionAmount() {
        val tx = FinanceTransaction(
            name = "Target",
            amount = 100.0,
            dateIso = "2026-09-12",
            splits = listOf(
                TransactionSplit(category = "Groceries", amount = 60.0),
                TransactionSplit(category = "Kids", amount = 40.0)
            )
        )
        assertTrue(validTransactionSplits(tx))
        assertEquals(60.0, spendingAmountForCategory(tx, "Groceries"), 0.001)
        assertEquals(40.0, spendingAmountForCategory(tx, "Kids"), 0.001)
        assertEquals(0.0, spendingAmountForCategory(tx, "Other"), 0.001)
    }

    @Test
    fun emergencyFundTargetUsesEssentialMonthlyCosts() {
        val data = AppData(
            bills = listOf(
                Bill(name = "Rent", amount = 1200.0, dueDateIso = "2026-09-20", category = "Housing"),
                Bill(name = "Netflix", amount = 20.0, dueDateIso = "2026-09-22", category = "Subscriptions")
            ),
            budgets = listOf(Budget(name = "Groceries", amount = 500.0, category = "Groceries"))
        )
        val estimate = emergencyFundEstimate(data, months = 3)
        assertEquals(1700.0, estimate.monthlyEssentials, 0.001)
        assertEquals(5100.0, estimate.target, 0.001)
    }

    @Test
    fun subscriptionInsightsShowMonthlyAndAnnualCost() {
        val data = AppData(
            subscriptionPreferences = listOf(
                SubscriptionPreference("netflix", "Netflix", SubscriptionStatus.CONFIRMED)
            ),
            transactions = listOf(
                FinanceTransaction(name="Netflix", amount=20.0, dateIso="2026-07-01"),
                FinanceTransaction(name="Netflix", amount=20.0, dateIso="2026-08-01"),
                FinanceTransaction(name="Netflix", amount=22.0, dateIso="2026-09-01")
            )
        )
        val insight = subscriptionCleanup(data)
        assertEquals(22.0, insight.monthlyCost, 0.001)
        assertEquals(264.0, insight.annualCost, 0.001)
        assertTrue(insight.items.first().priceIncreased)
    }

    @Test
    fun paycheckComparisonMatchesExpectedToActualDeposit() {
        val data = AppData(
            paydays = listOf(Payday(label="Employer", amount=1500.0, nextDateIso="2026-09-20")),
            transactions = listOf(
                FinanceTransaction(name="Employer Payroll", amount=-1475.0, dateIso="2026-09-06", income=true, source=TransactionSource.PLAID)
            )
        )
        val rows = paycheckComparisons(data, LocalDate.of(2026,9,12))
        assertTrue(rows.isNotEmpty())
        assertEquals(-25.0, rows.first().difference, 0.001)
    }

    @Test
    fun householdActivityIncludesManualClassificationAndGoalChanges() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(name="Walmart", amount=25.0, dateIso="2026-09-12", category="Groceries", userClassificationOverride=true)
            ),
            savingsGoals = listOf(SavingsGoal(name="Emergency", targetAmount=1000.0, savedAmount=250.0))
        )
        val feed = householdActivity(data)
        assertTrue(feed.any { it.title.contains("Walmart") })
        assertTrue(feed.any { it.title.contains("Emergency") })
    }

    @Test
    fun csvExportIncludesTransactionsAndSplits() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(
                    name="Target", amount=100.0, dateIso="2026-09-12",
                    splits=listOf(TransactionSplit(category="Groceries", amount=60.0))
                )
            )
        )
        val csv = exportTransactionsCsv(data)
        assertTrue(csv.contains("Target"))
        assertTrue(csv.contains("Groceries"))
    }
}
