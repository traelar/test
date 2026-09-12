package com.baylee.billnest.data

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HttpApiException(
    val statusCode: Int,
    message: String
) : Exception(message)

class HttpApiClient {
    suspend fun request(
        backendUrl: String,
        path: String,
        method: String = "GET",
        bearerToken: String? = null,
        jsonBody: String? = null
    ): String = withContext(Dispatchers.IO) {
        val base = backendUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "BillNest server must use HTTPS" }
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            bearerToken?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (jsonBody != null) doOutput = true
        }

        try {
            if (jsonBody != null) {
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (status !in 200..299) {
                val message = runCatching {
                    JsonParser.parseString(responseBody).asJsonObject.get("error")?.asString
                }.getOrNull().orEmpty().ifBlank { "BillNest server returned HTTP $status" }
                throw HttpApiException(status, message)
            }
            responseBody
        } finally {
            connection.disconnect()
        }
    }
}
