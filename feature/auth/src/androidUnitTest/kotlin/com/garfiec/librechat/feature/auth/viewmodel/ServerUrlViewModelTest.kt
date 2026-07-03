package com.garfiec.librechat.feature.auth.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.model.config.StartupConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The add-account mode contract: validating a server being ADDED must never touch the live
 * account's state — no global URL write (the bearer-to-wrong-host leak) and no config
 * publish/cache (that's the srv:-key poisoning) — while the normal mode keeps its original
 * set-then-validate behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerUrlViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val serverDataStore = mockk<ServerDataStore>(relaxed = true)
    private val configRepository = mockk<ConfigRepository>(relaxed = true)
    private val accountSwitcher = mockk<AccountSwitcher>(relaxed = true)

    private val pendingUrl = "https://b.example.com"
    private val config = StartupConfig(serverDomain = pendingUrl)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Pass block-running calls through so the probe actually executes.
        coEvery { accountSwitcher.withPendingIdentity(any<suspend () -> Result<StartupConfig>>()) } coAnswers {
            firstArg<suspend () -> Result<StartupConfig>>().invoke()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(addAccount: Boolean) = ServerUrlViewModel(
        serverDataStore = serverDataStore,
        configRepository = configRepository,
        accountSwitcher = accountSwitcher,
        addAccount = addAccount,
    )

    @Test
    fun `add mode probes under the pending identity and never touches the global URL`() = runTest(testDispatcher) {
        coEvery { configRepository.probeServerUrl() } returns Result.Success(config)
        val viewModel = createViewModel(addAccount = true)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.validateAndConnect()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isValidated).isTrue()
        coVerify(exactly = 1) { accountSwitcher.beginAdd(pendingUrl) }
        coVerify(exactly = 1) { accountSwitcher.attachPendingConfig(config) }
        coVerify(exactly = 0) { serverDataStore.setServerUrl(any()) }
        coVerify(exactly = 0) { configRepository.validateServerUrl(any()) }
    }

    @Test
    fun `add mode validation failure cancels the pending session and surfaces the error`() =
        runTest(testDispatcher) {
            coEvery { configRepository.probeServerUrl() } returns Result.Error(message = "not librechat")
            val viewModel = createViewModel(addAccount = true)
            advanceUntilIdle()

            viewModel.onUrlChanged(pendingUrl)
            viewModel.validateAndConnect()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isValidated).isFalse()
            assertThat(viewModel.uiState.value.error).isEqualTo("not librechat")
            coVerify(exactly = 1) { accountSwitcher.cancelAdd() }
            coVerify(exactly = 0) { serverDataStore.setServerUrl(any()) }
        }

    @Test
    fun `add mode surfaces a beginAdd failure as an error instead of crashing`() = runTest(testDispatcher) {
        coEvery { accountSwitcher.beginAdd(any()) } throws IllegalStateException("no active account")
        val viewModel = createViewModel(addAccount = true)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.validateAndConnect()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isValidated).isFalse()
        assertThat(viewModel.uiState.value.error).isNotNull()
    }

    @Test
    fun `normal mode sets the URL then validates, resetting it on failure`() = runTest(testDispatcher) {
        coEvery { configRepository.validateServerUrl(pendingUrl) } returns Result.Error(message = "nope")
        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.validateAndConnect()
        advanceUntilIdle()

        coVerify(exactly = 1) { serverDataStore.setServerUrl(pendingUrl) }
        coVerify(exactly = 1) { serverDataStore.setServerUrl("") }
        coVerify(exactly = 0) { accountSwitcher.beginAdd(any()) }
    }
}
