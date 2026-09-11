package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountModelTest {
    @Test
    fun accountSupportsCustomNameTypeAndBalance() {
        val account = Account(
            name = "Bills Checking",
            type = AccountType.CHECKING,
            balance = 1234.56
        )
        assertEquals("Bills Checking", account.name)
        assertEquals(AccountType.CHECKING, account.type)
        assertEquals(1234.56, account.balance, 0.001)
    }

    @Test
    fun billCanBeAssignedToAnAccount() {
        val bill = Bill(
            name = "Electric",
            amount = 125.0,
            dueDateIso = "2026-09-20",
            accountId = "checking-1"
        )
        assertEquals("checking-1", bill.accountId)
    }
}
