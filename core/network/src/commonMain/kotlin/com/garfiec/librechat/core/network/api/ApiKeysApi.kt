package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.ApiKey
import com.garfiec.librechat.core.model.ApiKeysResponse
import com.garfiec.librechat.core.model.request.CreateApiKeyRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.path
import kotlinx.serialization.json.Json

class ApiKeysApi constructor(
    private val client: HttpClient,
) {
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun createApiKey(request: CreateApiKeyRequest): ApiKey {
        val response = client.post {
            url { path("api/api-keys") }
            setBody(request)
        }
        val text = response.bodyAsText()
        if (text.trimStart().startsWith("<")) {
            throw IllegalStateException("API keys feature is not available on this server")
        }
        return lenientJson.decodeFromString(text)
    }

    suspend fun listApiKeys(): List<ApiKey> {
        val response = client.get {
            url { path("api/api-keys") }
        }
        val text = response.bodyAsText()
        if (text.trimStart().startsWith("<")) {
            return emptyList()
        }
        val apiKeysResponse = lenientJson.decodeFromString<ApiKeysResponse>(text)
        return apiKeysResponse.keys
    }

    suspend fun deleteApiKey(id: String) {
        val response = client.delete {
            url { path("api/api-keys/$id") }
        }
        val text = response.bodyAsText()
        if (text.trimStart().startsWith("<")) {
            throw IllegalStateException("API keys feature is not available on this server")
        }
    }
}
