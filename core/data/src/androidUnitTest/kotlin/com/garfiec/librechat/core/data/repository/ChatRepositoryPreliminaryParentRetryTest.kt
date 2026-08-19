package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.response.ChatStartResponse
import com.garfiec.librechat.core.network.api.ChatApi
import com.garfiec.librechat.core.network.sse.SseClient
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * A send whose parent response is still being written 409s, and that one 409 is transient — it
 * clears in milliseconds, so the repository retries it rather than telling the user their message
 * failed.
 *
 * These tests exist because the discriminator is subtle and getting it wrong fails SILENTLY: the
 * body carries no `code`, only an English sentence under `error`, so a reader with the MCP
 * controller's `error` fallback hands that sentence back as if it were a code and the retry never
 * runs. Nothing fails to compile, nothing logs, and the send just surfaces as an error.
 *
 * Driven through the repository rather than the code reader alone, because the reader agreeing in
 * isolation is exactly what the broken version also did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryPreliminaryParentRetryTest {

    private val chatApi = mockk<ChatApi>()
    private val sseClient = mockk<SseClient>(relaxed = true)
    private val connectivityObserver = mockk<ConnectivityObserver>(relaxed = true)

    /** The exact 409 body `rejectPreliminaryParentMessageId` writes: an `error`, and no `code`. */
    private val preliminaryParent = ApiException(
        statusCode = 409,
        message = "Conflict",
        body = """{"error":"${
            "Cannot submit a follow-up while the selected parent response is still being saved. " +
                "Please wait and try again."
        }","generationProtocolVersion":1}""",
    )

    private suspend fun sendFirstEvent(dispatcher: CoroutineDispatcher): StreamEvent =
        ChatRepositoryImpl(
            chatApi = chatApi,
            sseClient = sseClient,
            connectivityObserver = connectivityObserver,
            dispatcher = dispatcher,
            json = Json,
        ).startChat(
            text = "and the second thing",
            conversationId = "conv-1",
            endpoint = "agents",
            model = "gpt-4o",
        ).first()

    @Test
    fun `an uncoded 409 is retried and the send goes through`() = runTest {
        var attempts = 0
        coEvery { chatApi.startChat(any(), any<JsonObject>()) } answers {
            attempts++
            if (attempts == 1) throw preliminaryParent else ChatStartResponse(conversationId = "conv-1")
        }

        val created = sendFirstEvent(UnconfinedTestDispatcher(testScheduler))

        assertThat(attempts).isEqualTo(2)
        assertThat((created as StreamEvent.Created).conversationId).isEqualTo("conv-1")
    }

    @Test
    fun `a coded 409 beside it is reported rather than retried`() = runTest {
        var attempts = 0
        // RESOURCE_RECOVERY_REQUIRED needs the user to reattach files: retrying it would loop
        // against a condition only they can clear.
        coEvery { chatApi.startChat(any(), any<JsonObject>()) } answers {
            attempts++
            throw ApiException(
                statusCode = 409,
                message = "Conflict",
                body = """{"code":"resource_recovery_required","error":"Attached resources must be restored."}""",
            )
        }

        assertFailsWith<ApiException> { sendFirstEvent(UnconfinedTestDispatcher(testScheduler)) }

        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `the retry is bounded, so a 409 that never clears still reports`() = runTest {
        var attempts = 0
        coEvery { chatApi.startChat(any(), any<JsonObject>()) } answers {
            attempts++
            throw preliminaryParent
        }

        assertFailsWith<ApiException> { sendFirstEvent(UnconfinedTestDispatcher(testScheduler)) }

        // The initial attempt plus the retry budget — not an unbounded loop against a stuck server.
        assertThat(attempts).isEqualTo(4)
    }
}
