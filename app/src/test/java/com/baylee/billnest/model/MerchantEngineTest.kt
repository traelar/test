package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantEngineTest {
    @Test
    fun profileAliasCreatesBillNestDisplayNameWithoutChangingRawName() {
        val profile = MerchantProfile(
            id = "walmart",
            displayName = "Walmart",
            aliases = listOf("WM SUPERCENTER", "WAL MART"),
            preferredCategory = "Groceries"
        )
        val tx = FinanceTransaction(
            id = "tx",
            name = "WM SUPERCENTER #1234",
            amount = 51.25,
            dateIso = "2026-09-13"
        )

        val result = applyMerchantProfiles(listOf(tx), listOf(profile), AppData()).single()

        assertEquals("WM SUPERCENTER #1234", result.name)
        assertEquals("Walmart", result.displayNameOverride)
        assertEquals("walmart", result.merchantProfileId)
        assertEquals("Groceries", result.category)
    }

    @Test
    fun longestMatchingAliasWinsDeterministically() {
        val generic = MerchantProfile(
            id = "generic",
            displayName = "Target",
            aliases = listOf("TARGET"),
            updatedAtEpochMs = 200L
        )
        val specific = MerchantProfile(
            id = "specific",
            displayName = "Target Optical",
            aliases = listOf("TARGET OPTICAL"),
            updatedAtEpochMs = 100L
        )
        val tx = FinanceTransaction(name = "TARGET OPTICAL 0042", amount = 30.0, dateIso = "2026-09-13")

        assertEquals("specific", resolveMerchantProfile(tx, listOf(generic, specific))?.id)
    }

    @Test
    fun merchantIdentityNormalizationRemovesPunctuationAndStoreNumbers() {
        assertEquals("wal mart", merchantIdentityKey("WAL-MART #1234"))
        assertEquals("wm supercenter", merchantIdentityKey("  WM SUPERCENTER 1234 "))
    }

    @Test
    fun preferredCategoryReusesExistingCanonicalCategoryName() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(name = "Cafe", amount = 8.0, dateIso = "2026-09-12", category = "Work Drinks")
            )
        )
        val profile = MerchantProfile(
            id = "coffee",
            displayName = "Coffee Shop",
            aliases = listOf("COFFEE SHOP"),
            preferredCategory = " work   drinks "
        )
        val tx = FinanceTransaction(name = "COFFEE SHOP 22", amount = 5.0, dateIso = "2026-09-13")

        val result = applyMerchantProfile(tx, profile, data)

        assertEquals("Work Drinks", result.category)
    }

    @Test
    fun explicitUserClassificationIsNotOverwrittenByMerchantDefaults() {
        val profile = MerchantProfile(
            id = "payroll",
            displayName = "Employer",
            aliases = listOf("EMPLOYER"),
            preferredCategory = "Income",
            defaultClassification = TransactionClassification.INCOME
        )
        val tx = FinanceTransaction(
            name = "EMPLOYER ADJUSTMENT",
            amount = 25.0,
            dateIso = "2026-09-13",
            category = "Other",
            userClassificationOverride = true,
            income = false,
            transfer = false
        )

        val result = applyMerchantProfile(tx, profile, AppData())

        assertEquals("Other", result.category)
        assertFalse(result.income)
        assertFalse(result.transfer)
        assertTrue(result.userClassificationOverride)
        assertEquals("Employer", result.displayNameOverride)
    }
}
