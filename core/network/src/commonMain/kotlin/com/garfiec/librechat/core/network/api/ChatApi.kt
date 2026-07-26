package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.model.request.ChatAbortRequest
import com.garfiec.librechat.core.model.request.ChatResumeRequest
import com.garfiec.librechat.core.model.request.SteerCancelRequest
import com.garfiec.librechat.core.model.request.SteerRequest
import com.garfiec.librechat.core.model.response.ChatAbortResponse
import com.garfiec.librechat.core.model.response.ChatResumeResponse
import com.garfiec.librechat.core.model.response.ChatStartResponse
import com.garfiec.librechat.core.model.response.ChatStatusResponse
import com.garfiec.librechat.core.model.response.SteerCancelResponse
import com.garfiec.librechat.core.model.response.SteerResponse
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

    /**
     * POST /api/agents/chat/resume — resolves a run paused for human review (v0.8.8 HITL).
     *
     * Like [abortChat] this only acks: the resumed turn continues over the SSE stream already
     * open for the conversation, so callers must keep collecting it rather than opening a new one.
     *
     * The body replays the paused turn's agent selection because the route re-derives the
     * endpoint option and compares it against the fingerprint pinned at pause time — see
     * [ChatResumeRequest]. Failure modes worth distinguishing upstream: 409 (stale actionId or the
     * pause already resolved/expired), 403 (a different agent/config than the one that paused).
     */
    suspend fun resumeChat(request: ChatResumeRequest): ChatResumeResponse =
        client.post {
            url { path("api/agents/chat/resume") }
            setBody(request)
        }.body()

    /**
     * POST /api/agents/chat/steer — queues instruction text for injection into the live run
     * (v0.8.8 mid-run steering).
     *
     * Accepted is 202 *queued*, not applied: the run injects at its next tool-batch boundary and
     * announces it with `on_steer_applied` over the SSE stream the caller already holds.
     *
     * Every rejection carries a `code` (404 `NO_ACTIVE_RUN`, 409/429/501 for a run that is alive
     * but unreachable). The caller re-homes the text into the follow-up queue regardless of which
     * one it is, so the codes are diagnostic rather than a branch point. All surface as an
     * `ApiException` whose `body` carries the code; nothing here interprets them.
     */
    suspend fun steerChat(request: SteerRequest): SteerResponse =
        client.post {
            url { path("api/agents/chat/steer") }
            setBody(request)
        }.body()

    /**
     * POST /api/agents/chat/steer/cancel — withdraws a steer that has not been injected yet.
     *
     * `{removed:false}` is a success, not a failure: the cancel simply lost its race.
     */
    suspend fun cancelSteer(request: SteerCancelRequest): SteerCancelResponse =
        client.post {
            url { path("api/agents/chat/steer/cancel") }
            setBody(request)
        }.body()

    suspend fun getChatStatus(conversationId: String): ChatStatusResponse =
        client.get {
            url { path("api/agents/chat/status/$conversationId") }
        }.body()
}
