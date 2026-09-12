package com.baylee.billnest.model

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
