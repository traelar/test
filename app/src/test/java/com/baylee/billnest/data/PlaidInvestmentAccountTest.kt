package com.baylee.billnest.data

import com.baylee.billnest.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaidInvestmentAccountTest {
    @Test fun retirementSubtypesAreClassifiedAsInvestments() {
        assertEquals(AccountType.INVESTMENT, plaidAccountType("investment", "401k"))
        assertEquals(AccountType.INVESTMENT, plaidAccountType("investment", "retirement"))
        assertEquals(AccountType.INVESTMENT, plaidAccountType("investment", "other"))
    }

    @Test fun creditCardsAreClassifiedAsCreditAccounts() {
        assertEquals(AccountType.CREDIT, plaidAccountType("credit", "credit card"))
    }
}
