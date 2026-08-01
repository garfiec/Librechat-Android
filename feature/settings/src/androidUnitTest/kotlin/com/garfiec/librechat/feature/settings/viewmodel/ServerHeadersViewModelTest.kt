package com.garfiec.librechat.feature.settings.viewmodel

import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.ServerHeadersDataStore
import com.garfiec.librechat.core.network.client.HeaderRejection
import com.garfiec.librechat.core.ui.components.CustomHeaderRow
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Post-login gateway-header editing (issue #287). The contract that matters here is that this edits
 * the ACTIVE server and nothing else: it is reached while signed in, so a write keyed on the wrong
 * URL would file the credential under a server the app never contacts while leaving the live one
 * broken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerHeadersViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val serverDataStore = mockk<ServerDataStore>(relaxed = true)
    private val serverHeadersDataStore = mockk<ServerHeadersDataStore>(relaxed = true)

    private val liveUrl = "https://gateway.example.com"
    private val otherUrl = "https://other.example.com"

    /** Drives the observed server URL, so a test can switch servers under a composed screen. */
    private val currentUrl = MutableStateFlow(liveUrl)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { serverDataStore.currentUrlFlow } returns currentUrl
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { serverHeadersDataStore.setHeaders(any(), any()) } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ServerHeadersViewModel(
        serverDataStore = serverDataStore,
        serverHeadersDataStore = serverHeadersDataStore,
    )

    @Test
    fun `loads the active server's saved headers`() = runTest(testDispatcher) {
        coEvery { serverHeadersDataStore.headersForServer(liveUrl) } returns
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
            serverHeadersDataStore.setHeaders(liveUrl, mapOf("CF-Access-Client-Secret" to "secret-value"))
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
        coVerify(exactly = 0) { serverHeadersDataStore.setHeaders(any(), any()) }
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
        coVerify(exactly = 0) { serverHeadersDataStore.setHeaders(any(), any()) }
    }

    @Test
    fun `clearing every row saves an empty map rather than skipping the write`() = runTest(testDispatcher) {
        coEvery { serverHeadersDataStore.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "id-value")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.removeHeaderRow(0)
        viewModel.save()
        advanceUntilIdle()

        // Removing the last header has to reach the store — otherwise a user who deletes a stale
        // credential keeps sending it.
        coVerify(exactly = 1) { serverHeadersDataStore.setHeaders(liveUrl, emptyMap()) }
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
        coVerify(exactly = 1) { serverHeadersDataStore.setHeaders(liveUrl, emptyMap()) }
    }

    /**
     * This screen can stay composed across an account switch — a two-pane tablet layout never
     * navigates away from it. A URL captured once would file the edited credential under the previous
     * server's id: breaking the server being edited AND overwriting the one that was working.
     */
    @Test
    fun `a server switch under a composed screen re-targets the save`() = runTest(testDispatcher) {
        coEvery { serverHeadersDataStore.headersForServer(liveUrl) } returns
            mapOf("CF-Access-Client-Id" to "server-a-value")
        coEvery { serverHeadersDataStore.headersForServer(otherUrl) } returns emptyMap()

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
            serverHeadersDataStore.setHeaders(otherUrl, mapOf("CF-Access-Client-Id" to "server-b-value"))
        }
        coVerify(exactly = 0) { serverHeadersDataStore.setHeaders(liveUrl, any()) }
    }

    /**
     * The Save button is enabled as soon as a row is edited, which can be before ServerDataStore has
     * warmed up. Returning silently there would drop the credential with no write, no error and no
     * way to retry.
     */
    @Test
    fun `a save before the URL resolves still lands, keeping the typed rows`() = runTest(testDispatcher) {
        currentUrl.value = ""
        coEvery { serverHeadersDataStore.headersForServer(any()) } returns emptyMap()

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
            serverHeadersDataStore.setHeaders(liveUrl, mapOf("CF-Access-Client-Id" to "id-value"))
        }
        assertThat(viewModel.uiState.value.saved).isTrue()
    }

    /** The first URL resolution must not wipe rows typed while it was still pending. */
    @Test
    fun `the first URL resolution keeps rows typed before it landed`() = runTest(testDispatcher) {
        currentUrl.value = ""
        coEvery { serverHeadersDataStore.headersForServer(liveUrl) } returns
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

    /**
     * setHeaders no-ops when the URL yields no server id. Confirming "saved" over that is a lie the
     * user cannot see through: the token is gone, the button greys out, and every request keeps
     * failing at the gateway with an unrelated-looking connection error.
     */
    @Test
    fun `a store that could not persist reports failure, not success`() = runTest(testDispatcher) {
        coEvery { serverHeadersDataStore.setHeaders(any(), any()) } returns false

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addHeaderRow()
        viewModel.onNameChanged(0, "CF-Access-Client-Id")
        viewModel.onValueChanged(0, "id-value")
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(viewModel.uiState.value.saveFailure)
            .isEqualTo(ServerHeadersSaveFailure.NoActiveServer)
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
        coEvery { serverHeadersDataStore.headersForServer(liveUrl) } returns
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
        coVerify(exactly = 0) { serverHeadersDataStore.setHeaders(any(), any()) }
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
