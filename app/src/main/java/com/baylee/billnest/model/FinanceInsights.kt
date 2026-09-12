package com.baylee.billnest.model

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.min

data class MonthlyRecap(
    val month: YearMonth,
    val spending: Double,
    val priorMonthSpending: Double,
    val spendingDelta: Double,
    val income: Double,
    val topCategory: String?,
    val topCategoryAmount: Double,
    val categoryTotals: Map<String, Double>,
    val largestIncreaseCategory: String? = null,
    val largestIncreaseAmount: Double = 0.0,
    val assetChange: Double? = null,
    val debtReduction: Double? = null,
    val netWorthChange: Double? = null
)

data class FinancialSnapshot(
    val id: String,
    val dateIso: String,
    val assets: Double,
    val debts: Double,
    val netWorth: Double
)

data class NetWorthPoint(
    val month: YearMonth,
    val dateIso: String,
    val assets: Double,
    val debts: Double,
    val netWorth: Double
)

data class DebtPaymentMatch(
    val debtId: String,
    val transactionId: String,
    val amount: Double,
    val dateIso: String,
    val confidence: Double
)

data class DebtPaymentBreakdown(
    val paymentAmount: Double,
    val estimatedInterest: Double,
    val estimatedPrincipal: Double
)

data class PaycheckPlan(
    val paydayId: String,
    val paydayLabel: String,
    val dateIso: String,
    val income: Double,
    val bills: Double,
    val reserves: Double,
    val savings: Double,
    val debtMinimums: Double,
    val variableBudgets: Double,
    val unassigned: Double
)

private val fixedSpendingCategories = setOf(
    "housing", "utilities", "phone/internet", "insurance", "credit card",
    "debt", "savings", "transfer", "income", "subscriptions",
    "saving", "reserve", "reserved funds", "investment", "retirement",
    "debt payment", "credit card payment", "loan payment", "mortgage payment"
)

fun isNonVariableSpendingCategory(category: String): Boolean {
    val normalized = category.trim()
        .replace('_', ' ')
        .lowercase()
        .replace(Regex("\\s+"), " ")
    return normalized in fixedSpendingCategories || normalized.startsWith("transfer ")
}

fun isVariableSpendingForInsights(row: FinanceTransaction): Boolean =
    !row.transfer &&
        !row.income &&
        !row.excludedFromSpending &&
        row.amount > 0.0 &&
        !isNonVariableSpendingCategory(row.category)

fun monthlyRecap(data: AppData, month: YearMonth): MonthlyRecap {
    fun rowsFor(target: YearMonth): List<FinanceTransaction> = data.transactions.filter { row ->
        if (!isVariableSpendingForInsights(row)) return@filter false
        val date = runCatching { LocalDate.parse(row.dateIso) }.getOrNull() ?: return@filter false
        YearMonth.from(date) == target
    }

    fun totalsFor(rows: List<FinanceTransaction>): Map<String, Double> = rows
        .groupBy { it.category.ifBlank { "Other" } }
        .mapValues { (_, grouped) -> grouped.sumOf { it.amount.coerceAtLeast(0.0) } }
        .toList()
        .sortedByDescending { it.second }
        .toMap()

    fun latestSnapshot(target: YearMonth): FinancialSnapshot? = data.financialSnapshots
        .mapNotNull { snapshot ->
            val date = runCatching { LocalDate.parse(snapshot.dateIso) }.getOrNull() ?: return@mapNotNull null
            if (YearMonth.from(date) == target) date to snapshot else null
        }
        .maxByOrNull { it.first }
        ?.second

    val currentRows = rowsFor(month)
    val priorMonth = month.minusMonths(1)
    val priorRows = rowsFor(priorMonth)
    val categoryTotals = totalsFor(currentRows)
    val priorCategoryTotals = totalsFor(priorRows)
    val top = categoryTotals.maxByOrNull { it.value }
    val largestIncrease = (categoryTotals.keys + priorCategoryTotals.keys)
        .map { category -> category to ((categoryTotals[category] ?: 0.0) - (priorCategoryTotals[category] ?: 0.0)) }
        .filter { it.second > 0.005 }
        .maxByOrNull { it.second }

    val income = data.transactions.filter { row ->
        if (row.transfer) return@filter false
        val date = runCatching { LocalDate.parse(row.dateIso) }.getOrNull() ?: return@filter false
        YearMonth.from(date) == month &&
            (row.income || row.category.equals("Income", true) ||
                (row.source == TransactionSource.PLAID && row.amount < 0.0 && !row.userClassificationOverride))
    }.sumOf { abs(it.amount) }
    val spending = currentRows.sumOf { it.amount.coerceAtLeast(0.0) }
    val prior = priorRows.sumOf { it.amount.coerceAtLeast(0.0) }

    val currentSnapshot = latestSnapshot(month)
    val previousSnapshot = latestSnapshot(priorMonth)
    val hasProgressPair = currentSnapshot != null && previousSnapshot != null

    return MonthlyRecap(
        month = month,
        spending = spending,
        priorMonthSpending = prior,
        spendingDelta = spending - prior,
        income = income,
        topCategory = top?.key,
        topCategoryAmount = top?.value ?: 0.0,
        categoryTotals = categoryTotals,
        largestIncreaseCategory = largestIncrease?.first,
        largestIncreaseAmount = largestIncrease?.second ?: 0.0,
        assetChange = if (hasProgressPair) currentSnapshot!!.assets - previousSnapshot!!.assets else null,
        debtReduction = if (hasProgressPair) previousSnapshot!!.debts - currentSnapshot!!.debts else null,
        netWorthChange = if (hasProgressPair) currentSnapshot!!.netWorth - previousSnapshot!!.netWorth else null
    )
}

fun captureFinancialSnapshot(data: AppData, date: LocalDate = LocalDate.now()): AppData {
    val visibleAssets = data.accounts.filter { account ->
        account.type != AccountType.CREDIT && account.role != AccountRole.CREDIT
    }
    val assets = if (visibleAssets.isNotEmpty()) visibleAssets.sumOf { it.balance } else data.manualBalance
    val debts = data.debts.sumOf { it.balance.coerceAtLeast(0.0) }
    val snapshot = FinancialSnapshot(
        id = date.toString(),
        dateIso = date.toString(),
        assets = assets,
        debts = debts,
        netWorth = assets - debts
    )
    return data.copy(
        financialSnapshots = (data.financialSnapshots.filterNot { it.dateIso == snapshot.dateIso } + snapshot)
            .sortedBy { it.dateIso }
    )
}

fun netWorthHistory(data: AppData): List<NetWorthPoint> = data.financialSnapshots
    .mapNotNull { snapshot ->
        val date = runCatching { LocalDate.parse(snapshot.dateIso) }.getOrNull() ?: return@mapNotNull null
        date to snapshot
    }
    .groupBy { YearMonth.from(it.first) }
    .map { (month, rows) ->
        val latest = rows.maxBy { it.first }.second
        NetWorthPoint(
            month = month,
            dateIso = latest.dateIso,
            assets = latest.assets,
            debts = latest.debts,
            netWorth = latest.netWorth
        )
    }
    .sortedBy { it.month }

fun detectDebtPayments(
    data: AppData,
    referenceDate: LocalDate = LocalDate.now()
): List<DebtPaymentMatch> {
    val creditAccountByPlaidId = data.accounts
        .filter { it.type == AccountType.CREDIT && !it.plaidAccountId.isNullOrBlank() }
        .associateBy { it.plaidAccountId!! }

    return data.debts.flatMap { debt ->
        val linkedAccount = debt.plaidAccountId?.let(creditAccountByPlaidId::get)
        data.transactions.mapNotNull { row ->
            val date = runCatching { LocalDate.parse(row.dateIso) }.getOrNull() ?: return@mapNotNull null
            if (YearMonth.from(date) != YearMonth.from(referenceDate)) return@mapNotNull null

            val explicitTransfer = linkedAccount != null && row.transfer && row.transferToAccountId == linkedAccount.id
            val normalizedName = row.name.lowercase()
            val debtWords = debt.name.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 }
            val namedPayment = !row.income && !row.transfer && row.amount > 0.0 &&
                normalizedName.contains("payment") && debtWords.any { normalizedName.contains(it) }
            if (!explicitTransfer && !namedPayment) return@mapNotNull null

            DebtPaymentMatch(
                debtId = debt.id,
                transactionId = row.id,
                amount = abs(row.amount),
                dateIso = row.dateIso,
                confidence = if (explicitTransfer) 1.0 else 0.8
            )
        }
    }.distinctBy { it.debtId to it.transactionId }
        .sortedByDescending { it.dateIso }
}

/**
 * Estimate one month's interest/principal split for a detected payment. This is a planning estimate,
 * not a lender statement: daily balance changes, fees, grace periods, and lender accrual rules can differ.
 */
fun estimateDebtPaymentBreakdown(debt: Debt, paymentAmount: Double): DebtPaymentBreakdown {
    val payment = paymentAmount.coerceAtLeast(0.0)
    val estimatedMonthlyInterest = debt.balance.coerceAtLeast(0.0) * (debt.apr.coerceAtLeast(0.0) / 1200.0)
    val interest = min(payment, estimatedMonthlyInterest)
    return DebtPaymentBreakdown(
        paymentAmount = payment,
        estimatedInterest = interest,
        estimatedPrincipal = (payment - interest).coerceAtLeast(0.0)
    )
}

fun calculateNextPaycheckPlan(
    data: AppData,
    referenceDate: LocalDate = LocalDate.now()
): PaycheckPlan? {
    val payday = data.paydays
        .filter { !it.nextDate().isBefore(referenceDate) }
        .minByOrNull { it.nextDate() }
        ?: return null
    val payDate = payday.nextDate()
    val followingPayDate = when (payday.frequency) {
        Frequency.ONE_TIME -> payDate.plusDays(30)
        Frequency.WEEKLY -> payDate.plusWeeks(1)
        Frequency.BIWEEKLY -> payDate.plusWeeks(2)
        Frequency.MONTHLY -> payDate.plusMonths(1)
        Frequency.YEARLY -> payDate.plusYears(1)
    }
    val bills = data.bills.filter { bill ->
        if (bill.isPaidFor()) return@filter false
        val due = runCatching { bill.dueDate() }.getOrNull() ?: return@filter false
        !due.isBefore(payDate) && due.isBefore(followingPayDate)
    }.sumOf { it.amount.coerceAtLeast(0.0) }
    val reserves = data.reservedFunds.sumOf { it.paydayContribution.coerceAtLeast(0.0) }
    val savings = data.savingsGoals.sumOf { it.paydayContribution.coerceAtLeast(0.0) }
    val cyclesPerMonth = when (payday.frequency) {
        Frequency.WEEKLY -> 52.0 / 12.0
        Frequency.BIWEEKLY -> 26.0 / 12.0
        Frequency.MONTHLY, Frequency.ONE_TIME, Frequency.YEARLY -> 1.0
    }
    val debtMinimums = data.debts.sumOf { it.minimumPayment.coerceAtLeast(0.0) } / cyclesPerMonth
    val variableBudgets = data.budgets.sumOf { budget ->
        when (budget.period) {
            BudgetPeriod.PAYCHECK -> if (budget.paydayId == null || budget.paydayId == payday.id) budget.amount else 0.0
            BudgetPeriod.WEEKLY -> budget.amount * when (payday.frequency) {
                Frequency.WEEKLY -> 1.0
                Frequency.BIWEEKLY -> 2.0
                Frequency.MONTHLY -> 52.0 / 12.0
                Frequency.YEARLY -> 52.0
                Frequency.ONE_TIME -> 1.0
            }
            BudgetPeriod.BIWEEKLY -> budget.amount * when (payday.frequency) {
                Frequency.WEEKLY -> 0.5
                Frequency.BIWEEKLY -> 1.0
                Frequency.MONTHLY -> 26.0 / 12.0
                Frequency.YEARLY -> 26.0
                Frequency.ONE_TIME -> 1.0
            }
            BudgetPeriod.MONTHLY -> budget.amount / cyclesPerMonth
            BudgetPeriod.YEARLY -> budget.amount / (12.0 * cyclesPerMonth)
            BudgetPeriod.CUSTOM -> 0.0
        }
    }
    val allocated = bills + reserves + savings + debtMinimums + variableBudgets

    return PaycheckPlan(
        paydayId = payday.id,
        paydayLabel = payday.label,
        dateIso = payday.nextDateIso,
        income = payday.amount.coerceAtLeast(0.0),
        bills = bills,
        reserves = reserves,
        savings = savings,
        debtMinimums = debtMinimums,
        variableBudgets = variableBudgets,
        unassigned = payday.amount.coerceAtLeast(0.0) - allocated
    )
}
