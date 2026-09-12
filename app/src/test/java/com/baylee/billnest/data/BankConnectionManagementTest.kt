package com.baylee.billnest.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BankConnectionManagementTest {
    @Test
    fun deletePathEncodesPlaidItemId() {
        assertEquals(
            "/api/plaid/items/item%2Fwith+space",
            bankConnectionDeletePath("item/with space")
        )
    }

    @Test
    fun parsesConnectedBankItems() {
        val result = parseBankConnectionsJson(
            """{"items":[{"itemId":"item-1","label":"Main Bank","createdAt":"2026-09-12T00:00:00.000Z"}]}"""
        )

        assertEquals(1, result.size)
        assertEquals("item-1", result.first().itemId)
        assertEquals("Main Bank", result.first().label)
    }

    @Test
    fun refreshAvailabilityDependsOnConfiguredBackendNotCachedAccounts() {
        assertEquals(true, canRefreshBanks("https://billnest-api.example.com"))
        assertEquals(false, canRefreshBanks(""))
    }
}
