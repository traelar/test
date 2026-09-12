package com.baylee.billnest.model

enum class TransactionClassification { SPENDING, INCOME, TRANSFER }

/**
 * Apply a user-owned classification to an existing transaction. The override is
 * persisted independently of the Plaid-owned transaction details so later bank
 * refreshes cannot silently turn a transfer back into income or spending.
 */
fun reclassifyTransaction(
    transaction: FinanceTransaction,
    classification: TransactionClassification,
    fromAccountId: String? = null,
    toAccountId: String? = null,
    spendingCategory: String = transaction.category
): FinanceTransaction = when (classification) {
    TransactionClassification.SPENDING -> transaction.copy(
        category = spendingCategory.ifBlank { "Other" },
        transfer = false,
        income = false,
        transferFromAccountId = null,
        transferToAccountId = null,
        userClassificationOverride = true
    )
    TransactionClassification.INCOME -> transaction.copy(
        category = "Income",
        transfer = false,
        income = true,
        transferFromAccountId = null,
        transferToAccountId = null,
        userClassificationOverride = true
    )
    TransactionClassification.TRANSFER -> transaction.copy(
        category = "Transfer",
        transfer = true,
        income = false,
        transferFromAccountId = fromAccountId,
        transferToAccountId = toAccountId,
        userClassificationOverride = true
    )
}

/**
 * Remove a transaction from BillNest. Plaid-backed transactions leave behind a
 * local tombstone so a later Plaid refresh cannot restore the deleted row.
 */
fun deleteFinanceTransaction(data: AppData, transactionId: String): AppData {
    val existing = data.transactions.firstOrNull { it.id == transactionId } ?: return data
    val deletedPlaidIds = if (existing.source == TransactionSource.PLAID) {
        (data.deletedPlaidTransactionIds + transactionId).distinct()
    } else {
        data.deletedPlaidTransactionIds
    }
    return data.copy(
        transactions = data.transactions.filterNot { it.id == transactionId },
        deletedPlaidTransactionIds = deletedPlaidIds
    )
}

/**
 * Refresh Plaid-owned transaction details without overwriting a classification the
 * user explicitly set inside BillNest. Transactions the user deleted stay hidden.
 */
fun mergePlaidTransactions(
    existing: List<FinanceTransaction>,
    incoming: List<FinanceTransaction>,
    deletedPlaidTransactionIds: Set<String> = emptySet()
): List<FinanceTransaction> {
    val allowedExisting = existing.filterNot { it.id in deletedPlaidTransactionIds }
    val allowedIncoming = incoming.filterNot { it.id in deletedPlaidTransactionIds }
    val existingById = allowedExisting.associateBy { it.id }
    val refreshed = allowedIncoming.map { fresh ->
        val saved = existingById[fresh.id]
        if (saved?.userClassificationOverride == true) {
            fresh.copy(
                category = saved.category,
                transfer = saved.transfer,
                income = saved.income,
                transferFromAccountId = saved.transferFromAccountId,
                transferToAccountId = saved.transferToAccountId,
                userClassificationOverride = true
            )
        } else {
            fresh
        }
    }
    val incomingIds = allowedIncoming.map { it.id }.toSet()
    val retained = allowedExisting.filterNot { it.id in incomingIds }
    return (retained + refreshed).sortedByDescending { it.dateIso }
}