package com.garfiec.librechat.feature.auth.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.HeaderWriteFailure
import com.garfiec.librechat.core.data.repository.HeaderWriteResult
import com.garfiec.librechat.core.data.repository.ServerRepository
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.network.client.HeaderRejection
import com.garfiec.librechat.core.ui.components.CustomHeaderRow
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val configRepository = mockk<ConfigRepository>(relaxed = true)
    private val accountSwitcher = mockk<AccountSwitcher>(relaxed = true)

    private val pendingUrl = "https://b.example.com"
    private val config = StartupConfig(serverDomain = pendingUrl)

    private val liveUrl = "https://live.example.com"
    private val liveHeaders = mapOf("CF-Access-Client-Id" to "id-value")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // A relaxed default is not HeaderWriteResult.Saved, and Connect stops on anything else —
        // reading it as "the credential never reached disk". Default to a store that works; the
        // tests about failure say so.
        coEvery { serverRepository.setHeaders(any(), any()) } returns HeaderWriteResult.Saved
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
        serverRepository = serverRepository,
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

    /**
     * `isValidated` is a one-shot navigation signal, and the screen re-fires its navigate effect
     * whenever it re-composes with the flag still set. Since this ViewModel outlives the forward
     * navigation (it sits in the nav back stack), a latched flag would throw the user forward again
     * the moment they pop BACK onto the screen — in add-account mode, an inescapable loop that only
     * force-stopping the app can break.
     */
    @Test
    fun `the validated signal is consumable so back does not bounce forward`() = runTest(testDispatcher) {
        coEvery { configRepository.probeServerUrl() } returns Result.Success(config)
        val viewModel = createViewModel(addAccount = true)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.validateAndConnect()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isValidated).isTrue()

        viewModel.consumeValidated()

        assertThat(viewModel.uiState.value.isValidated).isFalse()
    }

    /** Consuming must not wedge the screen: a second Connect has to navigate again. */
    @Test
    fun `connecting again after a consume re-raises the signal`() = runTest(testDispatcher) {
        coEvery { configRepository.probeServerUrl() } returns Result.Success(config)
        val viewModel = createViewModel(addAccount = true)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.validateAndConnect()
        advanceUntilIdle()
        viewModel.consumeValidated()

        viewModel.validateAndConnect()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isValidated).isTrue()
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

    /**
     * Add mode prefills the ACTIVE server's URL by design, and must not prefill its headers with it.
     * A gateway header is a credential: showing it on a screen the user believes is configuring a new
     * server invites an edit that silently rewrites the live server's credential instead.
     */
    @Test
    fun `add mode prefills the active URL but never the active server's headers`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverRepository.headersForServer(any()) } returns liveHeaders

        val viewModel = createViewModel(addAccount = true)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.url).isEqualTo(liveUrl)
        assertThat(viewModel.uiState.value.customHeaders).isEmpty()
        assertThat(viewModel.uiState.value.showAdvanced).isFalse()
        // Not merely absent from the state — never read, so it cannot leak via a later copy() either.
        coVerify(exactly = 0) { serverRepository.headersForServer(any()) }
    }

    /** The same prefill in normal mode DOES restore the headers — that's what makes logout survivable. */
    @Test
    fun `normal mode prefills the saved headers and opens the advanced section`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverRepository.headersForServer(liveUrl) } returns liveHeaders

        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.customHeaders)
            .containsExactly(CustomHeaderRow("CF-Access-Client-Id", "id-value"))
        assertThat(viewModel.uiState.value.showAdvanced).isTrue()
    }

    /**
     * The probe is the FIRST request to a gateway-protected server, so it is exactly the request that
     * has to already carry the headers. Persisting them in a separate coroutine (or after the probe)
     * would make the first Connect fail and the second succeed — indistinguishable from flakiness.
     */
    @Test
    fun `normal mode persists the headers before probing the server`() = runTest(testDispatcher) {
        coEvery { configRepository.validateServerUrl(pendingUrl) } returns Result.Success(config)
        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.addHeaderRow()
        viewModel.onHeaderNameChanged(0, "CF-Access-Client-Id")
        // Padded on purpose: pasting a token out of a dashboard is the likely entry path, and the
        // trailing whitespace must be normalized away before it is persisted.
        viewModel.onHeaderValueChanged(0, "  id-value  ")
        viewModel.validateAndConnect()
        advanceUntilIdle()

        coVerifyOrder {
            serverRepository.setHeaders(pendingUrl, mapOf("CF-Access-Client-Id" to "id-value"))
            serverDataStore.setServerUrl(pendingUrl)
            configRepository.validateServerUrl(pendingUrl)
        }
    }

    /**
     * Add mode's deadline is earlier still: `beginAdd` mints the PendingRequestIdentity whose headers
     * lambda reads the store, so a save landing after it probes with an empty header set.
     */
    @Test
    fun `add mode persists the headers before beginAdd mints the pending identity`() = runTest(testDispatcher) {
        coEvery { configRepository.probeServerUrl() } returns Result.Success(config)
        val viewModel = createViewModel(addAccount = true)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.addHeaderRow()
        viewModel.onHeaderNameChanged(0, "CF-Access-Client-Id")
        viewModel.onHeaderValueChanged(0, "id-value")
        viewModel.validateAndConnect()
        advanceUntilIdle()

        coVerifyOrder {
            serverRepository.setHeaders(pendingUrl, mapOf("CF-Access-Client-Id" to "id-value"))
            accountSwitcher.beginAdd(pendingUrl)
        }
    }

    /**
     * The single-tap "add another account on the same server" flow the init comment advertises. Add
     * mode prefills the ACTIVE server's URL with an empty editor, so an unconditional save writes an
     * empty map — and an empty map means "delete this server's headers" to the store. That wipes the
     * live session's gateway credential, which is plaintext-only and has no second copy.
     */
    @Test
    fun `add mode with an untouched editor never writes over the active server's headers`() =
        runTest(testDispatcher) {
            coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
            coEvery { configRepository.probeServerUrl() } returns Result.Success(config)

            val viewModel = createViewModel(addAccount = true)
            advanceUntilIdle()

            // Connect on the unchanged prefill — no header row touched.
            viewModel.validateAndConnect()
            advanceUntilIdle()

            coVerify(exactly = 0) { serverRepository.setHeaders(any(), any()) }
        }

    /**
     * The untouched-editor test above only covers a user who never opened Advanced. Touching a row
     * and leaving it blank is the same situation — add mode's editor starts empty *by design*, so it
     * never represented the active server's stored headers and an empty map from it is not a clear.
     * The URL is the active server's, so writing one deletes a live session's credential.
     */
    @Test
    fun `add mode with a touched but blank editor still writes nothing`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { configRepository.probeServerUrl() } returns Result.Success(config)

        val viewModel = createViewModel(addAccount = true)
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.validateAndConnect()
        advanceUntilIdle()

        coVerify(exactly = 0) { serverRepository.setHeaders(any(), any()) }
    }

    /** Typing a credential in add mode is a real edit and must still reach the store. */
    @Test
    fun `add mode still saves headers the user actually typed`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { configRepository.probeServerUrl() } returns Result.Success(config)

        val viewModel = createViewModel(addAccount = true)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.addHeaderRow()
        viewModel.onHeaderNameChanged(0, "CF-Access-Client-Id")
        viewModel.onHeaderValueChanged(0, "id-value")
        viewModel.validateAndConnect()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serverRepository.setHeaders(pendingUrl, mapOf("CF-Access-Client-Id" to "id-value"))
        }
    }

    /**
     * The refusal message tells the user to re-enter the credential, and it renders in the same slot
     * as the load warning that explains why. Leaving it up while they comply means a stale error over
     * valid input, with the explanation suppressed behind it.
     */
    @Test
    fun `editing a row clears a stale store failure`() = runTest(testDispatcher) {
        coEvery { serverRepository.setHeaders(any(), any()) } returns
            HeaderWriteResult.Refused(HeaderWriteFailure.UnverifiedDelete)

        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.addHeaderRow()
        viewModel.removeHeaderRow(0)
        viewModel.validateAndConnect()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.headersSaveFailure).isNotNull()

        viewModel.addHeaderRow()

        assertThat(viewModel.uiState.value.headersSaveFailure).isNull()
    }

    /** Same guard pre-login: an editor that never loaded must not be mistaken for a cleared one. */
    @Test
    fun `normal mode with an untouched editor does not rewrite stored headers`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverRepository.headersForServer(liveUrl) } returns liveHeaders
        coEvery { configRepository.validateServerUrl(any()) } returns Result.Success(config)

        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        viewModel.validateAndConnect()
        advanceUntilIdle()

        coVerify(exactly = 0) { serverRepository.setHeaders(any(), any()) }
    }

    /** Clearing every row IS an edit, so it must still reach the store as an empty map. */
    @Test
    fun `removing the last row persists the clear`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverRepository.headersForServer(liveUrl) } returns liveHeaders
        coEvery { configRepository.validateServerUrl(any()) } returns Result.Success(config)

        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        viewModel.removeHeaderRow(0)
        viewModel.validateAndConnect()
        advanceUntilIdle()

        coVerify(exactly = 1) { serverRepository.setHeaders(liveUrl, emptyMap()) }
    }

    /**
     * Both init awaits can outlast the first frame, and `headersForServer` adds a second suspension on
     * the header store's warm-up. A user typing a rotated token in that window must not have it
     * replaced by the stale persisted set.
     */
    @Test
    fun `a late prefill does not clobber rows the user already typed`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverRepository.headersForServer(liveUrl) } returns liveHeaders

        val viewModel = createViewModel(addAccount = false)
        // Deliberately NOT advancing to idle — init is still suspended.
        viewModel.addHeaderRow()
        viewModel.onHeaderNameChanged(0, "CF-Access-Client-Secret")
        viewModel.onHeaderValueChanged(0, "rotated-secret")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.customHeaders)
            .containsExactly(CustomHeaderRow("CF-Access-Client-Secret", "rotated-secret"))
    }

    /** A reserved name is rejected inline, before any network or storage write. */
    @Test
    fun `a reserved header name blocks the connect entirely`() = runTest(testDispatcher) {
        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.addHeaderRow()
        viewModel.onHeaderNameChanged(0, "Authorization")
        viewModel.onHeaderValueChanged(0, "Basic abc")
        viewModel.validateAndConnect()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.headerError)
            .isEqualTo(HeaderFieldError(index = 0, reason = HeaderRejection.ReservedName))
        assertThat(viewModel.uiState.value.showAdvanced).isTrue()
        coVerify(exactly = 0) { serverRepository.setHeaders(any(), any()) }
        coVerify(exactly = 0) { configRepository.validateServerUrl(any()) }
    }

    /**
     * A probe without the credential comes back as the gateway's own login page, which surfaces as a
     * generic connection error — sending the user to re-check a URL that was never the problem while
     * the token they typed was never stored.
     */
    @Test
    fun `a header write the store refuses stops the connect instead of probing`() = runTest(testDispatcher) {
        coEvery { serverRepository.setHeaders(any(), any()) } returns
            HeaderWriteResult.Refused(HeaderWriteFailure.StorageUnavailable)
        coEvery { configRepository.validateServerUrl(any()) } returns Result.Success(config)

        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        viewModel.onUrlChanged(pendingUrl)
        viewModel.addHeaderRow()
        viewModel.onHeaderNameChanged(0, "CF-Access-Client-Id")
        viewModel.onHeaderValueChanged(0, "id-value")
        viewModel.validateAndConnect()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.headersSaveFailure)
            .isEqualTo(HeaderWriteFailure.StorageUnavailable)
        assertThat(viewModel.uiState.value.showAdvanced).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        coVerify(exactly = 0) { configRepository.validateServerUrl(any()) }
    }

    /**
     * The pre-login twin of the settings editor's refusal. This screen is reached *because* the
     * server is unreachable, so an editor that could not load its credential is the normal case here,
     * not an edge one — and clearing a row in it must not be able to delete the only copy.
     */
    @Test
    fun `a clear the store refuses stops the connect and names the reason`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverRepository.headersForServer(liveUrl) } returns null
        coEvery { serverRepository.setHeaders(any(), any()) } returns
            HeaderWriteResult.Refused(HeaderWriteFailure.UnverifiedDelete)
        coEvery { configRepository.validateServerUrl(any()) } returns Result.Success(config)

        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.removeHeaderRow(0)
        viewModel.validateAndConnect()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.headersSaveFailure)
            .isEqualTo(HeaderWriteFailure.UnverifiedDelete)
        assertThat(viewModel.uiState.value.showAdvanced).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        // Probing anyway would blame the URL for a credential that was never written.
        coVerify(exactly = 0) { configRepository.validateServerUrl(any()) }
    }

    @Test
    fun `an unreadable store is flagged rather than shown as an empty editor`() = runTest(testDispatcher) {
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverRepository.headersForServer(liveUrl) } returns null

        val viewModel = createViewModel(addAccount = false)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.headersLoadFailed).isTrue()
        // Expanded, so the warning isn't hidden behind a collapsed section.
        assertThat(viewModel.uiState.value.showAdvanced).isTrue()
    }
}
