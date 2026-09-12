package com.baylee.billnest.model

import java.time.LocalDate

/**
 * Transactions that should be presented as income in BillNest.
 * Once a user explicitly classifies a transaction, that choice wins over Plaid's
 * inferred direction/amount sign.
 */
fun visibleIncomeTransactions(transactions: List<FinanceTransaction>): List<FinanceTransaction> =
    transactions.filter { row ->
        if (row.transfer) return@filter false
        if (row.userClassificationOverride) {
            return@filter row.income || row.category.equals("Income", true)
        }
        row.income ||
            row.category.equals("Income", true) ||
            (row.source == TransactionSource.PLAID && row.amount < 0.0)
    }

/**
 * Income rows shown in the UI. The window is inclusive, so 30 days means today
 * plus the previous 29 calendar days. Bad/future dates are ignored rather than
 * crashing or leaking into the visible income list.
 */
fun recentVisibleIncomeTransactions(
    transactions: List<FinanceTransaction>,
    referenceDate: LocalDate = LocalDate.now(),
    days: Long = 30
): List<FinanceTransaction> {
    require(days > 0) { "Income window must be positive" }
    val start = referenceDate.minusDays(days - 1)
    return visibleIncomeTransactions(transactions).filter { row ->
        val date = runCatching { LocalDate.parse(row.dateIso) }.getOrNull() ?: return@filter false
        !date.isBefore(start) && !date.isAfter(referenceDate)
    }
}
