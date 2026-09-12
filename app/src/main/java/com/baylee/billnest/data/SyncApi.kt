package com.baylee.billnest.data

import com.baylee.billnest.model.SyncApplied
import com.baylee.billnest.model.SyncChange
import com.baylee.billnest.model.SyncConflict
import com.baylee.billnest.model.SyncMutation
import com.baylee.billnest.model.SyncResponse
import com.baylee.billnest.model.SyncServerRecord
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class SyncApi(
    private val client: HttpApiClient = HttpApiClient()
) {
    suspend fun sync(
        backendUrl: String,
        sessionToken: String,
        sinceEventId: Long,
        mutations: List<SyncMutation>
    ): SyncResponse {
        val body = JsonObject().apply {
            addProperty("sinceEventId", sinceEventId)
            add("mutations", JsonArray().apply {
                mutations.forEach { mutation ->
                    add(JsonObject().apply {
                        addProperty("mutationId", mutation.mutationId)
                        addProperty("kind", mutation.kind)
                        addProperty("recordId", mutation.recordId)
                        addProperty("baseVersion", mutation.baseVersion)
                        addProperty("deleted", mutation.deleted)
                        add("payload", JsonParser.parseString(mutation.payloadJson))
                    })
                }
            })
        }

        val raw = client.request(
            backendUrl = backendUrl,
            path = "/api/sync",
            method = "POST",
            bearerToken = sessionToken,
            jsonBody = body.toString()
        )
        return parseResponse(raw)
    }

    private fun parseResponse(raw: String): SyncResponse {
        val root = JsonParser.parseString(raw).asJsonObject
        val applied = root.getAsJsonArray("applied")?.map { element ->
            val item = element.asJsonObject
            SyncApplied(
                mutationId = item.get("mutationId").asString,
                kind = item.get("kind").asString,
                recordId = item.get("recordId").asString,
                version = item.get("version").asInt
            )
        }.orEmpty()

        val conflicts = root.getAsJsonArray("conflicts")?.map { element ->
            val item = element.asJsonObject
            val server = item.get("serverRecord")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { record ->
                SyncServerRecord(
                    kind = record.get("kind").asString,
                    recordId = record.get("recordId").asString,
                    version = record.get("version").asInt,
                    deleted = record.get("deleted").asBoolean,
                    payloadJson = record.get("payload")?.toString() ?: "{}",
                    updatedAt = record.get("updatedAt")?.asString.orEmpty()
                )
            }
            SyncConflict(
                mutationId = item.get("mutationId").asString,
                kind = item.get("kind").asString,
                recordId = item.get("recordId").asString,
                baseVersion = item.get("baseVersion").asInt,
                serverRecord = server
            )
        }.orEmpty()

        val changes = root.getAsJsonArray("changes")?.map { element ->
            val item = element.asJsonObject
            SyncChange(
                eventId = item.get("eventId").asLong,
                kind = item.get("kind").asString,
                recordId = item.get("recordId").asString,
                version = item.get("version").asInt,
                deleted = item.get("deleted").asBoolean,
                payloadJson = item.get("payload")?.toString() ?: "{}",
                updatedAt = item.get("updatedAt")?.asString.orEmpty()
            )
        }.orEmpty()

        return SyncResponse(
            cursor = root.get("cursor")?.asLong ?: 0L,
            applied = applied,
            conflicts = conflicts,
            changes = changes
        )
    }
}
