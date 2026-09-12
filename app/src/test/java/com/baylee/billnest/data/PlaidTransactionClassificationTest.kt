package com.baylee.billnest.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaidTransactionClassificationTest {
    @Test
    fun transferOutAndTransferInCategoriesAreRecognizedAsTransfers() {
        assertTrue(isPlaidTransferCategory("TRANSFER_OUT"))
        assertTrue(isPlaidTransferCategory("Transfer out"))
        assertTrue(isPlaidTransferCategory("TRANSFER_IN"))
        assertFalse(isPlaidTransferCategory("FOOD_AND_DRINK"))
    }
}
