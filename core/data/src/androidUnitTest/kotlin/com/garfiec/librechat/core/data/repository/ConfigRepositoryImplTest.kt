package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ConfigCacheDataStore
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.network.api.ConfigApi
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The probe/reload seams added for multi-account (issue #179): validation for a server being ADDED
 * must not leak into the live server's config state or cache, and a switch must reseed the
 * in-memory state from the incoming server's cache.
 */
class ConfigRepositoryImplTest {

    private val configApi = mockk<ConfigApi>()
    private val configCache = mockk<ConfigCacheDataStore>(relaxed = true)

    private fun repository() = ConfigRepositoryImpl(
        configApi = configApi,
        configCache = configCache,
        dispatcher = UnconfinedTestDispatcher(),
    )

    private val validConfig = StartupConfig(serverDomain = "https://b.example.com")

    @Test
    fun `probeServerUrl validates without publishing in-memory state or writing the cache`() = runTest {
        coEvery { configApi.getStartupConfig() } returns validConfig
        val repo = repository()

        val result = repo.probeServerUrl()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        // The live session's config must be untouched: an add-mode probe runs while another
        // account is active, and the cache key would derive from the LIVE url (poisoning it).
        assertThat(repo.startupConfig.value).isNull()
        coVerify(exactly = 0) { configCache.saveStartupConfig(any()) }
    }

    @Test
    fun `probeServerUrl rejects a non-LibreChat response`() = runTest {
        coEvery { configApi.getStartupConfig() } returns StartupConfig(serverDomain = "")
        val repo = repository()

        val result = repo.probeServerUrl()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("LibreChat")
    }

    @Test
    fun `validateServerUrl still publishes and caches`() = runTest {
        coEvery { configApi.getStartupConfig() } returns validConfig
        val repo = repository()

        val result = repo.validateServerUrl("https://b.example.com")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repo.startupConfig.value).isEqualTo(validConfig)
        coVerify(exactly = 1) { configCache.saveStartupConfig(validConfig) }
    }

    @Test
    fun `reloadForActiveServer replaces in-memory state from the cache without network`() = runTest {
        coEvery { configApi.getStartupConfig() } returns validConfig
        val repo = repository()
        repo.validateServerUrl("https://a.example.com") // in-memory now holds the outgoing server

        val incoming = StartupConfig(serverDomain = "https://b.example.com", version = "9.9.9")
        val incomingEndpoints = mapOf("openAI" to EndpointConfig())
        coEvery { configCache.loadStartupConfig() } returns incoming
        coEvery { configCache.loadEndpointConfigs() } returns incomingEndpoints
        coEvery { configCache.loadAvailableModels() } returns null

        repo.reloadForActiveServer()

        assertThat(repo.startupConfig.value).isEqualTo(incoming)
        assertThat(repo.endpointConfigs.value).isEqualTo(incomingEndpoints)
        assertThat(repo.availableModels.value).isEmpty()
        assertThat(repo.detectedBackendVersion.value).isNull() // re-detected on the next check
    }

    @Test
    fun `reloadForActiveServer empties state when the incoming server has no cache`() = runTest {
        coEvery { configApi.getStartupConfig() } returns validConfig
        val repo = repository()
        repo.validateServerUrl("https://a.example.com")

        coEvery { configCache.loadStartupConfig() } returns null
        coEvery { configCache.loadEndpointConfigs() } returns null
        coEvery { configCache.loadAvailableModels() } returns null

        repo.reloadForActiveServer()

        assertThat(repo.startupConfig.value).isNull()
        assertThat(repo.endpointConfigs.value).isEmpty()
        assertThat(repo.availableModels.value).isEmpty()
    }
}
