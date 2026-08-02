package com.garfiec.librechat.feature.settings.viewmodel

import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.HeaderWriteFailure
import com.garfiec.librechat.core.data.repository.HeaderWriteResult
import com.garfiec.librechat.core.data.repository.ServerRepository
import com.garfiec.librechat.core.network.client.HeaderRejection
import com.garfiec.librechat.core.ui.components.CustomHeaderRow
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Post-login gateway-header editing (issue #287). The contract that matters here is that this edits
 * the ACTIVE server and nothing else: it is reached while signed in, so a write keyed on the wrong
 * URL would file the credential under a server the app never contacts while leaving the live one
 * broken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerHeadersViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val serverDataStore = mockk<ServerDataStore>(relaxed = true)
    private val serverRepository = mockk<ServerRepository>(relaxed = true)

    private val liveUrl = "https://gateway.example.com"
    private val otherUrl = "https://other.example.com"

    /** Drives the observed server URL, so a test can switch servers under a composed screen. */
    private val currentUrl = MutableStateFlow(liveUrl)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { serverDataStore.currentUrlFlow } returns currentUrl
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverRepository.setHeaders(any(), any()) } returns HeaderWriteResult.Saved
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ServerHeadersViewModel(
        serverDataStore = serverDataStore,
        serverRepository = serverRepository,
    )

    @Test
    fun `loads the active server's saved headers`() = runTest(testDispatcher) {
        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "id-value")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.serverUrl).isEqualTo(liveUrl)
        assertThat(viewModel.uiState.value.headers)
            .containsExactly(CustomHeaderRow("CF-Access-Client-Id", "id-value"))
        // Nothing edited yet, so there is nothing to save.
        assertThat(viewModel.uiState.value.isDirty).isFalse()
    }

    @Test
    fun `saves against the active server URL, normalizing pasted values`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Secret")
        viewModel.onValueChanged(0, "  secret-value\t")
        assertThat(viewModel.uiState.value.isDirty).isTrue()

        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serverRepository.setHeaders(liveUrl, mapOf("CF-Access-Client-Secret" to "secret-value"))
        }
        assertThat(viewModel.uiState.value.saved).isTrue()
        assertThat(viewModel.uiState.value.isDirty).isFalse()
    }

    @Test
    fun `a reserved name blocks the save and points at the offending row`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        viewModel.onValueChanged(0, "id-value")
        viewModel.addHeaderRow()
        viewModel.onNameChanged(1, "User-Agent")
        viewModel.onValueChanged(1, "curl/8")

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error)
            .isEqualTo(ServerHeaderError(index = 1, reason = HeaderRejection.ReservedName))
        coVerify(exactly = 0) { serverRepository.setHeaders(any(), any()) }
    }

    @Test
    fun `a non-ASCII value blocks the save`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        // A smart quote, the classic artifact of pasting a token out of a dashboard.
        viewModel.onValueChanged(0, "id’value")

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error)
            .isEqualTo(ServerHeaderError(index = 0, reason = HeaderRejection.InvalidValue))
        coVerify(exactly = 0) { serverRepository.setHeaders(any(), any()) }
    }

    @Test
    fun `clearing every row saves an empty map rather than skipping the write`() = runTest(testDispatcher) {
        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "id-value")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.removeHeaderRow(0)
        viewModel.save()
        advanceUntilIdle()

        // Removing the last header has to reach the store — otherwise a user who deletes a stale
        // credential keeps sending it.
        coVerify(exactly = 1) { serverRepository.setHeaders(liveUrl, emptyMap()) }
    }

    @Test
    fun `editing a row clears a rejection pinned to a stale index`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "Host")
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNotNull()

        viewModel.onNameChanged(0, "CF-Access-Client-Id")

        assertThat(viewModel.uiState.value.error).isNull()
    }

    /** A blank editor row is the normal state after tapping Add; it must not read as an error. */
    @Test
    fun `a fully blank row is ignored rather than rejected`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
        coVerify(exactly = 1) { serverRepository.setHeaders(liveUrl, emptyMap()) }
    }

    /**
     * This screen can stay composed across an account switch — a two-pane tablet layout never
     * navigates away from it. A URL captured once would file the edited credential under the previous
     * server's id: breaking the server being edited AND overwriting the one that was working.
     */
    @Test
    fun `a server switch under a composed screen re-targets the save`() = runTest(testDispatcher) {
        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "server-a-value")
        coEvery { serverRepository.headersForServer(otherUrl) } returns emptyMap()

        val viewModel = createViewModel()
        advanceUntilIdle()

        currentUrl.value = otherUrl
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.serverUrl).isEqualTo(otherUrl)
        // Server A's rows must not carry over — they belong to the server that just went away.
        assertThat(viewModel.uiState.value.headers).isEmpty()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        viewModel.onValueChanged(0, "server-b-value")
        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serverRepository.setHeaders(otherUrl, mapOf("CF-Access-Client-Id" to "server-b-value"))
        }
        coVerify(exactly = 0) { serverRepository.setHeaders(liveUrl, any()) }
    }

    /**
     * The Save button is enabled as soon as a row is edited, which can be before ServerDataStore has
     * warmed up. Returning silently there would drop the credential with no write, no error and no
     * way to retry.
     */
    @Test
    fun `a save before the URL resolves still lands, keeping the typed rows`() = runTest(testDispatcher) {
        currentUrl.value = ""
        coEvery { serverRepository.headersForServer(any()) } returns emptyMap()

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.serverUrl).isEmpty()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        viewModel.onValueChanged(0, "id-value")
        viewModel.save()
        advanceUntilIdle()

        // awaitBaseUrl resolves it rather than the save being dropped on the floor.
        coVerify(exactly = 1) {
            serverRepository.setHeaders(liveUrl, mapOf("CF-Access-Client-Id" to "id-value"))
        }
        assertThat(viewModel.uiState.value.saved).isTrue()
    }

    /** The first URL resolution must not wipe rows typed while it was still pending. */
    @Test
    fun `the first URL resolution keeps rows typed before it landed`() = runTest(testDispatcher) {
        currentUrl.value = ""
        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "stale-value")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Secret")
        viewModel.onValueChanged(0, "rotated-secret")

        currentUrl.value = liveUrl
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.serverUrl).isEqualTo(liveUrl)
        assertThat(viewModel.uiState.value.headers)
            .containsExactly(CustomHeaderRow("CF-Access-Client-Secret", "rotated-secret"))
    }

    @Test
    fun `an unreadable store is flagged, not rendered as an empty editor`() = runTest(testDispatcher) {
        // Blank rows here also arm the trap: Save from that state writes the empty set and really
        // does delete the stored credential.
        coEvery { serverRepository.headersForServer(liveUrl) } returns null

        val viewModel = createViewModel()
        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.loadFailed).isTrue()
        assertThat(viewModel.uiState.value.headers).hasSize(1)
    }

    @Test
    fun `discarding against an unreadable store drops the typed rows without writing`() =
        runTest(testDispatcher) {
            // There is nothing to revert *to*, but the dialog closes either way, so keeping the rows
            // would put the abandoned edit back on screen at the next open. Nothing persisted is at
            // risk: the save below is what refuses to write an empty set while loadFailed.
            coEvery { serverRepository.headersForServer(liveUrl) } returns null

            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.addHeaderRow()
            viewModel.onValueChanged(0, "typed")
            viewModel.discardEdits()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.headers).isEmpty()
            assertThat(viewModel.uiState.value.isDirty).isFalse()
            assertThat(viewModel.uiState.value.loadFailed).isTrue()
            coVerify(exactly = 0) { serverRepository.setHeaders(any(), any()) }
        }

    @Test
    fun `an empty save the store refuses is surfaced rather than reported as saved`() =
        runTest(testDispatcher) {
            // The refusal itself belongs to the store — it is the only layer that knows whether an
            // empty set is "the user cleared their headers" or the absence of a read. What this
            // editor owes the user is showing it, and staying dirty so the attempt isn't lost.
            coEvery { serverRepository.headersForServer(liveUrl) } returns null
            coEvery { serverRepository.setHeaders(liveUrl, emptyMap()) } returns
                HeaderWriteResult.Refused(HeaderWriteFailure.UnverifiedDelete)

            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.addHeaderRow()
            viewModel.removeHeaderRow(0)
            viewModel.save()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.saveFailure)
                .isEqualTo(HeaderWriteFailure.UnverifiedDelete)
            assertThat(viewModel.uiState.value.saved).isFalse()
            assertThat(viewModel.uiState.value.isDirty).isTrue()
        }

    /**
     * A refused save leaves the dialog open and otherwise unchanged, so the message has to survive
     * until the user acts on it — and then go, rather than accusing them of a save they've since
     * edited past.
     */
    @Test
    fun `a save failure persists until the next edit`() = runTest(testDispatcher) {
        coEvery { serverRepository.setHeaders(any(), any()) } returns
            HeaderWriteResult.Refused(HeaderWriteFailure.StorageUnavailable)

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.saveFailure).isNotNull()

        viewModel.onValueChanged(0, "id-value")

        assertThat(viewModel.uiState.value.saveFailure).isNull()
    }

    /**
     * The URL is observed, so for a server that never changes the load happens exactly once. Without
     * a re-read a single failed read would keep the editor warning about a store that has since
     * recovered, for the life of the process.
     */
    @Test
    fun `reopening the editor re-reads a store whose first read failed`() = runTest(testDispatcher) {
        coEvery { serverRepository.headersForServer(liveUrl) } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.loadFailed).isTrue()

        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "id-value")
        viewModel.reload()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.loadFailed).isFalse()
        assertThat(viewModel.uiState.value.headers)
            .containsExactly(CustomHeaderRow("CF-Access-Client-Id", "id-value"))
    }

    /**
     * The dirty check has to hold on the far side of the read too. `headersForServer` really suspends
     * when the store is recovering, and the dialog is already on screen by then — so the user types
     * while it runs, and a check made only on entry lets the result land on top of them.
     */
    @Test
    fun `a reload in flight keeps rows typed while it ran`() = runTest(testDispatcher) {
        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "stored-value")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.reload()
        // Not advancing: the read is in flight, exactly as it is while the dialog is open.
        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Secret")
        viewModel.onValueChanged(0, "rotated-secret")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.headers)
            .contains(CustomHeaderRow("CF-Access-Client-Secret", "rotated-secret"))
        assertThat(viewModel.uiState.value.isDirty).isTrue()
    }

    /** Reopening must not cost the user a credential they were part-way through typing. */
    @Test
    fun `reopening the editor keeps unsaved edits`() = runTest(testDispatcher) {
        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "stored-value")

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onValueChanged(0, "half-typed-replacement")

        viewModel.reload()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.headers)
            .containsExactly(CustomHeaderRow("CF-Access-Client-Id", "half-typed-replacement"))
        assertThat(viewModel.uiState.value.isDirty).isTrue()
    }

    @Test
    fun `a non-empty save against an unreadable store still lands`() = runTest(testDispatcher) {
        // Re-entering the credential is the recovery path — refusing it would strand the user on a
        // server they cannot reach.
        coEvery { serverRepository.headersForServer(liveUrl) } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        viewModel.onValueChanged(0, "re-entered")
        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serverRepository.setHeaders(liveUrl, mapOf("CF-Access-Client-Id" to "re-entered"))
        }
        assertThat(viewModel.uiState.value.loadFailed).isFalse()
    }

    @Test
    fun `a server switch whose read fails drops the previous server's rows`() = runTest(testDispatcher) {
        // Otherwise server A's credential sits in server B's editor with Save enabled, one tap from
        // being sent to B's host for the rest of the session.
        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "server-a-secret")
        coEvery { serverRepository.headersForServer(otherUrl) } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onValueChanged(0, "server-a-secret-edited")

        currentUrl.value = otherUrl
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.serverUrl).isEqualTo(otherUrl)
        assertThat(viewModel.uiState.value.headers).isEmpty()
        assertThat(viewModel.uiState.value.isDirty).isFalse()
        assertThat(viewModel.uiState.value.loadFailed).isTrue()
    }

    @Test
    fun `a store that could not persist reports failure, not success`() = runTest(testDispatcher) {
        // A resolved server whose write is refused is a storage failure, not a missing server.
        coEvery { serverRepository.setHeaders(any(), any()) } returns
            HeaderWriteResult.Refused(HeaderWriteFailure.StorageUnavailable)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        viewModel.onValueChanged(0, "id-value")
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(viewModel.uiState.value.saveFailure)
            .isEqualTo(HeaderWriteFailure.StorageUnavailable)
        // Still dirty, so the user can retry rather than being told it worked.
        assertThat(viewModel.uiState.value.isDirty).isTrue()
    }

    /**
     * The editor lives in a dismissible dialog but this ViewModel outlives it, so an abandoned edit
     * has to be reverted to what is actually stored. Otherwise the next open presents a half-typed
     * credential as if it were the active one.
     */
    @Test
    fun `discarding restores the persisted headers and clears the dirty flag`() = runTest(testDispatcher) {
        coEvery { serverRepository.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "id-value")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onValueChanged(0, "half-typed-replacement")
        viewModel.addHeaderRow()
        assertThat(viewModel.uiState.value.isDirty).isTrue()

        viewModel.discardEdits()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.headers)
            .containsExactly(CustomHeaderRow("CF-Access-Client-Id", "id-value"))
        assertThat(viewModel.uiState.value.isDirty).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
        // Discarding is a UI-level revert — it must never write.
        coVerify(exactly = 0) { serverRepository.setHeaders(any(), any()) }
    }

    /** A rejection must not survive the revert and pin an error to a row that no longer exists. */
    @Test
    fun `discarding clears a pending row rejection`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "Host")
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNotNull()

        viewModel.discardEdits()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.headers).isEmpty()
    }

    /** One-shot events: a retained ViewModel must not re-fire the confirmation on a new composition. */
    @Test
    fun `the saved flag is consumable`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        viewModel.onValueChanged(0, "id-value")
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.saved).isTrue()

        viewModel.consumeSaved()

        assertThat(viewModel.uiState.value.saved).isFalse()
    }
}
