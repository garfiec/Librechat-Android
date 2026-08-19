package com.garfiec.librechat.feature.settings.viewmodel

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.model.mcp.McpOAuthConfig
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Which route a save takes, and what a refusal from it does to the dialog.
 *
 * The two are the same subject: `MCP_OAUTH_SECRET_REENTRY_REQUIRED` is raised by the UPDATE route
 * alone (`ServerConfigsDB.update`, reachable only through `PATCH /api/mcp/servers/:serverName`), so
 * an edit sent as a create can never produce it — the re-entry prompt was unreachable for exactly
 * as long as edit mode posted a create. A test that stubs the error and asserts the prompt would
 * pass on that build; asserting the route is what makes it real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModelSaveRouteTest {

    private val mcpRepository = mockk<McpRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { mcpRepository.listServers() } returns Result.Success(emptyList())
        coEvery { mcpRepository.getConnectionStatus() } returns Result.Success(emptyMap())
        coEvery { mcpRepository.getTools() } returns Result.Success(emptyList())
        coEvery { mcpRepository.createServer(any(), any(), any(), any(), any(), any()) } returns
            Result.Success(SERVER)
        coEvery { mcpRepository.updateServer(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.Success(SERVER)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The dialog's own name field is the title; the record is addressed by its stored name. */
    @Test
    fun `saving an edited server patches the stored server`() = runTest {
        val vm = McpViewModel(mcpRepository)
        vm.showEditServerDialog(SERVER)

        vm.saveServer(name = "Docs (renamed)", url = URL, type = McpServerType.SSE)

        coVerify(exactly = 1) {
            mcpRepository.updateServer(
                serverName = "docs_mcp",
                name = "Docs (renamed)",
                description = null,
                url = URL,
                type = McpServerType.SSE,
                apiKey = null,
                oauth = null,
            )
        }
        coVerify(exactly = 0) { mcpRepository.createServer(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `saving a new server still posts a create`() = runTest {
        val vm = McpViewModel(mcpRepository)
        vm.showAddServerDialog()

        vm.saveServer(name = "Docs", url = URL, type = McpServerType.SSE)

        coVerify(exactly = 1) {
            mcpRepository.createServer(
                name = "Docs",
                description = null,
                url = URL,
                type = McpServerType.SSE,
                apiKey = null,
                oauth = null,
            )
        }
        coVerify(exactly = 0) { mcpRepository.updateServer(any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * The stored client secret is bound to the OAuth endpoints it was issued for, so editing either
     * invalidates it and the same body can only be refused again. The dialog has to ask for the
     * secret rather than report a failure the user would retry.
     */
    @Test
    fun `a refused edit asks for the client secret again`() = runTest {
        coEvery { mcpRepository.updateServer(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.Error(
                exception = ApiException(
                    statusCode = 400,
                    message = "Client secret required",
                    body = """{"error":"MCP_OAUTH_SECRET_REENTRY_REQUIRED"}""",
                ),
            )
        val vm = McpViewModel(mcpRepository)
        vm.showEditServerDialog(SERVER)

        vm.saveServer(
            name = "Docs",
            url = URL,
            type = McpServerType.SSE,
            oauth = McpOAuthConfig(authorizationUrl = "https://auth.example.test/authorize"),
        )

        assertThat(vm.uiState.value.oauthSecretReentryRequired).isTrue()
        assertThat(vm.uiState.value.showServerDialog).isTrue()
    }

    /** Any other refusal keeps the generic message; the secret field must not turn red for it. */
    @Test
    fun `an unrelated failure does not ask for the client secret`() = runTest {
        coEvery { mcpRepository.updateServer(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.Error(message = "Server unreachable")
        val vm = McpViewModel(mcpRepository)
        vm.showEditServerDialog(SERVER)

        vm.saveServer(name = "Docs", url = URL, type = McpServerType.SSE)

        assertThat(vm.uiState.value.oauthSecretReentryRequired).isFalse()
        assertThat(vm.uiState.value.error).isEqualTo("Server unreachable")
    }

    private companion object {
        const val URL = "https://docs.example.test/mcp"
        val SERVER = McpServer(
            name = "docs_mcp",
            url = URL,
            type = McpServerType.SSE,
            title = "Docs",
        )
    }
}
