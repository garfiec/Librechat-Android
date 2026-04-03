package com.librechat.android.feature.settings.viewmodel

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.librechat.android.core.common.ChatLayoutConstants
import com.librechat.android.core.common.result.Result
import com.librechat.android.core.data.datastore.ChatFontSize
import com.librechat.android.core.data.datastore.LatexRenderer
import com.librechat.android.core.data.datastore.ServerDataStore
import com.librechat.android.core.data.datastore.SettingsDataStore
import com.librechat.android.core.data.datastore.ThemeDataStore
import com.librechat.android.core.data.datastore.ThemeMode
import com.librechat.android.core.data.repository.AuthRepository
import com.librechat.android.core.data.repository.BalanceRepository
import com.librechat.android.core.data.repository.ConversationRepository
import com.librechat.android.core.data.repository.KeyRepository
import com.librechat.android.core.data.repository.McpRepository
import com.librechat.android.core.data.repository.MemoryRepository
import com.librechat.android.core.data.repository.ShareRepository
import com.librechat.android.core.data.repository.SpeechRepository
import com.librechat.android.core.data.repository.UserRepository
import com.librechat.android.core.model.User
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val context = mockk<Application>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val themeDataStore = mockk<ThemeDataStore>(relaxed = true)
    private val serverDataStore = mockk<ServerDataStore>(relaxed = true)
    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
    private val mcpRepository = mockk<McpRepository>(relaxed = true)
    private val memoryRepository = mockk<MemoryRepository>(relaxed = true)
    private val speechRepository = mockk<SpeechRepository>(relaxed = true)
    private val balanceRepository = mockk<BalanceRepository>(relaxed = true)
    private val shareRepository = mockk<ShareRepository>(relaxed = true)
    private val keyRepository = mockk<KeyRepository>(relaxed = true)

    private val uiLanguageFlow = MutableStateFlow("en")

    private val testUser = User(
        email = "test@example.com",
        name = "Test User",
        username = "testuser",
        avatar = "https://example.com/avatar.png",
        twoFactorEnabled = false,
    )

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Setup DataStore flows
        every { themeDataStore.themeMode } returns MutableStateFlow(ThemeMode.SYSTEM)
        every { serverDataStore.currentUrlFlow } returns MutableStateFlow("https://chat.example.com")
        every { settingsDataStore.chatFontSize } returns MutableStateFlow(ChatFontSize.MEDIUM)
        every { settingsDataStore.autoScrollEnabled } returns MutableStateFlow(true)
        every { settingsDataStore.showThinkingBlocks } returns MutableStateFlow(true)
        every { settingsDataStore.autoReadEnabled } returns MutableStateFlow(false)
        every { settingsDataStore.selectedVoiceId } returns MutableStateFlow(null)
        every { settingsDataStore.showImageDescriptions } returns MutableStateFlow(false)
        every { settingsDataStore.dismissKeyboardOnSend } returns MutableStateFlow(false)
        every { settingsDataStore.ttsSource } returns MutableStateFlow("device")
        every { settingsDataStore.ttsSpeechRate } returns MutableStateFlow(1.0f)
        every { settingsDataStore.ttsPitch } returns MutableStateFlow(1.0f)
        every { settingsDataStore.ttsVoiceName } returns MutableStateFlow("")
        every { settingsDataStore.ttsEngine } returns MutableStateFlow("")
        every { settingsDataStore.ttsVoice } returns MutableStateFlow("")
        every { settingsDataStore.ttsCaching } returns MutableStateFlow(true)
        every { settingsDataStore.tabletSidebarGestureEnabled } returns MutableStateFlow(true)
        every { settingsDataStore.autoSendAfterStt } returns MutableStateFlow(false)
        every { settingsDataStore.sttEngine } returns MutableStateFlow("")
        every { settingsDataStore.sttLanguage } returns MutableStateFlow("")
        every { settingsDataStore.uiLanguage } returns uiLanguageFlow
        coEvery { settingsDataStore.setUiLanguage(any()) } coAnswers {
            uiLanguageFlow.value = invocation.args[0] as String
        }
        every { settingsDataStore.chatLayoutStyle } returns MutableStateFlow(ChatLayoutConstants.THREAD)
        every { settingsDataStore.showAvatars } returns MutableStateFlow(true)
        every { settingsDataStore.showBubbles } returns MutableStateFlow(false)
        every { settingsDataStore.latexRenderer } returns MutableStateFlow(LatexRenderer.KATEX)
        every { settingsDataStore.bookmarkedConversationIds } returns MutableStateFlow(emptySet())

        // Setup default API responses
        coEvery { userRepository.getUser() } returns Result.Success(testUser)
        coEvery { mcpRepository.listServers() } returns Result.Success(emptyList())
        coEvery { mcpRepository.getConnectionStatus() } returns Result.Success(emptyMap())
        coEvery { memoryRepository.getMemories() } returns Result.Success(emptyList())
        coEvery { speechRepository.getVoices() } returns Result.Success(emptyList())
        coEvery { balanceRepository.getBalance() } returns Result.Error(message = "Not available")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = SettingsViewModel(
        context = context,
        userRepository = userRepository,
        authRepository = authRepository,
        conversationRepository = conversationRepository,
        themeDataStore = themeDataStore,
        serverDataStore = serverDataStore,
        settingsDataStore = settingsDataStore,
        mcpRepository = mcpRepository,
        memoryRepository = memoryRepository,
        speechRepository = speechRepository,
        balanceRepository = balanceRepository,
        shareRepository = shareRepository,
        keyRepository = keyRepository,
    )

    @Test
    fun `initial state loads user profile`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.user).isNotNull()
        assertThat(state.user?.name).isEqualTo("Test User")
        assertThat(state.user?.email).isEqualTo("test@example.com")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `user load failure shows error`() = runTest {
        coEvery { userRepository.getUser() } returns Result.Error(message = "Network error")

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.user).isNull()
        assertThat(state.error).isEqualTo("Network error")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `logout calls authRepository and sets isLoggedOut`() = runTest {
        coEvery { authRepository.logout() } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.logout()
        advanceUntilIdle()

        coVerify { authRepository.logout() }
        assertThat(viewModel.uiState.value.isLoggedOut).isTrue()
    }

    @Test
    fun `deleteAccount calls userRepository then authRepository`() = runTest {
        coEvery { userRepository.deleteUser() } returns Result.Success(Unit)
        coEvery { authRepository.logout() } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteAccount()
        advanceUntilIdle()

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            userRepository.deleteUser()
            authRepository.logout()
        }
        assertThat(viewModel.uiState.value.isAccountDeleted).isTrue()
    }

    @Test
    fun `deleteAccount failure shows error`() = runTest {
        coEvery { userRepository.deleteUser() } returns Result.Error(message = "Server error")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteAccount()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Server error")
        assertThat(viewModel.uiState.value.isAccountDeleted).isFalse()
    }

    @Test
    fun `setThemeMode calls themeDataStore`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        coVerify { themeDataStore.setThemeMode(ThemeMode.DARK) }
    }

    @Test
    fun `clearAllChats calls conversationRepository deleteAll`() = runTest {
        coEvery { conversationRepository.deleteAll() } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.clearAllChats()
        advanceUntilIdle()

        coVerify { conversationRepository.deleteAll() }
        assertThat(viewModel.uiState.value.isClearing).isFalse()
    }

    @Test
    fun `clearAllChats failure shows error`() = runTest {
        coEvery { conversationRepository.deleteAll() } returns
            Result.Error(message = "Failed to clear")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.clearAllChats()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Failed to clear")
    }

    @Test
    fun `dismissError clears error state`() = runTest {
        coEvery { userRepository.getUser() } returns Result.Error(message = "Error")

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNotNull()

        viewModel.dismissError()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `twoFactorEnabled state reflects user profile`() = runTest {
        val userWith2FA = testUser.copy(twoFactorEnabled = true)
        coEvery { userRepository.getUser() } returns Result.Success(userWith2FA)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isTwoFactorEnabled).isTrue()
    }

    @Test
    fun `revokeAllKeys calls keyRepository`() = runTest {
        coEvery { keyRepository.deleteAllKeys() } returns Result.Success(Unit)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.revokeAllKeys()
        advanceUntilIdle()

        coVerify { keyRepository.deleteAllKeys() }
        assertThat(viewModel.uiState.value.isKeyRevoking).isFalse()
    }

    @Test
    fun `revokeAllKeys failure shows error`() = runTest {
        coEvery { keyRepository.deleteAllKeys() } returns
            Result.Error(message = "Failed to revoke")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.revokeAllKeys()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("Failed to revoke")
    }

    @Test
    fun `setLanguage updates language and dismisses dialog`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showLanguageDialog()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.showLanguageDialog).isTrue()

        viewModel.setLanguage("fr")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.selectedLanguage).isEqualTo("fr")
        assertThat(viewModel.uiState.value.showLanguageDialog).isFalse()
    }

    @Test
    fun `setForkMode updates mode and dismisses dialog`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.showForkSettingsDialog()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.showForkSettingsDialog).isTrue()

        viewModel.setForkMode("allBranches")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.forkMode).isEqualTo("allBranches")
        assertThat(viewModel.uiState.value.showForkSettingsDialog).isFalse()
    }

    @Test
    fun `toggleCommand updates command enabled state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleCommand("help", false)
        advanceUntilIdle()

        val helpCmd = viewModel.uiState.value.commands.find { it.name == "help" }
        assertThat(helpCmd?.enabled).isFalse()
    }

    @Test
    fun `retry reloads user profile`() = runTest {
        coEvery { userRepository.getUser() } returns Result.Success(testUser)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        // Called twice: once in init, once in retry
        coVerify(exactly = 2) { userRepository.getUser() }
    }

    @Test
    fun `exportAllData shows coming soon flag`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.exportAllData()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showExportComingSoon).isTrue()

        viewModel.dismissExportComingSoon()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.showExportComingSoon).isFalse()
    }
}
