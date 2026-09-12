package com.baylee.billnest.model

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
}
