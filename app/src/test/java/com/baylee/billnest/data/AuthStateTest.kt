package com.baylee.billnest.data

import com.baylee.billnest.model.AuthUiState
import com.baylee.billnest.model.SessionData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStateTest {
    @Test
    fun ownerSessionReportsOwnerRole() {
        val session = SessionData(
            sessionToken = "token",
            userId = "u1",
            username = "baylee",
            householdId = "h1",
            householdName = "Our Household",
            role = "owner",
            displayLabel = "baylee"
        )
        assertTrue(session.isOwner)
        assertTrue(AuthUiState.SignedIn(session).session.isOwner)
    }

    @Test
    fun memberSessionDoesNotReportOwnerRole() {
        val session = SessionData(
            sessionToken = "token",
            userId = "u2",
            username = "member",
            householdId = "h1",
            householdName = "Our Household",
            role = "member",
            displayLabel = "member"
        )
        assertFalse(session.isOwner)
    }
}
