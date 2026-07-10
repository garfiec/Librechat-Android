package com.garfiec.librechat.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.data.datastore.ArtifactDisplayMode
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
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
import com.garfiec.librechat.core.model.mcp.McpApiKeyConfig
import com.garfiec.librechat.core.model.mcp.McpOAuthConfig
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerType
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.model.speech.TtsVoice
import com.garfiec.librechat.feature.settings.util.ContentReader
import com.garfiec.librechat.feature.settings.util.PlatformCacheCleaner
import com.garfiec.librechat.feature.settings.viewmodel.delegate.AccountDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.DataManagementDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.McpServerDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.MemoryManagementDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.SpeechSettingsFactory
import com.garfiec.librechat.feature.settings.viewmodel.delegate.TwoFactorSecurityDelegate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("LongParameterList")
class SettingsViewModel(
    contentReader: ContentReader,
    cacheCleaner: PlatformCacheCleaner,
    userRepository: UserRepository,
    authRepository: AuthRepository,
    conversationRepository: ConversationRepository,
    themeDataStore: ThemeDataStore,
    serverDataStore: ServerDataStore,
    settingsDataStore: SettingsDataStore,
    mcpRepository: McpRepository,
    memoryRepository: MemoryRepository,
    speechSettingsFactory: SpeechSettingsFactory,
    balanceRepository: BalanceRepository,
    shareRepository: ShareRepository,
    keyRepository: KeyRepository,
    private val roleRepository: RoleRepository,
    private val permissionGate: PermissionGate,
    private val configRepository: ConfigRepository,
    diagnosticLogRepository: DiagnosticLogRepository,
    appInfo: AppInfo,
    ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** Raw state for everything not driven by DataStore flows. */
    private val _uiState = MutableStateFlow(
        SettingsUiState(appVersion = appInfo.versionName, gitSha = appInfo.gitSha),
    )

    private val stateHandle = SettingsStateHandle(_uiState, viewModelScope)

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
    private val accountDelegate =
        AccountDelegate(stateHandle, userRepository, balanceRepository, contentReader, ioDispatcher)

    /** Owns the DataStore read flows + write setters; merges them with [_uiState]. */
    private val prefsController = SettingsPreferencesController(
        themeDataStore,
        serverDataStore,
        settingsDataStore,
        _uiState,
        viewModelScope,
    )

    /** The single public UI state that merges DataStore preferences with imperative state. */
    val uiState: StateFlow<SettingsUiState> = prefsController.uiState

    init {
        accountDelegate.loadUser()
        speechDelegate.loadVoices()
        speechDelegate.loadSpeechConfig()
        accountDelegate.loadBalance()
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
            combine(
                configRepository.startupConfig,
                configRepository.detectedBackendVersion,
            ) { config, version -> config to version }.collect { (config, version) ->
                _uiState.update {
                    it.copy(
                        allowAccountDeletion = config?.allowAccountDeletion ?: true,
                        buildInfo = config?.buildInfo,
                        serverVersion = version,
                    )
                }
            }
        }
    }

    // ── Account & user profile ─────────────────────────────────────

    fun showAvatarDialog() = accountDelegate.showAvatarDialog()
    fun dismissAvatarDialog() = accountDelegate.dismissAvatarDialog()
    fun uploadAvatar(uri: Any) = accountDelegate.uploadAvatar(uri)

    // ── Theme & appearance ─────────────────────────────────────────

    fun setThemeMode(mode: ThemeMode) {
        prefsController.setThemeMode(mode)
    }

    fun setAccentColor(argb: Int) {
        prefsController.setAccentColor(argb)
    }

    fun setUseDynamicColor(enabled: Boolean) {
        prefsController.setUseDynamicColor(enabled)
    }

    fun showAccentColorDialog() {
        _uiState.update { it.copy(showAccentColorDialog = true) }
    }

    fun dismissAccentColorDialog() {
        _uiState.update { it.copy(showAccentColorDialog = false) }
    }

    // ── Chat preferences ───────────────────────────────────────────

    fun setChatFontSize(size: ChatFontSize) {
        prefsController.setChatFontSize(size)
    }

    fun setStarredModelsDisplay(display: StarredModelsDisplay) {
        prefsController.setStarredModelsDisplay(display)
    }

    fun setChatHeaderContent(content: ChatHeaderContent) {
        prefsController.setChatHeaderContent(content)
    }

    fun setChatHeaderAlignment(alignment: ChatHeaderAlignment) {
        prefsController.setChatHeaderAlignment(alignment)
    }

    fun setAutoScrollEnabled(enabled: Boolean) {
        prefsController.setAutoScrollEnabled(enabled)
    }

    fun setShowThinkingBlocks(show: Boolean) {
        prefsController.setShowThinkingBlocks(show)
    }

    fun setContextBarPlacement(placement: ContextBarPlacement) {
        prefsController.setContextBarPlacement(placement)
    }

    fun setShowImageDescriptions(show: Boolean) {
        prefsController.setShowImageDescriptions(show)
    }

    fun setDismissKeyboardOnSend(enabled: Boolean) {
        prefsController.setDismissKeyboardOnSend(enabled)
    }

    fun setChatLayoutStyle(style: String) {
        prefsController.setChatLayoutStyle(style)
    }

    fun setShowAvatars(show: Boolean) {
        prefsController.setShowAvatars(show)
    }

    fun setShowBubbles(show: Boolean) {
        prefsController.setShowBubbles(show)
    }

    fun setLatexRenderer(renderer: LatexRenderer) {
        prefsController.setLatexRenderer(renderer)
    }

    fun setInlineArtifactMermaid(enabled: Boolean) {
        prefsController.setInlineArtifactMermaid(enabled)
    }

    fun setInlineArtifactSvg(enabled: Boolean) {
        prefsController.setInlineArtifactSvg(enabled)
    }

    fun setInlineArtifactHtml(enabled: Boolean) {
        prefsController.setInlineArtifactHtml(enabled)
    }

    fun setInlineArtifactReact(enabled: Boolean) {
        prefsController.setInlineArtifactReact(enabled)
    }

    fun setInlineArtifactMarkdown(enabled: Boolean) {
        prefsController.setInlineArtifactMarkdown(enabled)
    }

    fun setArtifactDisplayMode(mode: ArtifactDisplayMode) {
        prefsController.setArtifactDisplayMode(mode)
    }

    // ── Tablet preferences ─────────────────────────────────────────

    fun setTabletSidebarGestureEnabled(enabled: Boolean) {
        prefsController.setTabletSidebarGestureEnabled(enabled)
    }

    // ── Language ───────────────────────────────────────────────────

    fun showLanguageDialog() {
        _uiState.update { it.copy(showLanguageDialog = true) }
    }

    fun dismissLanguageDialog() {
        _uiState.update { it.copy(showLanguageDialog = false) }
    }

    fun setLanguage(languageCode: String) {
        prefsController.setSelectedLanguage(languageCode)
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

    fun logout() = accountDelegate.logout()
    fun deleteAccount(token: String? = null, backupCode: String? = null) =
        accountDelegate.deleteAccount(token = token, backupCode = backupCode)
    fun dismissDeleteAccountOtpDialog() = accountDelegate.dismissDeleteAccountOtpDialog()
    fun retry() = accountDelegate.retry()
    fun dismissError() = accountDelegate.dismissError()

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
    fun saveSttSettings(engine: String, language: String, onDevice: Boolean, endOfSpeech: Boolean) =
        speechDelegate.saveSttSettings(engine, language, onDevice, endOfSpeech)
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
