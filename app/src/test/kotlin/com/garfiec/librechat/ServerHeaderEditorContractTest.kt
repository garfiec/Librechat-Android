package com.garfiec.librechat

import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.db.dao.ServerDao
import com.garfiec.librechat.core.data.db.entity.ServerEntity
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ServerRepositoryImpl
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.ui.components.CustomHeaderRow
import com.garfiec.librechat.feature.auth.viewmodel.ServerUrlViewModel
import com.garfiec.librechat.feature.settings.viewmodel.ServerHeadersViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The one contract both gateway-header editors owe the user (issue #287):
 *
 * **A credential that is on disk is only ever removed by a user action that named it.**
 *
 * Lives in `:app` because it is the only module that can see both editors, and because every defect
 * this file exists for was a *composition* fault — the editor and the store each behaving correctly
 * on their own. Both ViewModels are therefore driven against a **real** [ServerRepositoryImpl] over
 * an in-memory DAO, not a mocked repository: a `mockk<ServerRepository>` cannot exhibit a failed
 * read, a refused write, or a read that suspends, which is exactly the state space these bugs live
 * in. The per-editor tests in each feature module keep their mocks for the cases that don't need a
 * real store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerHeaderEditorContractTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private val liveUrl = "https://gateway.example.com"
    private val liveId = deriveServerId(liveUrl).value
    private val storedHeaders = mapOf("CF-Access-Client-Id" to "id-value")

    private val dao = InMemoryServerDao()
    private val serverDataStore = mockk<ServerDataStore>(relaxed = true)
    private val configRepository = mockk<ConfigRepository>(relaxed = true)
    private val accountSwitcher = mockk<AccountSwitcher>(relaxed = true)

    private val currentUrl = MutableStateFlow(liveUrl)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { serverDataStore.currentUrlFlow } returns currentUrl
        coEvery { serverDataStore.awaitBaseUrl() } returns liveUrl
        coEvery { configRepository.probeServerUrl() } returns
            Result.Success(StartupConfig(serverDomain = liveUrl))
        coEvery { configRepository.validateServerUrl(any()) } returns
            Result.Success(StartupConfig(serverDomain = liveUrl))
        coEvery { accountSwitcher.withPendingIdentity(any<suspend () -> Result<StartupConfig>>()) } coAnswers {
            firstArg<suspend () -> Result<StartupConfig>>().invoke()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository() = ServerRepositoryImpl(
        serverDao = dao,
        json = json,
        appScope = CoroutineScope(testDispatcher),
        ioDispatcher = testDispatcher,
    )

    private fun storeHeaders(headers: Map<String, String> = storedHeaders) {
        dao.rows[liveId] = ServerEntity(liveId, json.encodeToString(headers))
    }

    /**
     * Add-account mode prefills the URL with the ACTIVE server but starts with an empty editor by
     * design, so "the editor is empty" here never means "the user cleared their headers". Touching a
     * row must not turn that emptiness into a delete against the server the user is still signed in
     * to — the credential is plaintext-only with no second copy, and the failure that follows blames
     * the URL.
     */
    @Test
    fun `add mode with a touched but blank editor keeps the active server's credential`() =
        runTest(testDispatcher) {
            storeHeaders()
            val viewModel = ServerUrlViewModel(
                serverDataStore = serverDataStore,
                serverRepository = repository(),
                configRepository = configRepository,
                accountSwitcher = accountSwitcher,
                addAccount = true,
            )
            advanceUntilIdle()

            // The user expands Advanced and taps "+ Add header", then thinks better of it and
            // connects on the prefilled URL without filling the row in.
            viewModel.addHeaderRow()
            viewModel.validateAndConnect()
            advanceUntilIdle()

            assertThat(dao.rows).containsKey(liveId)
        }

    /**
     * A write the store accepted must be what the next read returns. Otherwise the editor tells the
     * user their credential saved, then tells them it can't be read, and they re-enter a secret that
     * every request is already carrying.
     */
    @Test
    fun `a save that lands is what the next open shows, even on a store that cannot be read`() =
        runTest(testDispatcher) {
            // Reads throw, writes succeed — the state the whole readFailed flag exists to describe.
            dao.failReads = true
            val viewModel = settingsEditor()
            advanceUntilIdle()

            viewModel.addHeaderRow()
            viewModel.onNameChanged(0, "CF-Access-Client-Id")
            viewModel.onValueChanged(0, "re-entered")
            viewModel.save()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.saved).isTrue()

            viewModel.reload()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.headers)
                .containsExactly(CustomHeaderRow("CF-Access-Client-Id", "re-entered"))
            assertThat(viewModel.uiState.value.loadFailed).isFalse()
        }

    /**
     * `reload()` checks `isDirty` before its read, but the read genuinely suspends when the store is
     * recovering — and the dialog is already on screen by then, so the user is typing into it.
     */
    @Test
    fun `a reload in flight does not swallow what the user types while it runs`() =
        runTest(testDispatcher) {
            storeHeaders()
            dao.failReads = true
            val viewModel = settingsEditor()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.loadFailed).isTrue()

            // The store recovers, and the user reopens the editor: reload() starts a read that has to
            // go to the database.
            dao.failReads = false
            viewModel.reload()

            // They start typing before it lands, which is the whole point of the warning they are
            // looking at.
            viewModel.addHeaderRow()
            viewModel.onNameChanged(0, "CF-Access-Client-Secret")
            viewModel.onValueChanged(0, "rotated-secret")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.headers)
                .containsExactly(CustomHeaderRow("CF-Access-Client-Secret", "rotated-secret"))
            assertThat(viewModel.uiState.value.isDirty).isTrue()
        }

    private fun settingsEditor() = ServerHeadersViewModel(
        serverDataStore = serverDataStore,
        serverRepository = repository(),
    )

    /**
     * The table as a plain map, with reads and writes independently switchable to a failure. Enough
     * to reach every state [ServerRepositoryImpl] models; Room's own behaviour is covered by
     * `ServerRepositoryImplTest`.
     */
    private class InMemoryServerDao : ServerDao {
        val rows = mutableMapOf<String, ServerEntity>()
        var failReads = false
        var failWrites = false

        override suspend fun getAll(): List<ServerEntity> =
            if (failReads) error("database unavailable") else rows.values.toList()

        override suspend fun upsert(server: ServerEntity) {
            if (failWrites) error("database unavailable")
            rows[server.serverId] = server
        }

        override suspend fun deleteById(serverId: String) {
            if (failWrites) error("database unavailable")
            rows.remove(serverId)
        }
    }
}
