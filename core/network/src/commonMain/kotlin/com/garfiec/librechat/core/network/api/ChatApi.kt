package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.model.request.ChatAbortRequest
import com.garfiec.librechat.core.model.response.ActiveJobsResponse
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

    suspend fun abortChat(streamId: String): ChatAbortResponse =
        client.post {
            url { path("api/agents/chat/abort") }
            setBody(ChatAbortRequest(abortKey = streamId, endpoint = "agents"))
        }.body()

    suspend fun getActiveJobs(): ActiveJobsResponse =
        client.get {
            url { path("api/agents/chat/active") }
        }.body()

    suspend fun getChatStatus(conversationId: String): ChatStatusResponse =
        client.get {
            url { path("api/agents/chat/status/$conversationId") }
        }.body()
}
