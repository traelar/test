package com.baylee.billnest.model

data class SessionData(
    val sessionToken: String,
    val userId: String,
    val username: String,
    val householdId: String,
    val householdName: String,
    val role: String,
    val displayLabel: String
) {
    val isOwner: Boolean get() = role.equals("owner", ignoreCase = true)
}

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object SignedOut : AuthUiState
    data class SignedIn(val session: SessionData) : AuthUiState
}

data class HouseholdMember(
    val userId: String,
    val username: String,
    val role: String,
    val displayLabel: String,
    val joinedAt: String
)

data class HouseholdDetails(
    val householdId: String,
    val name: String,
    val ownerUserId: String,
    val members: List<HouseholdMember>
)

data class HouseholdInvite(
    val inviteCode: String,
    val expiresAt: String
)
