package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.request.AddPromptToGroupRequest
import com.garfiec.librechat.core.model.request.CreatePromptData
import com.garfiec.librechat.core.network.di.librechatJson
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Pins the wire shapes of the two prompt routes whose request and response bodies do not match the
 * bare-object convention the rest of `PromptsApi` follows.
 *
 * The bodies below are live captures from a v0.8.7 server. This has to live at `:core:network`: no
 * test above it can see a JSON key, so a mismatch here compiles, type-checks, and surfaces only as
 * a `Result.Error` the UI handles correctly.
 */
class PromptsApiWireShapeTest {

    private lateinit var lastRequest: HttpRequestData

    private fun api(responseBody: String, status: HttpStatusCode = HttpStatusCode.OK): PromptsApi {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(
                content = responseBody,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(librechatJson) }
            // Mirrors production's defaultRequest, which is what makes the body serialize as JSON.
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        return PromptsApi(client)
    }

    private fun sentBody(): String = (lastRequest.body as TextContent).text

    private val addRequest = AddPromptToGroupRequest(
        prompt = CreatePromptData(prompt = "EDITED BODY", type = "text"),
    )

    /** A live 200 from `POST /api/prompts/groups/{id}/prompts`. */
    private val addResponse = """
        {"prompt":{"groupId":"6a7d506cfee2d387a18fc986","author":"6a797e6b09dd4560d08b49d6",
        "prompt":"EDITED BODY","type":"text","_id":"6a7d506df4113f7e6c7140bc",
        "createdAt":"2026-08-13T05:04:45.855Z","updatedAt":"2026-08-13T05:04:45.855Z","__v":0}}
    """.trimIndent().replace("\n", "")

    @Test
    fun `adding a version nests the body under prompt`() = runTest {
        api(addResponse).addPromptToGroup("g-1", addRequest)

        // The route reads req.body.prompt.prompt. Sent flat, req.body.prompt is the text itself and
        // the route answers 400 — which makes every body edit in the app impossible, on any server.
        val body = Json.parseToJsonElement(sentBody()).jsonObject
        val nested = body["prompt"]!!.jsonObject
        assertThat(nested["prompt"]!!.jsonPrimitive.content).isEqualTo("EDITED BODY")
        assertThat(nested["type"]!!.jsonPrimitive.content).isEqualTo("text")
    }

    @Test
    fun `adding a version unwraps the prompt the server answers with`() = runTest {
        val added = api(addResponse).addPromptToGroup("g-1", addRequest)

        // savePrompt returns { prompt: … }, not a bare Prompt. The id is the point: it is the only
        // place the new version's id appears, and promoting it to production needs it.
        assertThat(added.id).isEqualTo("6a7d506df4113f7e6c7140bc")
        assertThat(added.prompt).isEqualTo("EDITED BODY")
        assertThat(added.groupId).isEqualTo("6a7d506cfee2d387a18fc986")
    }

    @Test(expected = Exception::class)
    fun `a failed add reports failure even though the route answers 200`() = runTest {
        // savePrompt answers { message: "Error saving prompt" } — still HTTP 200 — when the write
        // fails. Nothing above this can tell that from a success, so the decode has to: a nullable
        // prompt hands the caller a success carrying nothing, and it promotes on the strength of it.
        api("""{"message":"Error saving prompt"}""").addPromptToGroup("g-1", addRequest)
    }

    @Test
    fun `promoting a version sends no body and decodes none`() = runTest {
        // The route promotes req.params.promptId and never reads req.body, so a body naming a
        // different prompt is silently ignored while reading as though it chose which version
        // went live.
        api("""{"message":"Prompt production made successfully"}""").updatePromptProductionTag("p-2")

        assertThat(lastRequest.body).isNotInstanceOf(TextContent::class.java)
        assertThat(lastRequest.url.encodedPath).isEqualTo("/api/prompts/p-2/tags/production")
    }

    @Test
    fun `promoting a version tolerates the message body the route answers with`() = runTest {
        // The response is { message: … }, not the prompt. Decoding it as a Prompt throws
        // MissingFieldException, turning an accepted promotion into a Result.Error — which skips
        // the repository's revision bump, leaving the `/` picker on the superseded body.
        api("""{"message":"Prompt production made successfully"}""").updatePromptProductionTag("p-2")
    }
}
