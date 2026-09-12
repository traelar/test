package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Alpha19SyncMapperTest {
    @Test
    fun transactionRuleRoundTripsAndUsesExpectedKind() {
        val rule = TransactionRule(
            id = "rule-1",
            merchantContains = "walmart",
            renameTo = "Walmart Grocery",
            category = "Groceries",
            excludeFromSpending = true,
            updatedAtEpochMs = 123L
        )
        val draft = SyncMapper.transactionRuleMutation(rule)
        val decoded = SyncMapper.decodeTransactionRule(draft.payloadJson)
        assertEquals("transaction_rule", draft.kind)
        assertEquals(rule, decoded)
    }

    @Test
    fun tombstoneRoundTripsAndUsesTransactionIdAsRecordId() {
        val tombstone = TransactionTombstone(transactionId = "tx-1", createdAtEpochMs = 456L)
        val draft = SyncMapper.transactionTombstoneMutation(tombstone)
        val decoded = SyncMapper.decodeTransactionTombstone(draft.payloadJson)
        assertEquals("transaction_tombstone", draft.kind)
        assertEquals("tx-1", draft.recordId)
        assertEquals(tombstone, decoded)
    }

    @Test
    fun financialSnapshotRoundTripsAndUsesDateRecordId() {
        val snapshot = FinancialSnapshot(
            id = "2026-09-12",
            dateIso = "2026-09-12",
            assets = 5000.0,
            debts = 1000.0,
            netWorth = 4000.0
        )
        val draft = SyncMapper.financialSnapshotMutation(snapshot)
        val decoded = SyncMapper.decodeFinancialSnapshot(draft.payloadJson)
        assertEquals("financial_snapshot", draft.kind)
        assertEquals("2026-09-12", draft.recordId)
        assertEquals(snapshot, decoded)
        assertTrue(decoded.netWorth > 0.0)
    }
}
