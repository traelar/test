package com.baylee.billnest.data

import com.baylee.billnest.model.SessionData
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class AuthApi(
    private val client: HttpApiClient = HttpApiClient()
) {
    suspend fun bootstrap(
        backendUrl: String,
        legacyApiKey: String,
        username: String,
        password: String,
        householdName: String
    ): SessionData {
        val body = JsonObject().apply {
            addProperty("username", username.trim())
            addProperty("password", password)
            addProperty("householdName", householdName.trim())
        }
        val json = client.request(
            backendUrl = backendUrl,
            path = "/api/auth/bootstrap",
            method = "POST",
            bearerToken = legacyApiKey,
            jsonBody = body.toString()
        )
        return parseSession(json)
    }

    suspend fun login(
        backendUrl: String,
        username: String,
        password: String
    ): SessionData {
        val body = JsonObject().apply {
            addProperty("username", username.trim())
            addProperty("password", password)
        }
        val json = client.request(
            backendUrl = backendUrl,
            path = "/api/auth/login",
            method = "POST",
            jsonBody = body.toString()
        )
        return parseSession(json)
    }

    suspend fun registerWithInvite(
        backendUrl: String,
        inviteCode: String,
        username: String,
        password: String
    ): SessionData {
        val body = JsonObject().apply {
            addProperty("inviteCode", inviteCode.trim())
            addProperty("username", username.trim())
            addProperty("password", password)
        }
        val json = client.request(
            backendUrl = backendUrl,
            path = "/api/auth/register",
            method = "POST",
            jsonBody = body.toString()
        )
        return parseSession(json)
    }

    suspend fun fetchMe(
        backendUrl: String,
        sessionToken: String
    ): SessionData {
        val json = client.request(
            backendUrl = backendUrl,
            path = "/api/me",
            bearerToken = sessionToken
        )
        return parseSession(json, sessionToken)
    }

    suspend fun logout(
        backendUrl: String,
        sessionToken: String
    ) {
        client.request(
            backendUrl = backendUrl,
            path = "/api/auth/logout",
            method = "POST",
            bearerToken = sessionToken,
            jsonBody = "{}"
        )
    }

    private fun parseSession(rawJson: String, existingToken: String? = null): SessionData {
        val root = JsonParser.parseString(rawJson).asJsonObject
        val user = root.getAsJsonObject("user") ?: error("BillNest server response is missing user data")
        val household = root.getAsJsonObject("household") ?: error("BillNest server response is missing household data")
        val token = existingToken ?: root.get("sessionToken")?.asString.orEmpty()
        if (token.isBlank()) error("BillNest server response is missing the session token")

        return SessionData(
            sessionToken = token,
            userId = user.get("userId")?.asString.orEmpty(),
            username = user.get("username")?.asString.orEmpty(),
            householdId = household.get("householdId")?.asString.orEmpty(),
            householdName = household.get("name")?.asString.orEmpty(),
            role = household.get("role")?.asString.orEmpty(),
            displayLabel = household.get("displayLabel")?.asString.orEmpty()
        ).also {
            require(it.userId.isNotBlank() && it.householdId.isNotBlank()) {
                "BillNest server response is incomplete"
            }
        }
    }
}
