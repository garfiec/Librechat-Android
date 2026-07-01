package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
    private val activeAccountProvider: ActiveAccountProvider,
    appScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    /**
     * Selected app language as a BCP-47/ISO code (e.g. "es", "zh"), or [DEFAULT_LANGUAGE] (the
     * "follow the device locale" sentinel) when the user hasn't chosen one. Drives the runtime
     * locale applied at the app root.
     */
    val selectedLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_LANGUAGE] ?: DEFAULT_LANGUAGE
    }

    /**
     * Initial language seeded for the first compose frame, warmed up asynchronously off the Main
     * thread (Koin instantiates this singleton on Main at startup, so the read must not block).
     * Stays [DEFAULT_LANGUAGE] until [isReady] flips; the root composable gates on [isReady] so the
     * persisted locale is applied before the first frame draws (no flash of the wrong language).
     */
    @Volatile
    var initialSelectedLanguage: String = DEFAULT_LANGUAGE
        private set

    private val _isReady = MutableStateFlow(false)

    /** Flips true once the async warm-up has resolved [initialSelectedLanguage]. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        appScope.launch(ioDispatcher) {
            try {
                val prefs = dataStore.data.first()
                initialSelectedLanguage = prefs[KEY_SELECTED_LANGUAGE] ?: DEFAULT_LANGUAGE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the default; the live flow still drives the UI value.
            } finally {
                _isReady.value = true
            }
        }
    }

    val latexRenderer: Flow<LatexRenderer> = dataStore.data.map { prefs ->
        LatexRenderer.fromString(prefs[KEY_LATEX_RENDERER])
    }

    val chatFontSize: Flow<ChatFontSize> = dataStore.data.map { prefs ->
        ChatFontSize.fromString(prefs[KEY_CHAT_FONT_SIZE])
    }

    val starredModelsDisplay: Flow<StarredModelsDisplay> = dataStore.data.map { prefs ->
        StarredModelsDisplay.fromString(prefs[KEY_STARRED_MODELS_DISPLAY])
    }

    val chatHeaderContent: Flow<ChatHeaderContent> = dataStore.data.map { prefs ->
        ChatHeaderContent.fromString(prefs[KEY_CHAT_HEADER_CONTENT])
    }

    val chatHeaderAlignment: Flow<ChatHeaderAlignment> = dataStore.data.map { prefs ->
        ChatHeaderAlignment.fromString(prefs[KEY_CHAT_HEADER_ALIGNMENT])
    }

    val autoScrollEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_SCROLL_ENABLED] ?: true
    }

    val showThinkingBlocks: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_THINKING_BLOCKS] ?: true
    }

    /** Where the v0.8.7 context-usage gauge is surfaced. Default [ContextBarPlacement.OPTIONS_SHEET]. */
    val contextBarPlacement: Flow<ContextBarPlacement> = dataStore.data.map { prefs ->
        ContextBarPlacement.fromString(prefs[KEY_CONTEXT_BAR_PLACEMENT])
    }

    val autoReadEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_READ_ENABLED] ?: false
    }

    val showImageDescriptions: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_IMAGE_DESCRIPTIONS] ?: false
    }

    val selectedVoiceId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_VOICE_ID]
    }

    // Account-scoped: the last endpoint/model is per-account (server A's pick must not seed server B).
    // Emits nothing while the account is Warming (NOT null) so a one-shot/wait-on-unresolved consumer
    // — the model seeder — blocks for the real value instead of mistaking Warming for "none saved" and
    // seeding a lower tier; emits null only once the account resolves (logged out or genuinely absent).
    val lastUsedEndpoint: Flow<String?> = scopedString(::endpointKey)

    val lastUsedModel: Flow<String?> = scopedString(::modelKey)

    val dismissKeyboardOnSend: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DISMISS_KEYBOARD_ON_SEND] ?: true
    }

    val ttsSource: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_SOURCE] ?: "device"
    }

    val ttsSpeechRate: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_SPEECH_RATE] ?: 1.0f
    }

    val ttsPitch: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_PITCH] ?: 1.0f
    }

    val ttsVoiceName: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_VOICE_NAME] ?: ""
    }

    val tabletSidebarOpen: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TABLET_SIDEBAR_OPEN] ?: true
    }

    val tabletSidebarGestureEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TABLET_SIDEBAR_GESTURE_ENABLED] ?: true
    }

    /** Whether the drawer's Projects folder section is expanded. Default expanded. */
    val projectsSectionExpanded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PROJECTS_SECTION_EXPANDED] ?: true
    }

    val autoSendAfterStt: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_SEND_AFTER_STT] ?: false
    }

    val sttEngine: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_STT_ENGINE] ?: ""
    }

    val sttLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_STT_LANGUAGE] ?: ""
    }

    val ttsEngine: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_ENGINE] ?: ""
    }

    val ttsVoice: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_VOICE] ?: ""
    }

    val ttsCaching: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_CACHING] ?: true
    }

    val chatLayoutStyle: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_CHAT_LAYOUT_STYLE] ?: "thread"
    }

    val showAvatars: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_AVATARS] ?: true
    }

    val showBubbles: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_BUBBLES] ?: false
    }

    val inlineArtifactPrefs: Flow<InlineArtifactPrefs> = dataStore.data.map { prefs ->
        InlineArtifactPrefs(
            mermaid = prefs[KEY_INLINE_ARTIFACT_MERMAID] ?: false,
            svg = prefs[KEY_INLINE_ARTIFACT_SVG] ?: false,
            html = prefs[KEY_INLINE_ARTIFACT_HTML] ?: false,
            react = prefs[KEY_INLINE_ARTIFACT_REACT] ?: false,
            markdown = prefs[KEY_INLINE_ARTIFACT_MARKDOWN] ?: false,
        )
    }

    val artifactDisplayPrefs: Flow<ArtifactDisplayPrefs> = dataStore.data.map { prefs ->
        ArtifactDisplayPrefs(
            mode = ArtifactDisplayMode.fromString(prefs[KEY_ARTIFACT_DISPLAY_MODE]),
        )
    }

    val selectedMcpServers: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_MCP_SERVERS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    val enabledTools: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLED_TOOLS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    /**
     * The backend version for which the user dismissed the version mismatch warning.
     * When the backend updates to a new version, the dialog will appear again.
     */
    val dismissedVersionWarning: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DISMISSED_VERSION_WARNING]
    }

    suspend fun setSelectedLanguage(languageCode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SELECTED_LANGUAGE] = languageCode
        }
    }

    suspend fun setLatexRenderer(renderer: LatexRenderer) {
        dataStore.edit { prefs ->
            prefs[KEY_LATEX_RENDERER] = renderer.toStorageString()
        }
    }

    suspend fun setChatFontSize(size: ChatFontSize) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAT_FONT_SIZE] = size.toStorageString()
        }
    }

    suspend fun setStarredModelsDisplay(display: StarredModelsDisplay) {
        dataStore.edit { prefs ->
            prefs[KEY_STARRED_MODELS_DISPLAY] = display.toStorageString()
        }
    }

    suspend fun setChatHeaderContent(content: ChatHeaderContent) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAT_HEADER_CONTENT] = content.toStorageString()
        }
    }

    suspend fun setChatHeaderAlignment(alignment: ChatHeaderAlignment) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAT_HEADER_ALIGNMENT] = alignment.toStorageString()
        }
    }

    suspend fun setAutoScrollEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_SCROLL_ENABLED] = enabled
        }
    }

    suspend fun setShowThinkingBlocks(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_THINKING_BLOCKS] = show
        }
    }

    suspend fun setContextBarPlacement(placement: ContextBarPlacement) {
        dataStore.edit { prefs ->
            prefs[KEY_CONTEXT_BAR_PLACEMENT] = placement.toStorageString()
        }
    }

    suspend fun setAutoReadEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_READ_ENABLED] = enabled
        }
    }

    suspend fun setShowImageDescriptions(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_IMAGE_DESCRIPTIONS] = show
        }
    }

    suspend fun setDismissKeyboardOnSend(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_DISMISS_KEYBOARD_ON_SEND] = enabled
        }
    }

    suspend fun setSelectedVoiceId(voiceId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SELECTED_VOICE_ID] = voiceId
        }
    }

    suspend fun setLastUsedModel(endpoint: String, model: String) {
        val accountId = activeAccountProvider.currentAccountId()?.value ?: return
        dataStore.edit { prefs ->
            prefs[endpointKey(accountId)] = endpoint
            prefs[modelKey(accountId)] = model
            // Reset migration: drop the pre-keying bare entries (they'd mis-attribute A's model to B).
            prefs.remove(stringPreferencesKey(LAST_USED_ENDPOINT))
            prefs.remove(stringPreferencesKey(LAST_USED_MODEL))
        }
    }

    // Suspends (emits nothing) while Warming; once Resolved emits the account's scoped value, or null
    // when logged out. flatMapLatest re-subscribes on every identity transition so a switch re-emits.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun scopedString(keyFor: (String) -> Preferences.Key<String>): Flow<String?> =
        activeAccountProvider.state.flatMapLatest { state ->
            when (state) {
                AccountState.Warming -> emptyFlow()
                is AccountState.Resolved ->
                    state.id?.let { id -> dataStore.data.map { it[keyFor(id.value)] } } ?: flowOf(null)
            }
        }

    private fun endpointKey(accountId: String) = accountScopedKey(accountId, LAST_USED_ENDPOINT)

    private fun modelKey(accountId: String) = accountScopedKey(accountId, LAST_USED_MODEL)

    suspend fun setTtsSource(source: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_SOURCE] = source
        }
    }

    suspend fun setTtsSpeechRate(rate: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_SPEECH_RATE] = rate
        }
    }

    suspend fun setTtsPitch(pitch: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_PITCH] = pitch
        }
    }

    suspend fun setTtsVoiceName(voiceName: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_VOICE_NAME] = voiceName
        }
    }

    suspend fun setTabletSidebarOpen(open: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TABLET_SIDEBAR_OPEN] = open
        }
    }

    suspend fun setProjectsSectionExpanded(expanded: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_PROJECTS_SECTION_EXPANDED] = expanded
        }
    }

    suspend fun setTabletSidebarGestureEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TABLET_SIDEBAR_GESTURE_ENABLED] = enabled
        }
    }

    suspend fun setAutoSendAfterStt(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_SEND_AFTER_STT] = enabled
        }
    }

    suspend fun setSttEngine(engine: String) {
        dataStore.edit { prefs ->
            prefs[KEY_STT_ENGINE] = engine
        }
    }

    suspend fun setSttLanguage(language: String) {
        dataStore.edit { prefs ->
            prefs[KEY_STT_LANGUAGE] = language
        }
    }

    suspend fun setTtsEngine(engine: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_ENGINE] = engine
        }
    }

    suspend fun setTtsVoice(voice: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_VOICE] = voice
        }
    }

    suspend fun setTtsCaching(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TTS_CACHING] = enabled
        }
    }

    suspend fun setChatLayoutStyle(style: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CHAT_LAYOUT_STYLE] = style
        }
    }

    suspend fun setShowAvatars(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_AVATARS] = show
        }
    }

    suspend fun setShowBubbles(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_BUBBLES] = show
        }
    }

    suspend fun setInlineArtifactMermaid(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_MERMAID] = enabled }
    }

    suspend fun setInlineArtifactSvg(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_SVG] = enabled }
    }

    suspend fun setInlineArtifactHtml(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_HTML] = enabled }
    }

    suspend fun setInlineArtifactReact(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_REACT] = enabled }
    }

    suspend fun setInlineArtifactMarkdown(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INLINE_ARTIFACT_MARKDOWN] = enabled }
    }

    suspend fun setArtifactDisplayMode(mode: ArtifactDisplayMode) {
        dataStore.edit { prefs -> prefs[KEY_ARTIFACT_DISPLAY_MODE] = mode.toStorageString() }
    }

    /**
     * Saves the backend version for which the user chose "Don't warn again".
     * If the backend later updates to a different version, the warning will reappear.
     */
    suspend fun setDismissedVersionWarning(version: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DISMISSED_VERSION_WARNING] = version
        }
    }

    suspend fun setSelectedMcpServers(servers: Set<String>) {
        dataStore.edit { prefs ->
            if (servers.isEmpty()) {
                prefs.remove(KEY_SELECTED_MCP_SERVERS)
            } else {
                prefs[KEY_SELECTED_MCP_SERVERS] = servers.joinToString(",")
            }
        }
    }

    suspend fun setEnabledTools(tools: Set<String>) {
        dataStore.edit { prefs ->
            if (tools.isEmpty()) {
                prefs.remove(KEY_ENABLED_TOOLS)
            } else {
                prefs[KEY_ENABLED_TOOLS] = tools.joinToString(",")
            }
        }
    }

    companion object {
        /**
         * Sentinel meaning "follow the device locale" — the default until the user picks a
         * specific language. Maps to a null locale override at the app root, so formatting and
         * resource resolution keep following the system (current behavior preserved).
         */
        const val DEFAULT_LANGUAGE = "system"

        private val KEY_SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        private val KEY_LATEX_RENDERER = stringPreferencesKey("latex_renderer")
        private val KEY_CHAT_FONT_SIZE = stringPreferencesKey("chat_font_size")
        private val KEY_STARRED_MODELS_DISPLAY = stringPreferencesKey("starred_models_display")
        private val KEY_CHAT_HEADER_CONTENT = stringPreferencesKey("chat_header_content")
        private val KEY_CHAT_HEADER_ALIGNMENT = stringPreferencesKey("chat_header_alignment")
        private val KEY_AUTO_SCROLL_ENABLED = booleanPreferencesKey("auto_scroll_enabled")
        private val KEY_SHOW_THINKING_BLOCKS = booleanPreferencesKey("show_thinking_blocks")
        private val KEY_CONTEXT_BAR_PLACEMENT = stringPreferencesKey("context_bar_placement")
        private val KEY_AUTO_READ_ENABLED = booleanPreferencesKey("auto_read_enabled")
        private val KEY_SHOW_IMAGE_DESCRIPTIONS = booleanPreferencesKey("show_image_descriptions")
        private val KEY_SELECTED_VOICE_ID = stringPreferencesKey("selected_voice_id")
        private const val LAST_USED_ENDPOINT = "last_used_endpoint"
        private const val LAST_USED_MODEL = "last_used_model"
        private val KEY_DISMISS_KEYBOARD_ON_SEND = booleanPreferencesKey("dismiss_keyboard_on_send")
        private val KEY_TTS_SOURCE = stringPreferencesKey("tts_source")
        private val KEY_TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        private val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val KEY_TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        private val KEY_TABLET_SIDEBAR_OPEN = booleanPreferencesKey("tablet_sidebar_open")
        private val KEY_TABLET_SIDEBAR_GESTURE_ENABLED = booleanPreferencesKey("tablet_sidebar_gesture_enabled")
        private val KEY_PROJECTS_SECTION_EXPANDED = booleanPreferencesKey("projects_section_expanded")
        private val KEY_AUTO_SEND_AFTER_STT = booleanPreferencesKey("auto_send_after_stt")
        private val KEY_STT_ENGINE = stringPreferencesKey("stt_engine")
        private val KEY_STT_LANGUAGE = stringPreferencesKey("stt_language")
        private val KEY_TTS_ENGINE = stringPreferencesKey("tts_engine")
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_TTS_CACHING = booleanPreferencesKey("tts_caching")
        private val KEY_CHAT_LAYOUT_STYLE = stringPreferencesKey("chat_layout_style")
        private val KEY_SHOW_AVATARS = booleanPreferencesKey("show_avatars")
        private val KEY_SHOW_BUBBLES = booleanPreferencesKey("show_bubbles")
        private val KEY_INLINE_ARTIFACT_MERMAID = booleanPreferencesKey("inline_artifact_mermaid")
        private val KEY_INLINE_ARTIFACT_SVG = booleanPreferencesKey("inline_artifact_svg")
        private val KEY_INLINE_ARTIFACT_HTML = booleanPreferencesKey("inline_artifact_html")
        private val KEY_INLINE_ARTIFACT_REACT = booleanPreferencesKey("inline_artifact_react")
        private val KEY_INLINE_ARTIFACT_MARKDOWN = booleanPreferencesKey("inline_artifact_markdown")
        private val KEY_ARTIFACT_DISPLAY_MODE = stringPreferencesKey("artifact_display_mode")
        private val KEY_DISMISSED_VERSION_WARNING = stringPreferencesKey("dismissed_version_warning")
        private val KEY_SELECTED_MCP_SERVERS = stringPreferencesKey("selected_mcp_servers")
        private val KEY_ENABLED_TOOLS = stringPreferencesKey("enabled_tools")
    }
}
