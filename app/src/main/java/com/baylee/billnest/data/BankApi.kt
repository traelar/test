package com.baylee.billnest.data

import com.baylee.billnest.model.Account
import com.baylee.billnest.model.AccountSource
import com.baylee.billnest.model.AccountType
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class PlaidLinkTokenResponse(val linkToken: String = "")
data class PlaidAccountDto(
    val accountId: String = "",
    val name: String = "Account",
    val mask: String = "",
    val type: String = "",
    val subtype: String = "",
    val current: Double = 0.0,
    val available: Double? = null,
    val connectionLabel: String? = null
)
data class PlaidAccountsResponse(val accounts: List<PlaidAccountDto> = emptyList())

object BankApi {
    private val gson = Gson()

    private fun base(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.startsWith("http://") || value.startsWith("https://")) {
            "Enter a backend address starting with http:// or https://"
        }
        return value
    }

    suspend fun createLinkToken(backendUrl: String): String = withContext(Dispatchers.IO) {
        val json = request(base(backendUrl) + "/api/plaid/link-token", "POST", "{}")
        val token = gson.fromJson(json, PlaidLinkTokenResponse::class.java).linkToken
        if (token.isBlank()) throw IOException("Backend did not return a Plaid link token")
        token
    }

    suspend fun exchangePublicToken(backendUrl: String, publicToken: String, label: String?) = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("publicToken" to publicToken, "label" to (label ?: "Bank")))
        request(base(backendUrl) + "/api/plaid/exchange", "POST", body)
    }

    suspend fun fetchAccounts(backendUrl: String): List<Account> = withContext(Dispatchers.IO) {
        val json = request(base(backendUrl) + "/api/plaid/accounts", "GET", null)
        gson.fromJson(json, PlaidAccountsResponse::class.java).accounts
            .filter { it.type.equals("depository", ignoreCase = true) || it.type.isBlank() }
            .map { dto ->
                Account(
                    name = dto.name,
                    type = when (dto.subtype.lowercase()) {
                        "checking" -> AccountType.CHECKING
                        "savings", "money market" -> AccountType.SAVINGS
                        else -> AccountType.OTHER
                    },
                    balance = dto.available ?: dto.current,
                    source = AccountSource.PLAID,
                    plaidAccountId = dto.accountId,
                    mask = dto.mask,
                    connectionLabel = dto.connectionLabel
                )
            }
    }

    private fun request(url: String, method: String, body: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
            doInput = true
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Bank server error $code: ${text.take(240)}")
            text
        } finally {
            connection.disconnect()
        }
    }
}
