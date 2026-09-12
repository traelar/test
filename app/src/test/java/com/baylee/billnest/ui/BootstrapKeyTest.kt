package com.baylee.billnest.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BootstrapKeyTest {
    @Test
    fun enteredKeyIsUsedWhenPhoneHasNoSavedKey() {
        assertEquals("entered-key", resolveBootstrapServerKey("", " entered-key "))
    }

    @Test
    fun savedKeyIsUsedWhenSetupFieldIsBlank() {
        assertEquals("saved-key", resolveBootstrapServerKey("saved-key", ""))
    }

    @Test
    fun setupRejectsWhenNeitherKeyExists() {
        assertThrows(IllegalStateException::class.java) {
            resolveBootstrapServerKey("", "")
        }
    }
}
