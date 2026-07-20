package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.model.request.ChatAbortRequest
import com.garfiec.librechat.core.model.response.ChatAbortResponse
import com.garfiec.librechat.core.model.response.ChatStartResponse
import com.garfiec.librechat.core.model.response.ChatStatusResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.path
import kotlinx.serialization.json.JsonObject

class ChatApi constructor(
    private val client: HttpClient,
) {
    /**
     * POST /api/agents/chat/{endpoint} — phase-1 of the two-phase SSE protocol.
     *
     * On v0.8.5-rc1 with `summarization: enabled: true`, the backend has been observed
     * returning 200 OK with no `Content-Type` header on this endpoint, which makes Ktor's
     * content-negotiation throw `NoTransformationFoundException` — surfacing a Ktor-internal
     * stack trace to the user. We catch and translate to [ApiException] so the chat surface
     * shows an actionable message instead of "Expected response body of the type 'class
     * com.garfiec.librechat.core.model.response.ChatStartResponse...'".
     */
    @Throws(Exception::class)
    suspend fun startChat(endpoint: String, body: JsonObject): ChatStartResponse {
        val response = client.post {
            url { path("api/agents/chat/$endpoint") }
            setBody(body)
        }
        val contentType = response.headers[HttpHeaders.ContentType]
        if (contentType == null || ContentType.parse(contentType).match(ContentType.Application.Json).not()) {
            throw ApiException(
                statusCode = response.status.value,
                message = "Server returned an unexpected response when starting the chat. " +
                    "This usually indicates a backend version incompatibility — please check " +
                    "that the server is running a supported LibreChat release.",
            )
        }
        return try {
            response.body()
        } catch (e: NoTransformationFoundException) {
            throw ApiException(
                statusCode = response.status.value,
                message = "Server returned an unexpected response shape when starting the chat. " +
                    "This usually indicates a backend version incompatibility — please check " +
                    "that the server is running a supported LibreChat release.",
                cause = e,
            )
        }
    }

    /**
     * POST /api/agents/chat/abort — asks the server to stop the in-flight turn.
     *
     * The response is only an ack (`{ success, aborted }`); it does NOT carry the turn. The
     * server ends the run by emitting a `final` frame flagged `aborted` over the SSE stream the
     * client is already collecting, so callers must keep that stream open and let the turn
     * finalize through the normal event flow.
     *
     * A null [streamId] is sent as an empty abort key, which resolves no job server-side and
     * falls through to the route's user-scoped fallback: it aborts the caller's most recent
     * active job. That is what makes Stop work before the `created` event has assigned a
     * conversation id.
     */
    suspend fun abortChat(streamId: String?, isTemporary: Boolean): ChatAbortResponse =
        client.post {
            url { path("api/agents/chat/abort") }
            setBody(
                ChatAbortRequest(
                    abortKey = streamId.orEmpty(),
                    endpoint = "agents",
                    isTemporary = isTemporary,
                ),
            )
        }.body()

    suspend fun getChatStatus(conversationId: String): ChatStatusResponse =
        client.get {
            url { path("api/agents/chat/status/$conversationId") }
        }.body()
}
