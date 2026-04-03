package com.librechat.android.core.network.api

import com.librechat.android.core.model.EndpointConfig
import com.librechat.android.core.model.StartupConfig
import com.librechat.android.core.model.response.Category
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class ConfigApi constructor(
    private val client: HttpClient,
) {
    suspend fun getStartupConfig(): StartupConfig =
        client.get {
            url { path("api/config") }
        }.body()

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Fetches configured endpoint configs. The server may return JSON with
     * Content-Type: text/html, so we read the raw body and deserialize manually.
     */
    suspend fun getEndpoints(): Map<String, EndpointConfig> {
        val response = client.get {
            url { path("api/endpoints") }
        }
        val text = response.bodyAsText()
        return lenientJson.decodeFromString(text)
    }

    /**
     * Fetches models per endpoint. The server returns each endpoint as an array of either
     * strings (legacy) or objects with an `id` field (current LibreChat / provider APIs).
     */
    suspend fun getModels(): Map<String, List<String>> {
        val response = client.get {
            url { path("api/models") }
        }
        val text = response.bodyAsText()
        val element = lenientJson.parseToJsonElement(text)
        return parseModelsPayload(element)
    }

    private fun parseModelsPayload(element: JsonElement): Map<String, List<String>> {
        val rootObject = element as? JsonObject ?: return emptyMap()
        val data = rootObject["data"]
        val mapObject = when {
            data is JsonObject -> data
            else -> rootObject
        }
        return mapObject.entries.associate { (key, value) ->
            key to normalizeModelIds(value)
        }
    }

    private fun normalizeModelIds(value: JsonElement): List<String> {
        val arr = value as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) item.content.takeIf { it.isNotBlank() } else null
                is JsonObject -> {
                    item["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: item["model"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }
    }

    suspend fun getCategories(): List<Category> =
        client.get {
            url { path("api/categories") }
        }.body()
}
