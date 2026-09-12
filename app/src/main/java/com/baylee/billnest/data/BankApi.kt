package com.baylee.billnest.data

import com.baylee.billnest.model.Account
import com.baylee.billnest.model.AccountSource
import com.baylee.billnest.model.AccountType
import com.baylee.billnest.model.FinanceTransaction
import com.baylee.billnest.model.TransactionSource
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

fun plaidAccountType(type: String, subtype: String): AccountType = when (subtype.lowercase()) {
    "checking" -> AccountType.CHECKING
    "savings", "money market" -> AccountType.SAVINGS
    "401k", "403b", "457b", "ira", "roth", "roth 401k", "pension", "retirement", "brokerage" -> AccountType.INVESTMENT
    "credit card", "paypal" -> AccountType.CREDIT
    else -> when {
        type.equals("investment", ignoreCase = true) -> AccountType.INVESTMENT
        type.equals("credit", ignoreCase = true) -> AccountType.CREDIT
        else -> AccountType.OTHER
    }
}

fun plaidAccountBalance(type: AccountType, current: Double, available: Double?): Double =
    if (type == AccountType.CREDIT) current else available ?: current

data class PlaidLinkTokenResponse(val linkToken: String = "")
data class PlaidAccountDto(
    val accountId: String = "",
    val name: String = "Account",
    val mask: String = "",
    val type: String = "",
    val subtype: String = "",
    val current: Double = 0.0,
    val available: Double? = null,
    val limit: Double? = null,
    val connectionLabel: String? = null
)
data class BankConnectionIssue(
    val itemId: String = "",
    val label: String? = null,
    val errorCode: String = "",
    val message: String = "",
    val requiresReconnect: Boolean = false
)
data class PlaidAccountsResponse(
    val accounts: List<PlaidAccountDto> = emptyList(),
    val connectedItems: Int = 0,
    val issues: List<BankConnectionIssue> = emptyList()
)
data class BankRefreshResult(
    val accounts: List<Account>,
    val issues: List<BankConnectionIssue>,
    val connectedItems: Int
)
data class BankConnection(
    val itemId: String = "",
    val label: String? = null,
    val createdAt: String = ""
)
data class PlaidItemsResponse(val items: List<BankConnection> = emptyList())
data class PlaidTransactionDto(
    val transactionId: String = "",
    val accountId: String = "",
    val name: String = "Transaction",
    val amount: Double = 0.0,
    val date: String = "",
    val category: String = "Other",
    val pending: Boolean = false,
    val income: Boolean = false
)
data class PlaidTransactionsResponse(
    val transactions: List<PlaidTransactionDto> = emptyList(),
    val issues: List<BankConnectionIssue> = emptyList()
)

fun bankConnectionDeletePath(itemId: String): String =
    "/api/plaid/items/${URLEncoder.encode(itemId, Charsets.UTF_8.name())}"

fun parseBankConnectionsJson(json: String): List<BankConnection> =
    Gson().fromJson(json, PlaidItemsResponse::class.java).items

fun canRefreshBanks(backendUrl: String): Boolean {
    val value = backendUrl.trim()
    return value.startsWith("https://") || value.startsWith("http://")
}

fun isPlaidTransferCategory(category: String): Boolean {
    val normalized = category.trim()
        .replace('_', ' ')
        .lowercase()
        .replace(Regex("\\s+"), " ")
    return normalized == "transfer" || normalized.startsWith("transfer ")
}

object BankApi {
    private val gson = Gson()
    @Volatile private var sessionToken: String? = null

    fun setSessionToken(token: String?) {
        sessionToken = token?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun base(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.startsWith("https://") || value.startsWith("http://")) {
            "Enter a backend address starting with https:// or http://"
        }
        return value
    }

    private fun authToken(legacyApiKey: String): String {
        return sessionToken ?: legacyApiKey.trim().takeIf { it.isNotBlank() }
        ?: throw IOException("Sign in to BillNest before connecting or refreshing a bank")
    }

    suspend fun createLinkToken(backendUrl: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val json = request(base(backendUrl) + "/api/plaid/link-token", "POST", "{}", authToken(apiKey))
        val token = gson.fromJson(json, PlaidLinkTokenResponse::class.java).linkToken
        if (token.isBlank()) throw IOException("Backend did not return a Plaid link token")
        token
    }

    suspend fun createUpdateLinkToken(backendUrl: String, apiKey: String, itemId: String): String = withContext(Dispatchers.IO) {
        val safeItemId = URLEncoder.encode(itemId, Charsets.UTF_8.name())
        val json = request(
            base(backendUrl) + "/api/plaid/items/$safeItemId/link-token",
            "POST",
            "{}",
            authToken(apiKey)
        )
        val token = gson.fromJson(json, PlaidLinkTokenResponse::class.java).linkToken
        if (token.isBlank()) throw IOException("Backend did not return a bank reconnect token")
        token
    }

    suspend fun exchangePublicToken(backendUrl: String, apiKey: String, publicToken: String, label: String?) = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("publicToken" to publicToken, "label" to (label ?: "Bank")))
        request(base(backendUrl) + "/api/plaid/exchange", "POST", body, authToken(apiKey))
    }

    suspend fun fetchAccounts(backendUrl: String, apiKey: String): BankRefreshResult = withContext(Dispatchers.IO) {
        val json = request(base(backendUrl) + "/api/plaid/accounts", "GET", null, authToken(apiKey))
        val response = gson.fromJson(json, PlaidAccountsResponse::class.java)
        val accounts = response.accounts
            .filter {
                it.type.isBlank() || it.type.equals("depository", ignoreCase = true) ||
                    it.type.equals("investment", ignoreCase = true) || it.type.equals("credit", ignoreCase = true)
            }
            .map { dto ->
                val accountType = plaidAccountType(dto.type, dto.subtype)
                Account(
                    name = dto.name,
                    type = accountType,
                    balance = plaidAccountBalance(accountType, dto.current, dto.available),
                    creditLimit = dto.limit ?: 0.0,
                    source = AccountSource.PLAID,
                    plaidAccountId = dto.accountId,
                    mask = dto.mask,
                    connectionLabel = dto.connectionLabel,
                    role = when (accountType) {
                        AccountType.SAVINGS, AccountType.INVESTMENT -> com.baylee.billnest.model.AccountRole.SAVINGS
                        AccountType.CREDIT -> com.baylee.billnest.model.AccountRole.CREDIT
                        else -> com.baylee.billnest.model.AccountRole.SPENDING
                    },
                    includeInSpendable = accountType != AccountType.SAVINGS && accountType != AccountType.INVESTMENT && accountType != AccountType.CREDIT
                )
            }
        BankRefreshResult(accounts, response.issues, response.connectedItems)
    }

    suspend fun listBankConnections(backendUrl: String, apiKey: String): List<BankConnection> = withContext(Dispatchers.IO) {
        val json = request(base(backendUrl) + "/api/plaid/items", "GET", null, authToken(apiKey))
        parseBankConnectionsJson(json)
    }

    suspend fun fetchTransactions(backendUrl: String, apiKey: String): Pair<List<FinanceTransaction>, List<BankConnectionIssue>> = withContext(Dispatchers.IO) {
        val json = request(base(backendUrl) + "/api/plaid/transactions", "GET", null, authToken(apiKey))
        val response = gson.fromJson(json, PlaidTransactionsResponse::class.java)
        response.transactions.filter { !it.pending && it.transactionId.isNotBlank() && it.date.isNotBlank() }.map { dto ->
            val transfer = isPlaidTransferCategory(dto.category)
            FinanceTransaction(
                id = "plaid:${dto.transactionId}",
                name = dto.name,
                amount = dto.amount,
                dateIso = dto.date,
                category = dto.category.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                accountId = dto.accountId,
                source = TransactionSource.PLAID,
                transfer = transfer,
                income = dto.income && !transfer
            )
        } to response.issues
    }

    suspend fun disconnectBank(backendUrl: String, apiKey: String, itemId: String) = withContext(Dispatchers.IO) {
        request(
            base(backendUrl) + bankConnectionDeletePath(itemId),
            "DELETE",
            null,
            authToken(apiKey)
        )
    }

    private fun request(url: String, method: String, body: String?, bearerToken: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $bearerToken")
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
