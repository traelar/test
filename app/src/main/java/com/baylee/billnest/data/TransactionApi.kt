package com.baylee.billnest.data

import com.baylee.billnest.model.FinanceTransaction
import com.google.gson.Gson

private data class TransactionsResponse(
    val transactions: List<FinanceTransaction> = emptyList()
)

data class TransactionSyncResponse(
    val added: Int = 0,
    val modified: Int = 0,
    val removed: Int = 0,
    val issues: List<BankConnectionIssue> = emptyList()
)

class TransactionApi(
    private val http: HttpApiClient = HttpApiClient(),
    private val gson: Gson = Gson()
) {
    suspend fun sync(backendUrl: String, sessionToken: String): TransactionSyncResponse {
        val json = http.request(
            backendUrl = backendUrl,
            path = "/api/transactions/sync",
            method = "POST",
            bearerToken = sessionToken,
            jsonBody = "{}"
        )
        return gson.fromJson(json, TransactionSyncResponse::class.java)
    }

    suspend fun list(backendUrl: String, sessionToken: String): List<FinanceTransaction> {
        val json = http.request(
            backendUrl = backendUrl,
            path = "/api/transactions",
            bearerToken = sessionToken
        )
        return gson.fromJson(json, TransactionsResponse::class.java).transactions
    }

    suspend fun syncAndList(backendUrl: String, sessionToken: String): Pair<TransactionSyncResponse, List<FinanceTransaction>> {
        val sync = sync(backendUrl, sessionToken)
        return sync to list(backendUrl, sessionToken)
    }
}
