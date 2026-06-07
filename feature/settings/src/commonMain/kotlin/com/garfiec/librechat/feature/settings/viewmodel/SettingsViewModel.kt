package com.garfiec.librechat.feature.settings.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.data.datastore.ThemeDataStore
import com.garfiec.librechat.core.data.datastore.ThemeMode
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.BalanceRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.MemoryRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.logging.DiagnosticLogRepository
import com.garfiec.librechat.core.model.Memory
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.config.BuildInfo
import com.garfiec.librechat.core.model.mcp.McpApiKeyConfig
import com.garfiec.librechat.core.model.mcp.McpOAuthConfig
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerStatus
import com.garfiec.librechat.core.model.mcp.McpServerType
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.model.speech.TtsVoice
import com.garfiec.librechat.feature.settings.model.SharedLinkDisplayData
import com.garfiec.librechat.feature.settings.model.UserDisplayData
import com.garfiec.librechat.feature.settings.screen.DeviceVoiceInfo
import com.garfiec.librechat.feature.settings.util.ContentReader
import com.garfiec.librechat.feature.settings.util.PlatformCacheCleaner
import com.garfiec.librechat.feature.settings.viewmodel.delegate.DataManagementDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.McpServerDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.MemoryManagementDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.SpeechSettingsFactory
import com.garfiec.librechat.feature.settings.viewmodel.delegate.TwoFactorSecurityDelegate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsCommand(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
)

/** One-shot diagnostic-log export payload handed from the ViewModel to the platform file saver. */
@Immutable
data class LogsExportPayload(
    val content: String,
    val fileName: String,
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
    /** Human-facing app version (e.g. `0.1.0`), sourced from the installed package. */
    val appVersion: String = "",
    /** Short git commit the build was cut from (e.g. `1a2b3c4d`), or `unknown`. */
    val gitSha: String = "",
    val isDeletingAccount: Boolean = false,
    /**
     * Transient errors surfaced via snackbar; cleared by [SettingsViewModel.dismissError]
     * after the snackbar is shown/acted on. Used for one-shot failures: avatar upload,
     * account-deletion submit, 2FA mutations, etc. Do NOT use this for profile-fetch
     * failures — those are sticky and must remain visible until the user retries.
     * See [profileLoadError].
     */
    val error: String? = null,
    /**
     * Sticky inline error for profile-fetch failures. Rendered as an `ErrorBanner` inside
     * the Account section so the rest of the screen (Sign Out, Delete Account, etc.) stays
     * reachable while the server is unreachable. Cleared at the start of each `loadUser()`.
     */
    val profileLoadError: String? = null,
    val isLoggedOut: Boolean = false,
    val isAccountDeleted: Boolean = false,
    /**
     * Mirrors `StartupConfig.allowAccountDeletion`. New in v0.8.5 — older servers
     * don't send the flag, so it defaults to `true` and the Delete Account button
     * stays visible. When the server sends `false`, mobile hides the button to
     * avoid a guaranteed 403 on submission.
     */
    val allowAccountDeletion: Boolean = true,
    /**
     * Server build metadata (commit/branch/buildDate) from `/api/config`
     * (`StartupConfig.buildInfo`, gated by `interface.buildInfo`). null when the
     * server doesn't report it; the About section omits the rows in that case.
     */
    val buildInfo: BuildInfo? = null,
    /** True only when the authenticated user's system role is ADMIN. Gates the
     *  admin role-skills row fail-CLOSED (defaults false until the profile loads). */
    val isAdmin: Boolean = false,
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
    // Diagnostic logs (issue #96)
    val isLogsExporting: Boolean = false,
    val isLogsClearing: Boolean = false,
    val logsBufferBytes: Long = 0,
    /**
     * One-shot export payload. Set when [SettingsViewModel.exportLogs] finishes reading the
     * buffer; the screen observes it, hands it to the platform `LogFileSaver`, then calls
     * [SettingsViewModel.consumeLogsExport] to clear it (so a recomposition doesn't re-trigger
     * the save). Mirrors the conversations `ExportReady` event but state-based, since Settings
     * has no events SharedFlow.
     */
    val logsExportReady: LogsExportPayload? = null,
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
    // Mobile-only: how pinned models/agents surface in the model-selection sheet
    val starredModelsDisplay: StarredModelsDisplay = StarredModelsDisplay.OFF,
    // Inline artifact rendering (per-type toggles)
    val inlineArtifactPrefs: InlineArtifactPrefs = InlineArtifactPrefs(),
    // Role-permission gates. `serverMemoriesEnabled` is the SERVER-level MEMORIES.USE
    // gate and is orthogonal to [memoriesEnabled], which is the user's own opt-out
    // stored on their profile (`user.personalization.memories`).
    val mcpServersEnabled: Boolean = true,
    val mcpServersCreateEnabled: Boolean = true,
    val serverMemoriesEnabled: Boolean = true,
    val remoteAgentsEnabled: Boolean = true,
    val remoteAgentsCreateEnabled: Boolean = true,
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

@Suppress("LongParameterList")
class SettingsViewModel(
    private val contentReader: ContentReader,
    private val cacheCleaner: PlatformCacheCleaner,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    conversationRepository: ConversationRepository,
    private val themeDataStore: ThemeDataStore,
    serverDataStore: ServerDataStore,
    private val settingsDataStore: SettingsDataStore,
    mcpRepository: McpRepository,
    memoryRepository: MemoryRepository,
    speechSettingsFactory: SpeechSettingsFactory,
    private val balanceRepository: BalanceRepository,
    shareRepository: ShareRepository,
    keyRepository: KeyRepository,
    private val roleRepository: RoleRepository,
    private val permissionGate: PermissionGate,
    private val configRepository: ConfigRepository,
    diagnosticLogRepository: DiagnosticLogRepository,
    appInfo: AppInfo,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** Raw state for everything not driven by DataStore flows. */
    private val _uiState = MutableStateFlow(
        SettingsUiState(appVersion = appInfo.versionName, gitSha = appInfo.gitSha),
    )

    private val stateHandle = SettingsStateHandle(_uiState, viewModelScope)

    // In-flight profile-load job. Cancelled before each retry so a hung 90s request
    // doesn't continue racing the fresh one.
    private var loadUserJob: Job? = null

    // --- Delegates ---
    private val speechDelegate = speechSettingsFactory.create(stateHandle)
    private val memoryDelegate = MemoryManagementDelegate(stateHandle, memoryRepository)
    private val mcpDelegate = McpServerDelegate(stateHandle, mcpRepository)
    private val twoFactorDelegate = TwoFactorSecurityDelegate(stateHandle, authRepository)
    private val dataDelegate =
        DataManagementDelegate(
            stateHandle,
            cacheCleaner,
            conversationRepository,
            shareRepository,
            keyRepository,
            diagnosticLogRepository,
        )

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

    private val inlineArtifactPrefsFlow: StateFlow<InlineArtifactPrefs> = settingsDataStore.inlineArtifactPrefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, InlineArtifactPrefs())

    private val starredModelsDisplayPref: StateFlow<StarredModelsDisplay> = settingsDataStore.starredModelsDisplay
        .stateIn(viewModelScope, SharingStarted.Eagerly, StarredModelsDisplay.OFF)

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
        val inlineArtifactPrefs: InlineArtifactPrefs = InlineArtifactPrefs(),
        val starredModelsDisplay: StarredModelsDisplay = StarredModelsDisplay.OFF,
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
    }.combine(inlineArtifactPrefsFlow) { additional, inlineArtifact ->
        additional.copy(inlineArtifactPrefs = inlineArtifact)
    }.combine(starredModelsDisplayPref) { additional, starredDisplay ->
        additional.copy(starredModelsDisplay = starredDisplay)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AdditionalPreferences(true, false, "", "", true))

    /** The single public UI state that merges DataStore preferences with imperative state. */
    val uiState: StateFlow<SettingsUiState> = combine(
        _uiState,
        dataStorePreferences,
        extraPreferences,
        deviceTtsPreferences,
        additionalPreferences,
    ) { state, prefs, extra, deviceTts, additional ->
        val selectedVoice = extra.selectedVoiceId?.let { id -> state.availableVoices.find { it.id == id } }
        state.copy(
            themeMode = prefs.themeMode,
            serverUrl = prefs.serverUrl,
            chatFontSize = prefs.chatFontSize,
            autoScrollEnabled = prefs.autoScrollEnabled,
            showThinkingBlocks = prefs.showThinkingBlocks,
            autoReadEnabled = extra.autoRead,
            selectedVoice = selectedVoice,
            showImageDescriptions = extra.showImageDescriptions,
            dismissKeyboardOnSend = extra.dismissKeyboardOnSend,
            ttsSource = extra.ttsSource,
            ttsSpeechRate = deviceTts.speechRate,
            ttsPitch = deviceTts.pitch,
            ttsDeviceVoiceName = deviceTts.voiceName,
            ttsEngine = deviceTts.ttsEngine,
            ttsVoice = deviceTts.ttsVoice,
            ttsCaching = additional.ttsCaching,
            tabletSidebarGestureEnabled = additional.tabletSidebarGestureEnabled,
            sttAutoSend = additional.autoSendAfterStt,
            sttEngine = additional.sttEngine,
            sttLanguage = additional.sttLanguage,
            chatLayoutStyle = additional.chatLayoutStyle,
            showAvatars = additional.showAvatars,
            showBubbles = additional.showBubbles,
            latexRenderer = additional.latexRenderer,
            inlineArtifactPrefs = additional.inlineArtifactPrefs,
            starredModelsDisplay = additional.starredModelsDisplay,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        loadUser()
        speechDelegate.loadVoices()
        loadBalance()
        speechDelegate.loadDeviceVoices()
        loadRoleGatedData()
        observePermissionFlags()
        observeAccountDeletionPolicy()
        dataDelegate.loadLogsBufferSize()
    }

    /**
     * Gated loads share a single 5-second role-await budget so offline/timeout launches
     * don't serialize into N×5s. `role?.hasAccess(...) != false` preserves permissive
     * default: null role → true, missing type/action → true.
     */
    private fun loadRoleGatedData() {
        viewModelScope.launch {
            val role = permissionGate.awaitRole()
            if (role?.hasAccess(PermissionType.MCP_SERVERS, Permission.USE) != false) {
                mcpDelegate.loadMcpServers()
            }
            if (role?.hasAccess(PermissionType.MEMORIES, Permission.USE) != false) {
                memoryDelegate.loadMemories()
            }
        }
    }

    /**
     * Continuous collector — the 5 role-driven flags on SettingsUiState stay in sync
     * with the current role. Permissive while role is null.
     */
    private fun observePermissionFlags() {
        viewModelScope.launch {
            roleRepository.userPermissions.collect { role ->
                _uiState.update {
                    it.copy(
                        mcpServersEnabled = role.hasAccessOrPermissive(PermissionType.MCP_SERVERS, Permission.USE),
                        mcpServersCreateEnabled = role.hasAccessOrPermissive(PermissionType.MCP_SERVERS, Permission.CREATE),
                        serverMemoriesEnabled = role.hasAccessOrPermissive(PermissionType.MEMORIES, Permission.USE),
                        remoteAgentsEnabled = role.hasAccessOrPermissive(PermissionType.REMOTE_AGENTS, Permission.USE),
                        remoteAgentsCreateEnabled = role.hasAccessOrPermissive(PermissionType.REMOTE_AGENTS, Permission.CREATE),
                    )
                }
            }
        }
    }

    /**
     * Observes the v0.8.5+ `allowAccountDeletion` flag from `/api/config`.
     * Defaults to `true` (older-server behavior) when the field is absent or
     * the config hasn't loaded yet — see VERSION_GATES.md guideline #2.
     */
    private fun observeAccountDeletionPolicy() {
        viewModelScope.launch {
            configRepository.startupConfig.collect { config ->
                _uiState.update {
                    it.copy(
                        allowAccountDeletion = config?.allowAccountDeletion ?: true,
                        buildInfo = config?.buildInfo,
                    )
                }
            }
        }
    }

    // ── Account & user profile ─────────────────────────────────────

    private fun loadUser() {
        loadUserJob?.cancel()
        loadUserJob = viewModelScope.launch {
            _uiState.update { it.copy(profileLoadError = null) }
            when (val result = userRepository.getUser()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            user = result.data.toDisplayData(),
                            profileLoadError = null,
                            isTwoFactorEnabled = result.data.twoFactorEnabled,
                            // Fail-CLOSED admin gate: the role-skills admin row shows
                            // only for the ADMIN system role (mirrors the server's
                            // manageRoles middleware). Server also 403s non-admins.
                            isAdmin = result.data.role == ADMIN_ROLE,
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            profileLoadError = result.message ?: "Failed to load user profile",
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

    fun uploadAvatar(uri: Any) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAvatarUploading = true) }
            // Reading bytes off the URI is blocking I/O — keep it off the Main
            // dispatcher (viewModelScope = Main.immediate) to avoid an ANR on
            // large images. Mirrors the FileAttachmentDelegate fix.
            val bytes = try {
                withContext(ioDispatcher) { contentReader.readBytes(uri) }
            } catch (e: CancellationException) {
                // Cooperative cancellation must propagate (SKIE/iOS requirement).
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isAvatarUploading = false, error = "Could not read selected image: ${e.message}")
                }
                return@launch
            }
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

    fun setStarredModelsDisplay(display: StarredModelsDisplay) {
        viewModelScope.launch { settingsDataStore.setStarredModelsDisplay(display) }
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

    fun setInlineArtifactMermaid(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setInlineArtifactMermaid(enabled) }
    }

    fun setInlineArtifactSvg(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setInlineArtifactSvg(enabled) }
    }

    fun setInlineArtifactHtml(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setInlineArtifactHtml(enabled) }
    }

    fun setInlineArtifactReact(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setInlineArtifactReact(enabled) }
    }

    fun setInlineArtifactMarkdown(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setInlineArtifactMarkdown(enabled) }
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
                    Logger.d(result.exception) { "Failed to load balance: ${result.message}" }
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
        _uiState.update { it.copy(selectedLanguage = languageCode, showLanguageDialog = false) }
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
        _uiState.update { it.copy(isLoggedOut = true) }
    }

    fun deleteAccount(token: String? = null, backupCode: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingAccount = true) }
            when (val result = userRepository.deleteUser(token = token, backupCode = backupCode)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isDeletingAccount = false, isAccountDeleted = true, showDeleteAccountOtpDialog = false) }
                }
                is Result.Error -> {
                    val needsOtp = token == null && backupCode == null &&
                        result.isHttpStatus(403)
                    _uiState.update {
                        it.copy(
                            isDeletingAccount = false,
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

    // Diagnostic logs (issue #96)
    fun exportLogs() = dataDelegate.exportLogs()
    fun clearLogs() = dataDelegate.clearLogs()
    fun consumeLogsExport() = dataDelegate.consumeLogsExport()

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

    private companion object {
        /** Upstream SystemRoles.ADMIN — the role name manageRoles checks. */
        const val ADMIN_ROLE = "ADMIN"
    }
}
