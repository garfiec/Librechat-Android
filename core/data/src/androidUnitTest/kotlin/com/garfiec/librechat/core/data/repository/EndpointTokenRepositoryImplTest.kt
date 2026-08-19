package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.BackendBuildClass
import com.garfiec.librechat.core.common.DetectedBackend
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.request.ContextProjectionRequest
import com.garfiec.librechat.core.network.api.EndpointTokenApi
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The context-projection suppression. The endpoint was removed on the 0.8.8 line, and since the
 * v0.8.8-rc1 tag shipped the gate is a plain version compare: servers reporting >= 0.8.8-rc1 skip
 * the POST; anything older (including dev builds that still report 0.8.7) issues it and discards
 * the 404 — the harmless direction.
 */
class EndpointTokenRepositoryImplTest {

    private val api = mockk<EndpointTokenApi>()
    private val configRepository = mockk<ConfigRepository>()

    private val request = ContextProjectionRequest(
        conversationId = "convo_1",
        messageId = "msg_1",
        endpoint = "openAI",
    )

    private fun repository(detected: DetectedBackend?): EndpointTokenRepository {
        every { configRepository.detectedBackend } returns MutableStateFlow(detected)
        return EndpointTokenRepositoryImpl(api, configRepository)
    }

    private fun devBackend(commitDate: String) =
        DetectedBackend("0.8.7", BackendBuildClass.DEV, commitDate)

    @Test
    fun `a dev build still reporting the previous release gets the projection`() = runTest {
        coEvery { api.getContextProjection(any()) } returns null

        val result = repository(devBackend("2026-08-01")).getContextProjection(request)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 1) { api.getContextProjection(request) }
    }

    @Test
    fun `a tagged 0-8-8 server skips the call regardless of date`() = runTest {
        val detected = DetectedBackend("0.8.8-rc1", BackendBuildClass.RC, "2026-08-01")

        val result = repository(detected).getContextProjection(request)

        assertThat((result as Result.Success).data).isNull()
        coVerify(exactly = 0) { api.getContextProjection(any()) }
    }

    @Test
    fun `an unresolved server still issues the call`() = runTest {
        // supportsFeature fails closed on null, so the suppression does not apply. The gauge is
        // separately off in that case (ChatViewModel requires a resolved version), so this path
        // is only reachable if that gate is ever relaxed.
        coEvery { api.getContextProjection(any()) } returns null

        val result = repository(null).getContextProjection(request)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        coVerify(exactly = 1) { api.getContextProjection(request) }
    }
}
