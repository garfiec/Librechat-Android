package com.garfiec.librechat.feature.settings.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.data.datastore.ArtifactDisplayPrefs
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.data.datastore.ThemeDataStore
import com.garfiec.librechat.core.data.datastore.ThemeMode
import com.garfiec.librechat.core.model.Memory
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.config.BuildInfo
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.mcp.McpServerStatus
import com.garfiec.librechat.core.model.speech.TtsVoice
import com.garfiec.librechat.feature.settings.model.SharedLinkDisplayData
import com.garfiec.librechat.feature.settings.model.UserDisplayData
import com.garfiec.librechat.feature.settings.screen.DeviceVoiceInfo

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
    val accentColor: Int = ThemeDataStore.DEFAULT_ACCENT_COLOR,
    val useDynamicColor: Boolean = false,
    val dynamicColorSupported: Boolean = false,
    val showAccentColorDialog: Boolean = false,
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
    /**
     * Resolved LibreChat backend version (e.g. `0.8.7`), from
     * `ConfigRepository.detectedBackendVersion`. null when the version can't be determined
     * (LibreChat exposes no version endpoint; we infer it from the build commit).
     * The About section shows an explicit "Unknown" in that case rather than hiding the row.
     */
    val serverVersion: String? = null,
    /** True only when the authenticated user's system role is ADMIN. Gates the
     *  admin role-skills row fail-CLOSED (defaults false until the profile loads). */
    val isAdmin: Boolean = false,
    // Chat preferences
    val chatFontSize: ChatFontSize = ChatFontSize.MEDIUM,
    val autoScrollEnabled: Boolean = true,
    val showThinkingBlocks: Boolean = true,
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
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
    /** Mobile-only: prefer the platform on-device recognizer for the Browser engine. Default ON. */
    val sttOnDevice: Boolean = true,
    /** Mobile-only: stop dictation at end-of-speech (hands-free) vs. run continuously. Default OFF. */
    val sttEndOfSpeech: Boolean = false,
    /** Whether the server has external STT (Whisper) configured — gates the External engine option. */
    val serverSttEnabled: Boolean = false,
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
    val selectedLanguage: String = SettingsDataStore.DEFAULT_LANGUAGE,
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
    // Mobile-only: what the chat floating top bar shows + how its bubble is aligned
    val chatHeaderContent: ChatHeaderContent = ChatHeaderContent.TITLE,
    val chatHeaderAlignment: ChatHeaderAlignment = ChatHeaderAlignment.LEFT,
    // Inline artifact rendering (per-type toggles)
    val inlineArtifactPrefs: InlineArtifactPrefs = InlineArtifactPrefs(),
    // Artifact viewer presentation (bottom sheet vs full screen + selector visibility)
    val artifactDisplayPrefs: ArtifactDisplayPrefs = ArtifactDisplayPrefs(),
    // Role-permission gates. `serverMemoriesEnabled` is the SERVER-level MEMORIES.USE
    // gate and is orthogonal to [memoriesEnabled], which is the user's own opt-out
    // stored on their profile (`user.personalization.memories`).
    val mcpServersEnabled: Boolean = true,
    val mcpServersCreateEnabled: Boolean = true,
    val serverMemoriesEnabled: Boolean = true,
    val remoteAgentsEnabled: Boolean = true,
    val remoteAgentsCreateEnabled: Boolean = true,
)

internal fun User.toDisplayData() = UserDisplayData(
    name = name ?: "",
    email = email,
    username = username ?: "",
    avatar = avatar,
)
