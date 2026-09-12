package com.baylee.billnest.data

import com.baylee.billnest.model.HouseholdDetails
import com.baylee.billnest.model.HouseholdInvite
import com.baylee.billnest.model.HouseholdMember
import com.google.gson.JsonParser

class HouseholdApi(
    private val client: HttpApiClient = HttpApiClient()
) {
    suspend fun fetchHousehold(
        backendUrl: String,
        sessionToken: String
    ): HouseholdDetails {
        val raw = client.request(
            backendUrl = backendUrl,
            path = "/api/household",
            bearerToken = sessionToken
        )
        val root = JsonParser.parseString(raw).asJsonObject
        val household = root.getAsJsonObject("household")
            ?: error("BillNest server response is missing household data")
        val members = root.getAsJsonArray("members")?.map { element ->
            val member = element.asJsonObject
            HouseholdMember(
                userId = member.get("userId")?.asString.orEmpty(),
                username = member.get("username")?.asString.orEmpty(),
                role = member.get("role")?.asString.orEmpty(),
                displayLabel = member.get("displayLabel")?.asString.orEmpty(),
                joinedAt = member.get("joinedAt")?.asString.orEmpty()
            )
        }.orEmpty()
        return HouseholdDetails(
            householdId = household.get("householdId")?.asString.orEmpty(),
            name = household.get("name")?.asString.orEmpty(),
            ownerUserId = household.get("ownerUserId")?.asString.orEmpty(),
            members = members
        )
    }

    suspend fun createInvite(
        backendUrl: String,
        sessionToken: String
    ): HouseholdInvite {
        val raw = client.request(
            backendUrl = backendUrl,
            path = "/api/household/invites",
            method = "POST",
            bearerToken = sessionToken,
            jsonBody = "{}"
        )
        val root = JsonParser.parseString(raw).asJsonObject
        return HouseholdInvite(
            inviteCode = root.get("inviteCode")?.asString.orEmpty(),
            expiresAt = root.get("expiresAt")?.asString.orEmpty()
        ).also { require(it.inviteCode.isNotBlank()) { "BillNest server did not return an invite code" } }
    }

    suspend fun removeMember(
        backendUrl: String,
        sessionToken: String,
        userId: String
    ) {
        client.request(
            backendUrl = backendUrl,
            path = "/api/household/members/${java.net.URLEncoder.encode(userId, Charsets.UTF_8.name())}",
            method = "DELETE",
            bearerToken = sessionToken
        )
    }
}
