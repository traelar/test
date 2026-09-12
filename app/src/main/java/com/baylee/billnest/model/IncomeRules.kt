package com.baylee.billnest.model

/**
 * Transactions that should be presented as income in BillNest.
 * User-classified transfers always win over Plaid's inferred deposit direction.
 */
fun visibleIncomeTransactions(transactions: List<FinanceTransaction>): List<FinanceTransaction> =
    transactions.filter { row ->
        !row.transfer && (
            row.income ||
                row.category.equals("Income", true) ||
                (row.source == TransactionSource.PLAID && row.amount < 0.0)
            )
    }
