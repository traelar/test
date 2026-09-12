package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceAutomationTest {
    @Test
    fun `exact amount date and account produces automatic bill match`() {
        val bill = Bill(name = "Mortgage", amount = 1800.0, dueDateIso = "2026-09-01", accountId = "plaid:checking")
        val tx = FinanceTransaction(
            id = "plaid:tx1",
            source = TransactionSource.PLAID,
            accountKey = "plaid:checking",
            dateIso = "2026-09-01",
            name = "MORTGAGE PAYMENT",
            amount = 1800.0,
            type = TransactionType.EXPENSE
        )

        val proposal = BillMatcher.bestProposal(tx, listOf(bill))!!
        assertEquals(MatchStatus.AUTO_MATCHED, proposal.status)
        assertTrue(proposal.score >= BillMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun `ambiguous amount match goes to review instead of auto paid`() {
        val bill = Bill(name = "Internet", amount = 80.0, dueDateIso = "2026-09-10")
        val tx = FinanceTransaction(
            id = "plaid:tx2",
            source = TransactionSource.PLAID,
            dateIso = "2026-09-14",
            name = "Utility Payment",
            amount = 80.0,
            type = TransactionType.EXPENSE
        )

        val proposal = BillMatcher.bestProposal(tx, listOf(bill))!!
        assertEquals(MatchStatus.NEEDS_REVIEW, proposal.status)
    }

    @Test
    fun `income transfer pending and excluded transactions never match bills`() {
        val bill = Bill(name = "Phone", amount = 100.0, dueDateIso = "2026-09-10")
        fun tx(type: TransactionType, pending: Boolean = false, excluded: Boolean = false) = FinanceTransaction(
            name = "Phone", amount = 100.0, dateIso = "2026-09-10", type = type,
            pending = pending, excludedFromSpending = excluded
        )
        assertEquals(null, BillMatcher.bestProposal(tx(TransactionType.INCOME), listOf(bill)))
        assertEquals(null, BillMatcher.bestProposal(tx(TransactionType.TRANSFER), listOf(bill)))
        assertEquals(null, BillMatcher.bestProposal(tx(TransactionType.EXPENSE, pending = true), listOf(bill)))
        assertEquals(null, BillMatcher.bestProposal(tx(TransactionType.EXPENSE, excluded = true), listOf(bill)))
    }

    @Test
    fun `recurring detector finds similar merchant charges across months`() {
        val transactions = listOf(
            FinanceTransaction(id = "1", name = "NETFLIX.COM", merchantName = "Netflix", amount = 22.99, dateIso = "2026-06-05", type = TransactionType.EXPENSE),
            FinanceTransaction(id = "2", name = "NETFLIX.COM", merchantName = "Netflix", amount = 22.99, dateIso = "2026-07-05", type = TransactionType.EXPENSE),
            FinanceTransaction(id = "3", name = "NETFLIX.COM", merchantName = "Netflix", amount = 22.99, dateIso = "2026-08-05", type = TransactionType.EXPENSE)
        )

        val recurring = RecurringDetector.detect(transactions)
        assertEquals(1, recurring.size)
        assertEquals("Netflix", recurring.first().displayName)
        assertTrue(recurring.first().monthlyEstimate > 22.0)
    }
}
