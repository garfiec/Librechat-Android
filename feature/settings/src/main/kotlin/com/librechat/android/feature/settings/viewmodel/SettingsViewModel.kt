package com.librechat.android.feature.settings.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.librechat.android.core.model.Memory
import com.librechat.android.core.model.User
import com.librechat.android.core.model.mcp.McpApiKeyConfig
import com.librechat.android.core.model.mcp.McpOAuthConfig
import com.librechat.android.core.model.mcp.McpServer
import com.librechat.android.core.model.mcp.McpServerStatus
import com.librechat.android.core.model.mcp.McpServerType
import com.librechat.android.core.model.speech.TtsVoice
import com.librechat.android.feature.settings.SharedLinkDisplayData
import com.librechat.android.feature.settings.UserDisplayData
import com.librechat.android.feature.settings.screen.DeviceVoiceInfo
import com.librechat.android.feature.settings.viewmodel.delegate.DataManagementDelegate
import com.librechat.android.feature.settings.viewmodel.delegate.McpServerDelegate
import com.librechat.android.feature.settings.viewmodel.delegate.MemoryManagementDelegate
import com.librechat.android.feature.settings.viewmodel.delegate.SpeechSettingsDelegate
import com.librechat.android.feature.settings.viewmodel.delegate.TwoFactorSecurityDelegate
import com.librechat.android.feature.settings.viewmodel.delegate.isHttpStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class SettingsCommand(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
)

val DEFAULT_COMMANDS = listOf(
    SettingsCommand("help", "Show available commands", true),
    SettingsCommand("clear", "Clear current conversation", true),
    SettingsCommand("new", "Start a new conversation", true),
    SettingsCommand("model", "Switch the current model", true),
    SettingsCommand("system", "Set a system message", true),
    SettingsCommand("fork", "Fork the current conversation", true),
)

@Immutable
data class SettingsUiState(
    val user: UserDisplayData? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedOut: Boolean = false,
    val isAccountDeleted: Boolean = false,
    // Chat preferences
    val chatFontSize: ChatFontSize = ChatFontSize.MEDIUM,
    val autoScrollEnabled: Boolean = true,
    val showThinkingBlocks: Boolean = true,
    val showImageDescriptions: Boolean = false,
    val dismissKeyboardOnSend: Boolean = false,
    // Data management
    val archivedCount: Int = 0,
    val isClearing: Boolean = false,
    val showExportComingSoon: Boolean = false,
    // Security (2FA)
    val isTwoFactorEnabled: Boolean = false,
    val isTwoFactorLoading: Boolean = false,
    val showTwoFactorSetupDialog: Boolean = false,
    val twoFactorOtpauthUrl: String? = null,
    val showBackupCodesDialog: Boolean = false,
    val backupCodes: List<String> = emptyList(),
    val showDisableTwoFactorDialog: Boolean = false,
    val showEnableTwoFactorOtpDialog: Boolean = false,
    val showBackupCodesOtpDialog: Boolean = false,
    val showDeleteAccountOtpDialog: Boolean = false,
    // MCP
    val mcpServers: List<McpServer> = emptyList(),
    val mcpConnectionStatus: Map<String, McpServerStatus> = emptyMap(),
    val mcpError: String? = null,
    val showMcpServerDialog: Boolean = false,
    val editingMcpServer: McpServer? = null,
    val mcpReinitializingServers: Set<String> = emptySet(),
    val mcpReinitializeMessage: String? = null,
    // Speech settings
    val autoReadEnabled: Boolean = false,
    val selectedVoice: TtsVoice? = null,
    val availableVoices: List<TtsVoice> = emptyList(),
    // Memories
    val memories: List<Memory> = emptyList(),
    val memoriesEnabled: Boolean = true,
    val showMemoryDialog: Boolean = false,
    val editingMemory: Memory? = null,
    // Balance
    val tokenCredits: Long = 0,
    val isBalanceLoading: Boolean = false,
    // Avatar
    val showAvatarDialog: Boolean = false,
    val isAvatarUploading: Boolean = false,
    // Shared links
    val sharedLinks: List<SharedLinkDisplayData> = emptyList(),
    val sharedLinksNextCursor: String? = null,
    val sharedLinksHasNextPage: Boolean = false,
    val isSharedLinksLoading: Boolean = false,
    // Speech detail dialogs
    val showSttDetailDialog: Boolean = false,
    val showTtsDetailDialog: Boolean = false,
    val sttEngine: String = "",
    val sttLanguage: String = "",
    val sttAutoSend: Boolean = false,
    val ttsEngine: String = "",
    val ttsVoice: String = "",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsDeviceVoiceName: String = "",
    val ttsCaching: Boolean = true,
    val ttsSource: String = "device",
    val availableDeviceVoices: List<DeviceVoiceInfo> = emptyList(),
    // TTS preview
    val isTtsPreviewPlaying: Boolean = false,
    // Cache / Keys
    val isCacheClearing: Boolean = false,
    val isKeyRevoking: Boolean = false,
    // Language
    val selectedLanguage: String = "en",
    val showLanguageDialog: Boolean = false,
    // Fork settings
    val forkMode: String = "targetLevel",
    val showForkSettingsDialog: Boolean = false,
    // Commands
    val showCommandsScreen: Boolean = false,
    val commands: List<SettingsCommand> = DEFAULT_COMMANDS,
    // Personalization
    val showPersonalizationDialog: Boolean = false,
    val personalizationEnabled: Boolean = true,
    val aboutUser: String = "",
    val responseStyle: String = "",
    // Tablet
    val tabletSidebarGestureEnabled: Boolean = true,
    // Chat layout
    val chatLayoutStyle: String = ChatLayoutConstants.THREAD,
    val showAvatars: Boolean = true,
    val showBubbles: Boolean = false,
    val latexRenderer: LatexRenderer = LatexRenderer.KATEX,
)

private fun User.toDisplayData() = UserDisplayData(
    name = name ?: "",
    email = email,
    username = username ?: "",
    avatar = avatar,
)

/** Intermediate holder for the combined DataStore preferences. */
private data class DataStorePreferences(
    val themeMode: ThemeMode,
    val serverUrl: String,
    val chatFontSize: ChatFontSize,
    val autoScrollEnabled: Boolean,
    val showThinkingBlocks: Boolean,
    val autoReadEnabled: Boolean,
    val selectedVoiceId: String,
)

class SettingsViewModel(
    private val context: Application,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    conversationRepository: ConversationRepository,
    private val themeDataStore: ThemeDataStore,
    serverDataStore: ServerDataStore,
    private val settingsDataStore: SettingsDataStore,
    mcpRepository: McpRepository,
    memoryRepository: MemoryRepository,
    speechRepository: SpeechRepository,
    private val balanceRepository: BalanceRepository,
    shareRepository: ShareRepository,
    keyRepository: KeyRepository,
) : ViewModel() {

    /** Raw state for everything not driven by DataStore flows. */
    private val _uiState = MutableStateFlow(SettingsUiState())

    private val stateHandle = SettingsStateHandle(_uiState, viewModelScope)

    // --- Delegates ---
    private val memoryDelegate = MemoryManagementDelegate(stateHandle, memoryRepository)
    private val mcpDelegate = McpServerDelegate(stateHandle, mcpRepository)
    private val twoFactorDelegate = TwoFactorSecurityDelegate(stateHandle, authRepository)
    private val dataDelegate =
        DataManagementDelegate(stateHandle, context, conversationRepository, shareRepository, keyRepository)
    private val speechDelegate = SpeechSettingsDelegate(stateHandle, context, speechRepository, settingsDataStore)

    /** Combined DataStore preferences flow. */
    private val dataStorePreferences: StateFlow<DataStorePreferences> = combine(
        themeDataStore.themeMode,
        serverDataStore.currentUrlFlow,
        settingsDataStore.chatFontSize,
        settingsDataStore.autoScrollEnabled,
        settingsDataStore.showThinkingBlocks,
    ) { theme, serverUrl, fontSize, autoScroll, showThinking ->
        DataStorePreferences(
            themeMode = theme,
            serverUrl = serverUrl,
            chatFontSize = fontSize,
            autoScrollEnabled = autoScroll,
            showThinkingBlocks = showThinking,
            autoReadEnabled = false,
            selectedVoiceId = "",
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataStorePreferences(
        themeMode = ThemeMode.SYSTEM,
        serverUrl = "",
        chatFontSize = ChatFontSize.MEDIUM,
        autoScrollEnabled = true,
        showThinkingBlocks = true,
        autoReadEnabled = false,
        selectedVoiceId = "",
    ))

    /** Extra DataStore preferences (separate combine since Kotlin combine maxes at 5). */
    private data class ExtraPreferences(
        val autoRead: Boolean,
        val selectedVoiceId: String?,
        val showImageDescriptions: Boolean,
        val dismissKeyboardOnSend: Boolean,
        val ttsSource: String,
    )

    private val extraPreferences: StateFlow<ExtraPreferences> = combine(
        settingsDataStore.autoReadEnabled,
        settingsDataStore.selectedVoiceId,
        settingsDataStore.showImageDescriptions,
        settingsDataStore.dismissKeyboardOnSend,
        settingsDataStore.ttsSource,
    ) { autoRead, voiceId, showImgDesc, dismissKeyboard, ttsSource ->
        ExtraPreferences(autoRead, voiceId, showImgDesc, dismissKeyboard, ttsSource)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExtraPreferences(false, null, false, false, "device"))

    /** Device TTS preferences (speech rate, pitch, voice name, engine, voice selection). */
    private data class DeviceTtsPreferences(
        val speechRate: Float,
        val pitch: Float,
        val voiceName: String,
        val ttsEngine: String,
        val ttsVoice: String,
    )

    private val deviceTtsPreferences: StateFlow<DeviceTtsPreferences> = combine(
        settingsDataStore.ttsSpeechRate,
        settingsDataStore.ttsPitch,
        settingsDataStore.ttsVoiceName,
        settingsDataStore.ttsEngine,
        settingsDataStore.ttsVoice,
    ) { rate, pitch, voiceName, ttsEngine, ttsVoice ->
        DeviceTtsPreferences(rate, pitch, voiceName, ttsEngine, ttsVoice)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DeviceTtsPreferences(1.0f, 1.0f, "", "", ""))

    /** TTS caching preference. Kept as a separate flow to stay within the 5-arg combine limit. */
    private val ttsCachingPreference: StateFlow<Boolean> = settingsDataStore.ttsCaching
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Tablet-specific preferences. */
    private val tabletSidebarGestureEnabled: StateFlow<Boolean> = settingsDataStore.tabletSidebarGestureEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Chat layout preferences. */
    private val chatLayoutStylePref: StateFlow<String> = settingsDataStore.chatLayoutStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatLayoutConstants.THREAD)

    private val showAvatarsPref: StateFlow<Boolean> = settingsDataStore.showAvatars
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val showBubblesPref: StateFlow<Boolean> = settingsDataStore.showBubbles
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val latexRendererPref: StateFlow<LatexRenderer> = settingsDataStore.latexRenderer
        .stateIn(viewModelScope, SharingStarted.Eagerly, LatexRenderer.KATEX)

    /** In-app UI language (ISO code), persisted so it survives process death. */
    private val uiLanguagePref: StateFlow<String> = settingsDataStore.uiLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "en")

    /** Additional preferences combined separately to stay within the 5-arg combine limit. */
    private data class AdditionalPreferences(
        val tabletSidebarGestureEnabled: Boolean,
        val autoSendAfterStt: Boolean,
        val sttEngine: String,
        val sttLanguage: String,
        val ttsCaching: Boolean,
        val chatLayoutStyle: String = ChatLayoutConstants.THREAD,
        val showAvatars: Boolean = true,
        val showBubbles: Boolean = false,
        val latexRenderer: LatexRenderer = LatexRenderer.KATEX,
    )

    private val baseAdditionalPreferences = combine(
        tabletSidebarGestureEnabled,
        settingsDataStore.autoSendAfterStt,
        settingsDataStore.sttEngine,
        settingsDataStore.sttLanguage,
        ttsCachingPreference,
    ) { tabletGesture, autoSendStt, sttEngine, sttLanguage, ttsCaching ->
        AdditionalPreferences(tabletGesture, autoSendStt, sttEngine, sttLanguage, ttsCaching)
    }

    private val additionalPreferences: StateFlow<AdditionalPreferences> = combine(
        baseAdditionalPreferences,
        chatLayoutStylePref,
        showAvatarsPref,
        showBubblesPref,
        latexRendererPref,
    ) { base, layoutStyle, showAvatars, showBubbles, latexRenderer ->
        base.copy(chatLayoutStyle = layoutStyle, showAvatars = showAvatars, showBubbles = showBubbles, latexRenderer = latexRenderer)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AdditionalPreferences(true, false, "", "", true))

    /** Holder so we can merge [uiLanguagePref] without exceeding the 5-flow [combine] overload. */
    private data class UiStateMergeInputs(
        val state: SettingsUiState,
        val prefs: DataStorePreferences,
        val extra: ExtraPreferences,
        val deviceTts: DeviceTtsPreferences,
        val additional: AdditionalPreferences,
    )

    private val uiStateMergeInputs: StateFlow<UiStateMergeInputs> = combine(
        _uiState,
        dataStorePreferences,
        extraPreferences,
        deviceTtsPreferences,
        additionalPreferences,
    ) { state, prefs, extra, deviceTts, additional ->
        UiStateMergeInputs(state, prefs, extra, deviceTts, additional)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UiStateMergeInputs(
            SettingsUiState(),
            DataStorePreferences(
                themeMode = ThemeMode.SYSTEM,
                serverUrl = "",
                chatFontSize = ChatFontSize.MEDIUM,
                autoScrollEnabled = true,
                showThinkingBlocks = true,
                autoReadEnabled = false,
                selectedVoiceId = "",
            ),
            ExtraPreferences(false, null, false, false, "device"),
            DeviceTtsPreferences(1.0f, 1.0f, "", "", ""),
            AdditionalPreferences(true, false, "", "", true),
        ),
    )

    /** The single public UI state that merges DataStore preferences with imperative state. */
    val uiState: StateFlow<SettingsUiState> = combine(
        uiStateMergeInputs,
        uiLanguagePref,
    ) { inputs, uiLang ->
        val selectedVoice = inputs.extra.selectedVoiceId?.let { id ->
            inputs.state.availableVoices.find { it.id == id }
        }
        inputs.state.copy(
            themeMode = inputs.prefs.themeMode,
            serverUrl = inputs.prefs.serverUrl,
            chatFontSize = inputs.prefs.chatFontSize,
            autoScrollEnabled = inputs.prefs.autoScrollEnabled,
            showThinkingBlocks = inputs.prefs.showThinkingBlocks,
            autoReadEnabled = inputs.extra.autoRead,
            selectedVoice = selectedVoice,
            showImageDescriptions = inputs.extra.showImageDescriptions,
            dismissKeyboardOnSend = inputs.extra.dismissKeyboardOnSend,
            ttsSource = inputs.extra.ttsSource,
            ttsSpeechRate = inputs.deviceTts.speechRate,
            ttsPitch = inputs.deviceTts.pitch,
            ttsDeviceVoiceName = inputs.deviceTts.voiceName,
            ttsEngine = inputs.deviceTts.ttsEngine,
            ttsVoice = inputs.deviceTts.ttsVoice,
            ttsCaching = inputs.additional.ttsCaching,
            tabletSidebarGestureEnabled = inputs.additional.tabletSidebarGestureEnabled,
            sttAutoSend = inputs.additional.autoSendAfterStt,
            sttEngine = inputs.additional.sttEngine,
            sttLanguage = inputs.additional.sttLanguage,
            chatLayoutStyle = inputs.additional.chatLayoutStyle,
            showAvatars = inputs.additional.showAvatars,
            showBubbles = inputs.additional.showBubbles,
            latexRenderer = inputs.additional.latexRenderer,
            selectedLanguage = uiLang,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        loadUser()
        mcpDelegate.loadMcpServers()
        memoryDelegate.loadMemories()
        speechDelegate.loadVoices()
        loadBalance()
        speechDelegate.loadDeviceVoices()
    }

    // ── Account & user profile ─────────────────────────────────────

    private fun loadUser() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = userRepository.getUser()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            user = result.data.toDisplayData(),
                            isLoading = false,
                            isTwoFactorEnabled = result.data.twoFactorEnabled,
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message ?: "Failed to load user profile",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    // Avatar

    fun showAvatarDialog() {
        _uiState.update { it.copy(showAvatarDialog = true) }
    }

    fun dismissAvatarDialog() {
        _uiState.update { it.copy(showAvatarDialog = false) }
    }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAvatarUploading = true) }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                _uiState.update {
                    it.copy(isAvatarUploading = false, error = "Could not read selected image")
                }
                return@launch
            }
            when (val result = userRepository.uploadAvatar(bytes)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            user = result.data.toDisplayData(),
                            isAvatarUploading = false,
                            showAvatarDialog = false,
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isAvatarUploading = false,
                            error = result.message ?: "Failed to upload avatar",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    // ── Theme & appearance ─────────────────────────────────────────

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeDataStore.setThemeMode(mode) }
    }

    // ── Chat preferences ───────────────────────────────────────────

    fun setChatFontSize(size: ChatFontSize) {
        viewModelScope.launch { settingsDataStore.setChatFontSize(size) }
    }

    fun setAutoScrollEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoScrollEnabled(enabled) }
    }

    fun setShowThinkingBlocks(show: Boolean) {
        viewModelScope.launch { settingsDataStore.setShowThinkingBlocks(show) }
    }

    fun setShowImageDescriptions(show: Boolean) {
        viewModelScope.launch { settingsDataStore.setShowImageDescriptions(show) }
    }

    fun setDismissKeyboardOnSend(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDismissKeyboardOnSend(enabled) }
    }

    fun setChatLayoutStyle(style: String) {
        viewModelScope.launch { settingsDataStore.setChatLayoutStyle(style) }
    }

    fun setShowAvatars(show: Boolean) {
        viewModelScope.launch { settingsDataStore.setShowAvatars(show) }
    }

    fun setShowBubbles(show: Boolean) {
        viewModelScope.launch { settingsDataStore.setShowBubbles(show) }
    }

    fun setLatexRenderer(renderer: LatexRenderer) {
        viewModelScope.launch { settingsDataStore.setLatexRenderer(renderer) }
    }

    // ── Tablet preferences ─────────────────────────────────────────

    fun setTabletSidebarGestureEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setTabletSidebarGestureEnabled(enabled) }
    }

    // ── Balance ────────────────────────────────────────────────────

    private fun loadBalance() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBalanceLoading = true) }
            when (val result = balanceRepository.getBalance()) {
                is Result.Success -> {
                    _uiState.update { it.copy(tokenCredits = result.data.tokenCredits, isBalanceLoading = false) }
                }
                is Result.Error -> {
                    Timber.d(result.exception, "Failed to load balance: ${result.message}")
                    _uiState.update { it.copy(isBalanceLoading = false) }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    // ── Language ───────────────────────────────────────────────────

    fun showLanguageDialog() {
        _uiState.update { it.copy(showLanguageDialog = true) }
    }

    fun dismissLanguageDialog() {
        _uiState.update { it.copy(showLanguageDialog = false) }
    }

    fun setLanguage(languageCode: String) {
        viewModelScope.launch {
            settingsDataStore.setUiLanguage(languageCode)
        }
        _uiState.update { it.copy(showLanguageDialog = false) }
    }

    // ── Fork settings ──────────────────────────────────────────────

    fun showForkSettingsDialog() {
        _uiState.update { it.copy(showForkSettingsDialog = true) }
    }

    fun dismissForkSettingsDialog() {
        _uiState.update { it.copy(showForkSettingsDialog = false) }
    }

    fun setForkMode(mode: String) {
        _uiState.update { it.copy(forkMode = mode, showForkSettingsDialog = false) }
    }

    // ── Commands ───────────────────────────────────────────────────

    fun showCommandsScreen() {
        _uiState.update { it.copy(showCommandsScreen = true) }
    }

    fun hideCommandsScreen() {
        _uiState.update { it.copy(showCommandsScreen = false) }
    }

    fun toggleCommand(name: String, enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                commands = state.commands.map { cmd ->
                    if (cmd.name == name) cmd.copy(enabled = enabled) else cmd
                },
            )
        }
    }

    // ── Personalization ────────────────────────────────────────────

    fun showPersonalizationDialog() {
        _uiState.update { it.copy(showPersonalizationDialog = true) }
    }

    fun dismissPersonalizationDialog() {
        _uiState.update { it.copy(showPersonalizationDialog = false) }
    }

    fun savePersonalization(aboutUser: String, responseStyle: String, enabled: Boolean) {
        _uiState.update {
            it.copy(
                aboutUser = aboutUser,
                responseStyle = responseStyle,
                personalizationEnabled = enabled,
                showPersonalizationDialog = false,
            )
        }
    }

    // ── Auth actions ───────────────────────────────────────────────

    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            authRepository.logout()
            _uiState.update { it.copy(isLoading = false, isLoggedOut = true) }
        }
    }

    fun deleteAccount(token: String? = null, backupCode: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = userRepository.deleteUser(token = token, backupCode = backupCode)) {
                is Result.Success -> {
                    authRepository.logout()
                    _uiState.update { it.copy(isLoading = false, isAccountDeleted = true, showDeleteAccountOtpDialog = false) }
                }
                is Result.Error -> {
                    val needsOtp = token == null && backupCode == null &&
                        result.isHttpStatus(403)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showDeleteAccountOtpDialog = if (needsOtp) true else it.showDeleteAccountOtpDialog,
                            error = if (needsOtp) null else (result.message ?: "Failed to delete account"),
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissDeleteAccountOtpDialog() {
        _uiState.update { it.copy(showDeleteAccountOtpDialog = false) }
    }

    fun retry() {
        loadUser()
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    // ── Delegated public API ───────────────────────────────────────

    // Memory management
    fun showAddMemoryDialog() = memoryDelegate.showAddMemoryDialog()
    fun showEditMemoryDialog(memory: Memory) = memoryDelegate.showEditMemoryDialog(memory)
    fun dismissMemoryDialog() = memoryDelegate.dismissMemoryDialog()
    fun saveMemory(key: String, value: String) = memoryDelegate.saveMemory(key, value)
    fun deleteMemory(key: String) = memoryDelegate.deleteMemory(key)
    fun toggleMemoriesEnabled(enabled: Boolean) = memoryDelegate.toggleMemoriesEnabled(enabled)

    // MCP server management
    fun showAddMcpServerDialog() = mcpDelegate.showAddMcpServerDialog()
    fun showEditMcpServerDialog(server: McpServer) = mcpDelegate.showEditMcpServerDialog(server)
    fun dismissMcpServerDialog() = mcpDelegate.dismissMcpServerDialog()
    fun saveMcpServer(
        name: String,
        description: String? = null,
        url: String,
        type: McpServerType,
        apiKey: McpApiKeyConfig? = null,
        oauth: McpOAuthConfig? = null,
    ) = mcpDelegate.saveMcpServer(name, description, url, type, apiKey, oauth)
    fun deleteMcpServer(serverName: String) = mcpDelegate.deleteMcpServer(serverName)
    fun reinitializeMcpServer(serverName: String) = mcpDelegate.reinitializeMcpServer(serverName)
    fun dismissMcpReinitializeMessage() = mcpDelegate.dismissMcpReinitializeMessage()

    // Two-factor security
    fun toggleTwoFactor() = twoFactorDelegate.toggleTwoFactor()
    fun enableTwoFactorWithOtp(token: String?, backupCode: String?) =
        twoFactorDelegate.enableTwoFactor(token = token, backupCode = backupCode)
    fun dismissEnableTwoFactorOtpDialog() = twoFactorDelegate.dismissEnableTwoFactorOtpDialog()
    fun confirmEnableTwoFactor(code: String) = twoFactorDelegate.confirmEnableTwoFactor(code)
    fun confirmDisableTwoFactor(code: String) = twoFactorDelegate.confirmDisableTwoFactor(code)
    fun dismissTwoFactorSetupDialog() = twoFactorDelegate.dismissTwoFactorSetupDialog()
    fun dismissDisableTwoFactorDialog() = twoFactorDelegate.dismissDisableTwoFactorDialog()
    fun dismissBackupCodesDialog() = twoFactorDelegate.dismissBackupCodesDialog()
    fun viewBackupCodes() = twoFactorDelegate.viewBackupCodes()
    fun viewBackupCodesWithOtp(token: String?, backupCode: String?) =
        twoFactorDelegate.viewBackupCodes(token = token, backupCode = backupCode)
    fun dismissBackupCodesOtpDialog() = twoFactorDelegate.dismissBackupCodesOtpDialog()

    // Data management
    fun clearAllChats() = dataDelegate.clearAllChats()
    fun exportAllData() = dataDelegate.exportAllData()
    fun dismissExportComingSoon() = dataDelegate.dismissExportComingSoon()
    fun loadSharedLinks() = dataDelegate.loadSharedLinks()
    fun loadMoreSharedLinks() = dataDelegate.loadMoreSharedLinks()
    fun toggleSharedLinkVisibility(shareId: String) = dataDelegate.toggleSharedLinkVisibility(shareId)
    fun deleteSharedLink(shareId: String) = dataDelegate.deleteSharedLink(shareId)
    fun clearCache() = dataDelegate.clearCache()
    fun revokeAllKeys() = dataDelegate.revokeAllKeys()

    // Speech settings
    fun setAutoSendAfterStt(enabled: Boolean) = speechDelegate.setAutoSendAfterStt(enabled)
    fun setAutoReadEnabled(enabled: Boolean) = speechDelegate.setAutoReadEnabled(enabled)
    fun selectVoice(voice: TtsVoice) = speechDelegate.selectVoice(voice)
    fun testVoice() = speechDelegate.testVoice()
    fun previewDeviceTts(text: String, rate: Float, pitch: Float, voiceName: String?) =
        speechDelegate.previewDeviceTts(text, rate, pitch, voiceName)
    fun previewServerTts(text: String, voice: String?, model: String?) =
        speechDelegate.previewServerTts(text, voice, model)
    fun stopTtsPreview() = speechDelegate.stopTtsPreview()
    fun showSttDetailDialog() = speechDelegate.showSttDetailDialog()
    fun dismissSttDetailDialog() = speechDelegate.dismissSttDetailDialog()
    fun saveSttSettings(engine: String, language: String) = speechDelegate.saveSttSettings(engine, language)
    fun showTtsDetailDialog() = speechDelegate.showTtsDetailDialog()
    fun dismissTtsDetailDialog() = speechDelegate.dismissTtsDetailDialog()
    fun saveTtsSettings(
        engine: String,
        voice: String,
        rate: Float,
        pitch: Float,
        deviceVoiceName: String,
        caching: Boolean,
        source: String,
    ) = speechDelegate.saveTtsSettings(engine, voice, rate, pitch, deviceVoiceName, caching, source)

    override fun onCleared() {
        super.onCleared()
        speechDelegate.release()
    }
}
