package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.request.CreateMemoryRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryPreferencesRequest
import com.garfiec.librechat.core.model.request.UpdateMemoryRequest
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Pins the envelope shapes of the memories routes. Every one of them wraps its payload, so a
 * regression that decodes a response as the bare entity fails here instead of at runtime with an
 * empty memories screen.
 */
class MemoriesApiTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun jsonClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        defaultRequest {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
        }
    }

    @Test
    fun `getMemories unwraps the list from the usage envelope`() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "memories": [
                        {"key":"likes_tea","value":"yes","updated_at":"2026-07-20T10:00:00.000Z"},
                        {"key":"tone","value":"terse","agentId":"agent_1","agentName":"Helper"}
                      ],
                      "totalTokens": 12,
                      "tokenLimit": 2000,
                      "charLimit": 10000,
                      "usagePercentage": 1
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val memories = MemoriesApi(jsonClient(engine)).getMemories()

        assertThat(memories).hasSize(2)
        assertThat(memories[0].updatedAt).isEqualTo("2026-07-20T10:00:00.000Z")
        assertThat(memories[0].agentId).isNull()
        assertThat(memories[1].agentId).isEqualTo("agent_1")
        assertThat(memories[1].agentName).isEqualTo("Helper")
    }

    @Test
    fun `createMemory unwraps the created entry`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"created":true,"memory":{"key":"likes_tea","value":"yes"}}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val memory = MemoriesApi(jsonClient(engine))
            .createMemory(CreateMemoryRequest(key = "likes_tea", value = "yes"))

        assertThat(memory.key).isEqualTo("likes_tea")
    }

    @Test
    fun `updateMemory unwraps the updated entry and targets the agent partition`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = """{"updated":true,"memory":{"key":"tone","value":"warm","agentId":"agent_1"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val memory = MemoriesApi(jsonClient(engine))
            .updateMemory("tone", UpdateMemoryRequest(value = "warm"), agentId = "agent_1")

        assertThat(capturedUrl).contains("agentId=agent_1")
        assertThat(memory.value).isEqualTo("warm")
        assertThat(memory.agentId).isEqualTo("agent_1")
    }

    @Test
    fun `updatePreferences sends and reads the memories key`() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                content = """{"updated":true,"preferences":{"memories":false}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val preferences = MemoriesApi(jsonClient(engine))
            .updatePreferences(UpdateMemoryPreferencesRequest(enabled = false))

        assertThat(capturedBody).isEqualTo("""{"memories":false}""")
        assertThat(preferences.enabled).isFalse()
    }
}
