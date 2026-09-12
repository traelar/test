package com.baylee.billnest.model

import com.google.gson.Gson

/** Sync codec for Alpha19 household finance records. Kept separate from the legacy mapper
 * so the new records can evolve without disturbing older migrations. */
object Alpha19SyncMapper {
    private val gson = Gson()

    fun encodeTransactionRule(value: TransactionRule): String = gson.toJson(value)
    fun decodeTransactionRule(value: String): TransactionRule = gson.fromJson(value, TransactionRule::class.java)
    fun transactionRuleMutation(value: TransactionRule) = SyncRecordDraft(
        kind = "transaction_rule",
        recordId = value.id,
        payloadJson = encodeTransactionRule(value)
    )

    fun encodeTransactionTombstone(value: TransactionTombstone): String = gson.toJson(value)
    fun decodeTransactionTombstone(value: String): TransactionTombstone = gson.fromJson(value, TransactionTombstone::class.java)
    fun transactionTombstoneMutation(value: TransactionTombstone) = SyncRecordDraft(
        kind = "transaction_tombstone",
        recordId = value.transactionId,
        payloadJson = encodeTransactionTombstone(value)
    )

    fun encodeFinancialSnapshot(value: FinancialSnapshot): String = gson.toJson(value)
    fun decodeFinancialSnapshot(value: String): FinancialSnapshot = gson.fromJson(value, FinancialSnapshot::class.java)
    fun financialSnapshotMutation(value: FinancialSnapshot) = SyncRecordDraft(
        kind = "financial_snapshot",
        recordId = value.id,
        payloadJson = encodeFinancialSnapshot(value)
    )
}
