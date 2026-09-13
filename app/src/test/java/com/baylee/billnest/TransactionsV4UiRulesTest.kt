package com.baylee.billnest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionsV4UiRulesTest {
    @Test
    fun transactionActionsUsePhoneSafeRows() {
        val method = Class.forName("com.baylee.billnest.TransactionsPageV4Kt")
            .declaredMethods
            .firstOrNull { it.name == "transactionV4ActionRows" }

        assertNotNull("Stage A transaction actions need a phone-safe row plan", method)
        method!!.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val rows = method.invoke(null, true) as List<List<*>>
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.size <= 2 })
        assertEquals(listOf("EDIT", "SUBSCRIPTION"), rows[0].map { it.toString() })
        assertEquals(listOf("RULE", "DELETE"), rows[1].map { it.toString() })
    }
}
