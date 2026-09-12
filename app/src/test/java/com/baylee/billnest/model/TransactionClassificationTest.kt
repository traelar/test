package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionClassificationTest {
    @Test
    fun financeTransactionStoresManualTransferClassificationMetadata() {
        val fields = FinanceTransaction::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("FinanceTransaction should store the manually selected source account", "transferFromAccountId" in fields)
        assertTrue("FinanceTransaction should store the manually selected destination account", "transferToAccountId" in fields)
        assertTrue("FinanceTransaction should remember that the user overrode Plaid's classification", "userClassificationOverride" in fields)
    }

    @Test
    fun plaidRefreshPreservesManualTransferClassification() {
        val existing = FinanceTransaction(
            id = "plaid:tx-1",
            name = "Transfer deposit",
            amount = -500.0,
            dateIso = "2026-09-10",
            category = "Transfer",
            accountId = "checking",
            source = TransactionSource.PLAID,
            transfer = true,
            income = false,
            transferFromAccountId = "savings",
            transferToAccountId = "checking",
            userClassificationOverride = true
        )
        val refreshed = FinanceTransaction(
            id = "plaid:tx-1",
            name = "Bank transfer deposit",
            amount = -500.0,
            dateIso = "2026-09-10",
            category = "Income",
            accountId = "checking",
            source = TransactionSource.PLAID,
            transfer = false,
            income = true
        )

        val merged = mergePlaidTransactions(listOf(existing), listOf(refreshed)).single { it.id == existing.id }

        assertTrue(merged.transfer)
        assertEquals(false, merged.income)
        assertEquals("Transfer", merged.category)
        assertEquals("savings", merged.transferFromAccountId)
        assertEquals("checking", merged.transferToAccountId)
        assertTrue(merged.userClassificationOverride)
        assertEquals("Bank transfer deposit", merged.name)
    }

    @Test
    fun reclassifyIncomeAsTransferSetsFromAndToAccounts() {
        val original = FinanceTransaction(
            id = "plaid:income-1",
            name = "ACH deposit",
            amount = -400.0,
            dateIso = "2026-09-11",
            category = "Income",
            accountId = "checking",
            source = TransactionSource.PLAID,
            income = true
        )

        val updated = reclassifyTransaction(
            original,
            TransactionClassification.TRANSFER,
            fromAccountId = "savings",
            toAccountId = "checking",
            spendingCategory = "Other"
        )

        assertTrue(updated.transfer)
        assertFalse(updated.income)
        assertEquals("Transfer", updated.category)
        assertEquals("savings", updated.transferFromAccountId)
        assertEquals("checking", updated.transferToAccountId)
        assertTrue(updated.userClassificationOverride)
    }

    @Test
    fun reclassifyingBackToIncomeClearsTransferAccounts() {
        val original = FinanceTransaction(
            id = "plaid:transfer-1",
            name = "Deposit",
            amount = -250.0,
            dateIso = "2026-09-11",
            category = "Transfer",
            transfer = true,
            transferFromAccountId = "savings",
            transferToAccountId = "checking",
            userClassificationOverride = true
        )

        val updated = reclassifyTransaction(original, TransactionClassification.INCOME)

        assertFalse(updated.transfer)
        assertTrue(updated.income)
        assertEquals("Income", updated.category)
        assertEquals(null, updated.transferFromAccountId)
        assertEquals(null, updated.transferToAccountId)
        assertTrue(updated.userClassificationOverride)
    }

    @Test
    fun incomeScreenRowsExcludeTransactionsReclassifiedAsTransfers() {
        val paycheck = FinanceTransaction(
            id = "paycheck",
            name = "Employer payroll",
            amount = -1200.0,
            dateIso = "2026-09-12",
            category = "Income",
            source = TransactionSource.PLAID,
            income = true
        )
        val transfer = paycheck.copy(
            id = "transfer",
            name = "Savings transfer",
            category = "Transfer",
            income = false,
            transfer = true,
            transferFromAccountId = "savings",
            transferToAccountId = "checking",
            userClassificationOverride = true
        )
        val inferredDeposit = paycheck.copy(
            id = "deposit",
            name = "Refund deposit",
            category = "Other",
            income = false
        )

        val rows = visibleIncomeTransactions(listOf(paycheck, transfer, inferredDeposit))

        assertEquals(listOf("paycheck", "deposit"), rows.map { it.id })
        assertFalse(rows.any { it.transfer })
    }

    @Test
    fun incomeScreenHonorsManualSpendingOverrideForPlaidDeposits() {
        val overridden = FinanceTransaction(
            id = "plaid:spending-override",
            name = "Reclassified deposit",
            amount = -90.0,
            dateIso = "2026-09-12",
            category = "Other",
            source = TransactionSource.PLAID,
            income = false,
            transfer = false,
            userClassificationOverride = true
        )

        assertFalse(visibleIncomeTransactions(listOf(overridden)).any { it.id == overridden.id })
    }

    @Test
    fun deletingPlaidTransactionRemovesItAndCreatesPersistentTombstone() {
        val plaid = FinanceTransaction(
            id = "plaid:test-deposit",
            name = "Deposit test",
            amount = -0.01,
            dateIso = "2026-09-12",
            source = TransactionSource.PLAID,
            income = true
        )
        val result = deleteFinanceTransaction(
            AppData(transactions = listOf(plaid)),
            plaid.id
        )

        assertFalse(result.transactions.any { it.id == plaid.id })
        assertTrue(plaid.id in result.deletedPlaidTransactionIds)
    }

    @Test
    fun deletingManualTransactionDoesNotCreatePlaidTombstone() {
        val manual = FinanceTransaction(
            id = "manual:test-deposit",
            name = "Manual test",
            amount = 1.0,
            dateIso = "2026-09-12",
            source = TransactionSource.MANUAL,
            income = true
        )
        val result = deleteFinanceTransaction(
            AppData(transactions = listOf(manual)),
            manual.id
        )

        assertFalse(result.transactions.any { it.id == manual.id })
        assertTrue(result.deletedPlaidTransactionIds.isEmpty())
    }

    @Test
    fun plaidRefreshDoesNotRestoreDeletedPlaidTransaction() {
        val deletedId = "plaid:test-deposit"
        val incoming = FinanceTransaction(
            id = deletedId,
            name = "Deposit test",
            amount = -0.01,
            dateIso = "2026-09-12",
            source = TransactionSource.PLAID,
            income = true
        )

        val merged = mergePlaidTransactions(
            existing = emptyList(),
            incoming = listOf(incoming),
            deletedPlaidTransactionIds = setOf(deletedId)
        )

        assertFalse(merged.any { it.id == deletedId })
    }
}
