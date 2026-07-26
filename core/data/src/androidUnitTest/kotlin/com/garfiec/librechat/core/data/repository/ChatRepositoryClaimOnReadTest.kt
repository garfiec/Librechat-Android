package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.PendingSteer
import com.garfiec.librechat.core.model.response.ChatAbortResponse
import com.garfiec.librechat.core.model.response.ChatStatusResponse
import com.garfiec.librechat.core.network.api.ChatApi
import com.garfiec.librechat.core.network.sse.SseClient
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * `/chat/status` and the abort ack hand back un-injected steers CLAIM-ON-READ: the server deletes
 * its copy as it answers. The repository therefore claims them itself, inside the call, so no
 * caller can put a staleness guard or an early `return` between the read and the hand-over — the
 * defect these tests exist to prevent.
 */
class ChatRepositoryClaimOnReadTest {

    private val chatApi = mockk<ChatApi>()
    private val sseClient = mockk<SseClient>(relaxed = true)
    private val connectivityObserver = mockk<ConnectivityObserver>(relaxed = true)

    private fun repository() = ChatRepositoryImpl(
        chatApi = chatApi,
        sseClient = sseClient,
        connectivityObserver = connectivityObserver,
        dispatcher = UnconfinedTestDispatcher(),
        json = Json,
    )

    private val parked = listOf(
        PendingSteer(steerId = "s1", text = "also check the logs", createdAt = 1L),
        PendingSteer(steerId = "s2", text = "use metric units", createdAt = 2L),
    )

    @Test
    fun `checkStreamStatus hands parked steers to the claimer and empties the returned copy`() =
        runTest {
            coEvery { chatApi.getChatStatus("conv-1") } returns
                ChatStatusResponse(active = false, unrecoveredSteers = parked)
            val claimed = mutableListOf<List<PendingSteer>>()

            val status = repository().checkStreamStatus("conv-1") { claimed += it }

            assertThat(claimed).containsExactly(parked)
            // Nothing left on the returned value for a caller to forget about.
            assertThat(status.unrecoveredSteers).isEmpty()
            assertThat(status.active).isFalse()
        }

    /**
     * The point of claiming inside the call: a caller that discards the response as stale has
     * still consumed it server-side, so the words must already be re-homed by then.
     */
    @Test
    fun `a caller that discards the response has still received the steers`() = runTest {
        coEvery { chatApi.getChatStatus("conv-1") } returns
            ChatStatusResponse(active = false, unrecoveredSteers = parked)
        val claimed = mutableListOf<List<PendingSteer>>()

        repository().checkStreamStatus("conv-1") { claimed += it }
        // …and the caller drops the returned value on the floor, as a stale-session guard does.

        assertThat(claimed).containsExactly(parked)
    }

    @Test
    fun `abortChat hands its ack steers to the claimer and empties the returned copy`() = runTest {
        coEvery { chatApi.abortChat("conv-1", false) } returns
            ChatAbortResponse(success = true, aborted = "conv-1", pendingSteers = parked)
        val claimed = mutableListOf<List<PendingSteer>>()

        val result = repository().abortChat("conv-1", isTemporary = false) { claimed += it }

        assertThat(claimed).containsExactly(parked)
        assertThat((result as Result.Success).data.pendingSteers).isEmpty()
        assertThat(result.data.success).isTrue()
    }

    /** A failed abort has no body to claim from; the claimer must not be invoked with junk. */
    @Test
    fun `a failed abort does not invoke the claimer`() = runTest {
        coEvery { chatApi.abortChat("conv-1", false) } throws RuntimeException("job not found")
        val claimed = mutableListOf<List<PendingSteer>>()

        val result = repository().abortChat("conv-1", isTemporary = false) { claimed += it }

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(claimed).isEmpty()
    }
}
