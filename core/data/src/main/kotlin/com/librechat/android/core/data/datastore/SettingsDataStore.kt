package com.librechat.android.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class LatexRenderer {
    NATIVE, KATEX;

    companion object {
        fun fromString(value: String?): LatexRenderer = when (value) {
            "native" -> NATIVE
            else -> KATEX
        }
    }

    fun toStorageString(): String = when (this) {
        NATIVE -> "native"
        KATEX -> "katex"
    }
}

enum class ChatFontSize {
    SMALL, MEDIUM, LARGE;

    companion object {
        fun fromString(value: String?): ChatFontSize = when (value) {
            "small" -> SMALL
            "large" -> LARGE
            else -> MEDIUM
        }
    }

    fun toStorageString(): String = when (this) {
        SMALL -> "small"
        MEDIUM -> "medium"
        LARGE -> "large"
    }
}

class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) {
    val latexRenderer: Flow<LatexRenderer> = dataStore.data.map { prefs ->
        LatexRenderer.fromString(prefs[KEY_LATEX_RENDERER])
    }

    val chatFontSize: Flow<ChatFontSize> = dataStore.data.map { prefs ->
        ChatFontSize.fromString(prefs[KEY_CHAT_FONT_SIZE])
    }

    val autoScrollEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_SCROLL_ENABLED] ?: true
    }

    val showThinkingBlocks: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_THINKING_BLOCKS] ?: true
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

    val lastUsedEndpoint: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_USED_ENDPOINT]
    }

    val lastUsedModel: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_USED_MODEL]
    }

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

    val bookmarkedConversationIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_BOOKMARKED_CONVERSATIONS] ?: emptySet()
    }

    val tabletSidebarOpen: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TABLET_SIDEBAR_OPEN] ?: true
    }

    val tabletSidebarGestureEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TABLET_SIDEBAR_GESTURE_ENABLED] ?: true
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

    /** BCP-47 / ISO-639-1 UI language code (e.g. "fr", "en") for in-app language preference. */
    val uiLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_UI_LANGUAGE] ?: "en"
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
        dataStore.edit { prefs ->
            prefs[KEY_LAST_USED_ENDPOINT] = endpoint
            prefs[KEY_LAST_USED_MODEL] = model
        }
    }

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

    suspend fun setUiLanguage(languageCode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_UI_LANGUAGE] = languageCode
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

    suspend fun toggleBookmark(conversationId: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_BOOKMARKED_CONVERSATIONS] ?: emptySet()
            prefs[KEY_BOOKMARKED_CONVERSATIONS] = if (conversationId in current) {
                current - conversationId
            } else {
                current + conversationId
            }
        }
    }

    companion object {
        private val KEY_LATEX_RENDERER = stringPreferencesKey("latex_renderer")
        private val KEY_CHAT_FONT_SIZE = stringPreferencesKey("chat_font_size")
        private val KEY_AUTO_SCROLL_ENABLED = booleanPreferencesKey("auto_scroll_enabled")
        private val KEY_SHOW_THINKING_BLOCKS = booleanPreferencesKey("show_thinking_blocks")
        private val KEY_AUTO_READ_ENABLED = booleanPreferencesKey("auto_read_enabled")
        private val KEY_SHOW_IMAGE_DESCRIPTIONS = booleanPreferencesKey("show_image_descriptions")
        private val KEY_SELECTED_VOICE_ID = stringPreferencesKey("selected_voice_id")
        private val KEY_LAST_USED_ENDPOINT = stringPreferencesKey("last_used_endpoint")
        private val KEY_LAST_USED_MODEL = stringPreferencesKey("last_used_model")
        private val KEY_DISMISS_KEYBOARD_ON_SEND = booleanPreferencesKey("dismiss_keyboard_on_send")
        private val KEY_TTS_SOURCE = stringPreferencesKey("tts_source")
        private val KEY_TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        private val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val KEY_TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        private val KEY_BOOKMARKED_CONVERSATIONS = stringSetPreferencesKey("bookmarked_conversations")
        private val KEY_TABLET_SIDEBAR_OPEN = booleanPreferencesKey("tablet_sidebar_open")
        private val KEY_TABLET_SIDEBAR_GESTURE_ENABLED = booleanPreferencesKey("tablet_sidebar_gesture_enabled")
        private val KEY_AUTO_SEND_AFTER_STT = booleanPreferencesKey("auto_send_after_stt")
        private val KEY_STT_ENGINE = stringPreferencesKey("stt_engine")
        private val KEY_STT_LANGUAGE = stringPreferencesKey("stt_language")
        private val KEY_UI_LANGUAGE = stringPreferencesKey("ui_language")
        private val KEY_TTS_ENGINE = stringPreferencesKey("tts_engine")
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_TTS_CACHING = booleanPreferencesKey("tts_caching")
        private val KEY_CHAT_LAYOUT_STYLE = stringPreferencesKey("chat_layout_style")
        private val KEY_SHOW_AVATARS = booleanPreferencesKey("show_avatars")
        private val KEY_SHOW_BUBBLES = booleanPreferencesKey("show_bubbles")
        private val KEY_DISMISSED_VERSION_WARNING = stringPreferencesKey("dismissed_version_warning")
        private val KEY_SELECTED_MCP_SERVERS = stringPreferencesKey("selected_mcp_servers")
        private val KEY_ENABLED_TOOLS = stringPreferencesKey("enabled_tools")
    }
}
