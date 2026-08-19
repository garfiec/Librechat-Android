package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.BackendBuildClass
import com.garfiec.librechat.core.common.DetectedBackend
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.network.api.FilesApi
import com.garfiec.librechat.core.network.api.FilesExtApi
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The queued-attachment TTL touch (`POST /api/files/usage`, v0.8.8-rc1).
 *
 * Both directions of this gate cost something real, which is why it is the one that had to stop
 * answering in booleans: calling a server that lacks the route spends upload-limiter quota on a
 * request that cannot succeed, while NOT calling one that has it lets the reaper collect an
 * attachment out from under a queued message, so the send references a file the server deleted.
 */
class FileRepositoryUsageHoldTest {

    private val filesApi = mockk<FilesApi>(relaxUnitFun = true)
    private val filesExtApi = mockk<FilesExtApi>(relaxUnitFun = true)
    private val configRepository = mockk<ConfigRepository>()

    private fun repository(detected: DetectedBackend?): FileRepository {
        every { configRepository.detectedBackend } returns MutableStateFlow(detected)
        return FileRepositoryImpl(filesApi, filesExtApi, configRepository)
    }

    @Test
    fun `a tagged pre-0_8_8 server is never touched`() = runTest {
        val repository = repository(DetectedBackend("0.8.7", BackendBuildClass.OFFICIAL, "2026-06-26"))

        val result = repository.markFilesUsed(listOf("file-1"))

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repository.supportsUsageHold()).isFalse()
        coVerify(exactly = 0) { filesApi.markFilesUsed(any()) }
    }

    @Test
    fun `a dev build reporting the previous release is touched`() = runTest {
        // The data-loss case. This server reports 0.8.7 and may be a 0.8.8-cycle build with the
        // route; withholding the touch is what lets its reaper take the queued attachment.
        val repository = repository(DetectedBackend("0.8.7", BackendBuildClass.DEV, "2026-07-20"))

        repository.markFilesUsed(listOf("file-1"))

        coVerify(exactly = 1) { filesApi.markFilesUsed(listOf("file-1")) }
        assertThat(repository.supportsUsageHold()).isTrue()
    }

    @Test
    fun `an unresolvable server is touched once and a 404 latches the route off`() = runTest {
        coEvery { filesApi.markFilesUsed(any()) } throws ApiException(404, "Not Found")
        val repository = repository(null)

        val first = repository.markFilesUsed(listOf("file-1"))

        // Best-effort by contract: send-time marking is the backstop, so a server that simply
        // lacks the route is not a failure to report upward.
        assertThat(first).isInstanceOf(Result.Success::class.java)
        assertThat(repository.supportsUsageHold()).isFalse()

        repository.markFilesUsed(listOf("file-2"))

        // One request spent on the discovery, not one per enqueue and per 30-minute heartbeat.
        coVerify(exactly = 1) { filesApi.markFilesUsed(any()) }
    }

    @Test
    fun `a 404 from a version-confirmed server does not disable the hold`() = runTest {
        coEvery { filesApi.markFilesUsed(any()) } throws ApiException(404, "Not Found")
        val repository = repository(DetectedBackend("0.8.8-rc1", BackendBuildClass.RC, "2026-08-14"))

        val result = repository.markFilesUsed(listOf("file-1"))

        // This server's own tag says the route is there, so a 404 is a proxy or a deployment
        // oddity — not evidence about the release, and not grounds to stop holding files.
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(repository.supportsUsageHold()).isTrue()
    }

    @Test
    fun `a transient failure leaves the question open`() = runTest {
        coEvery { filesApi.markFilesUsed(any()) } throws ApiException(500, "Server error")
        val repository = repository(null)

        repository.markFilesUsed(listOf("file-1"))

        // A 500 says nothing about whether the route exists. Latching on it would hand a flaky
        // minute the power to disable the hold for the rest of the session.
        assertThat(repository.supportsUsageHold()).isTrue()

        coEvery { filesApi.markFilesUsed(any()) } returns Unit
        repository.markFilesUsed(listOf("file-2"))

        coVerify(exactly = 2) { filesApi.markFilesUsed(any()) }
    }

    @Test
    fun `the next server gets its own probe`() = runTest {
        coEvery { filesApi.markFilesUsed(any()) } throws ApiException(404, "Not Found")
        val repository = repository(null)
        repository.markFilesUsed(listOf("file-1"))
        assertThat(repository.supportsUsageHold()).isFalse()

        repository.clear()

        // The repository is an app-wide singleton: without this reset, a 404 discovered on the
        // account being left would suppress the hold on the one switched to.
        assertThat(repository.supportsUsageHold()).isTrue()
    }
}
