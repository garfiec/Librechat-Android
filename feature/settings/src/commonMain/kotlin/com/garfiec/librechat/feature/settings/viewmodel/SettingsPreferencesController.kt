package com.garfiec.librechat.feature.settings.viewmodel

import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.data.datastore.ArtifactDisplayMode
import com.garfiec.librechat.core.data.datastore.ArtifactDisplayPrefs
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.data.datastore.ThemeDataStore
import com.garfiec.librechat.core.data.datastore.ThemeMode
import com.garfiec.librechat.core.ui.theme.supportsDynamicColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Intermediate holder for the combined DataStore preferences. */
private data class DataStorePreferences(
    val themeMode: ThemeMode,
    val serverUrl: String,
    val chatFontSize: ChatFontSize,
    val autoScrollEnabled: Boolean,
    val showThinkingBlocks: Boolean,
    val accentColor: Int = ThemeDataStore.DEFAULT_ACCENT_COLOR,
    val useDynamicColor: Boolean = false,
)

/** Extra DataStore preferences (separate combine since Kotlin combine maxes at 5). */
private data class ExtraPreferences(
    val autoRead: Boolean,
    val selectedVoiceId: String?,
    val showImageDescriptions: Boolean,
    val dismissKeyboardOnSend: Boolean,
    val ttsSource: String,
)

/** Device TTS preferences (speech rate, pitch, voice name, engine, voice selection). */
private data class DeviceTtsPreferences(
    val speechRate: Float,
    val pitch: Float,
    val voiceName: String,
    val ttsEngine: String,
    val ttsVoice: String,
)

/** Additional preferences combined separately to stay within the 5-arg combine limit. */
private data class AdditionalPreferences(
    val tabletSidebarGestureEnabled: Boolean,
    val autoSendAfterStt: Boolean,
    val sttEngine: String,
    val sttLanguage: String,
    val ttsCaching: Boolean,
    val sttOnDevice: Boolean = true,
    val sttEndOfSpeech: Boolean = false,
    val chatLayoutStyle: String = ChatLayoutConstants.THREAD,
    val showAvatars: Boolean = true,
    val showBubbles: Boolean = false,
    val latexRenderer: LatexRenderer = LatexRenderer.KATEX,
    val inlineArtifactPrefs: InlineArtifactPrefs = InlineArtifactPrefs(),
    val artifactDisplayPrefs: ArtifactDisplayPrefs = ArtifactDisplayPrefs(),
    val starredModelsDisplay: StarredModelsDisplay = StarredModelsDisplay.OFF,
    val selectedLanguage: String = SettingsDataStore.DEFAULT_LANGUAGE,
    val chatHeaderContent: ChatHeaderContent = ChatHeaderContent.TITLE,
    val chatHeaderAlignment: ChatHeaderAlignment = ChatHeaderAlignment.LEFT,
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
)

/**
 * Owns the DataStore side of settings: the read flows (the multi-stage `combine`
 * machinery, split across holders because Kotlin's `combine` maxes at 5 sources)
 * merged with the ViewModel's imperative [baseState] into a single [uiState], plus
 * the matching write setters. The ViewModel keeps only imperative state and
 * forwards preference reads/writes here.
 */
class SettingsPreferencesController(
    private val themeDataStore: ThemeDataStore,
    serverDataStore: ServerDataStore,
    private val settingsDataStore: SettingsDataStore,
    baseState: StateFlow<SettingsUiState>,
    private val scope: CoroutineScope,
) {
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
        )
    }.combine(themeDataStore.accentColor) { prefs, accent ->
        prefs.copy(accentColor = accent)
    }.combine(themeDataStore.useDynamicColor) { prefs, dynamic ->
        prefs.copy(useDynamicColor = dynamic)
    }.stateIn(scope, SharingStarted.Eagerly, DataStorePreferences(
        themeMode = ThemeMode.SYSTEM,
        serverUrl = "",
        chatFontSize = ChatFontSize.MEDIUM,
        autoScrollEnabled = true,
        showThinkingBlocks = true,
    ))

    private val extraPreferences: StateFlow<ExtraPreferences> = combine(
        settingsDataStore.autoReadEnabled,
        settingsDataStore.selectedVoiceId,
        settingsDataStore.showImageDescriptions,
        settingsDataStore.dismissKeyboardOnSend,
        settingsDataStore.ttsSource,
    ) { autoRead, voiceId, showImgDesc, dismissKeyboard, ttsSource ->
        ExtraPreferences(autoRead, voiceId, showImgDesc, dismissKeyboard, ttsSource)
    }.stateIn(scope, SharingStarted.Eagerly, ExtraPreferences(false, null, false, false, "device"))

    private val deviceTtsPreferences: StateFlow<DeviceTtsPreferences> = combine(
        settingsDataStore.ttsSpeechRate,
        settingsDataStore.ttsPitch,
        settingsDataStore.ttsVoiceName,
        settingsDataStore.ttsEngine,
        settingsDataStore.ttsVoice,
    ) { rate, pitch, voiceName, ttsEngine, ttsVoice ->
        DeviceTtsPreferences(rate, pitch, voiceName, ttsEngine, ttsVoice)
    }.stateIn(scope, SharingStarted.Eagerly, DeviceTtsPreferences(1.0f, 1.0f, "", "", ""))

    /** TTS caching preference. Kept as a separate flow to stay within the 5-arg combine limit. */
    private val ttsCachingPreference: StateFlow<Boolean> = settingsDataStore.ttsCaching
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Tablet-specific preferences. */
    private val tabletSidebarGestureEnabled: StateFlow<Boolean> = settingsDataStore.tabletSidebarGestureEnabled
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Chat layout preferences. */
    private val chatLayoutStylePref: StateFlow<String> = settingsDataStore.chatLayoutStyle
        .stateIn(scope, SharingStarted.Eagerly, ChatLayoutConstants.THREAD)

    private val showAvatarsPref: StateFlow<Boolean> = settingsDataStore.showAvatars
        .stateIn(scope, SharingStarted.Eagerly, true)

    private val showBubblesPref: StateFlow<Boolean> = settingsDataStore.showBubbles
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val latexRendererPref: StateFlow<LatexRenderer> = settingsDataStore.latexRenderer
        .stateIn(scope, SharingStarted.Eagerly, LatexRenderer.KATEX)

    private val inlineArtifactPrefsFlow: StateFlow<InlineArtifactPrefs> = settingsDataStore.inlineArtifactPrefs
        .stateIn(scope, SharingStarted.Eagerly, InlineArtifactPrefs())

    private val artifactDisplayPrefsFlow: StateFlow<ArtifactDisplayPrefs> = settingsDataStore.artifactDisplayPrefs
        .stateIn(scope, SharingStarted.Eagerly, ArtifactDisplayPrefs())

    private val starredModelsDisplayPref: StateFlow<StarredModelsDisplay> = settingsDataStore.starredModelsDisplay
        .stateIn(scope, SharingStarted.Eagerly, StarredModelsDisplay.OFF)

    private val selectedLanguagePref: StateFlow<String> = settingsDataStore.selectedLanguage
        .stateIn(scope, SharingStarted.Eagerly, SettingsDataStore.DEFAULT_LANGUAGE)

    private val chatHeaderContentPref: StateFlow<ChatHeaderContent> = settingsDataStore.chatHeaderContent
        .stateIn(scope, SharingStarted.Eagerly, ChatHeaderContent.TITLE)

    private val chatHeaderAlignmentPref: StateFlow<ChatHeaderAlignment> = settingsDataStore.chatHeaderAlignment
        .stateIn(scope, SharingStarted.Eagerly, ChatHeaderAlignment.LEFT)

    private val contextBarPlacementPref: StateFlow<ContextBarPlacement> = settingsDataStore.contextBarPlacement
        .stateIn(scope, SharingStarted.Eagerly, ContextBarPlacement.OPTIONS_SHEET)

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
    }.combine(artifactDisplayPrefsFlow) { additional, artifactDisplay ->
        additional.copy(artifactDisplayPrefs = artifactDisplay)
    }.combine(starredModelsDisplayPref) { additional, starredDisplay ->
        additional.copy(starredModelsDisplay = starredDisplay)
    }.combine(selectedLanguagePref) { additional, selectedLanguage ->
        additional.copy(selectedLanguage = selectedLanguage)
    }.combine(chatHeaderContentPref) { additional, headerContent ->
        additional.copy(chatHeaderContent = headerContent)
    }.combine(chatHeaderAlignmentPref) { additional, headerAlignment ->
        additional.copy(chatHeaderAlignment = headerAlignment)
    }.combine(contextBarPlacementPref) { additional, contextBarPlacement ->
        additional.copy(contextBarPlacement = contextBarPlacement)
    }.combine(settingsDataStore.sttOnDevice) { additional, sttOnDevice ->
        additional.copy(sttOnDevice = sttOnDevice)
    }.combine(settingsDataStore.sttEndOfSpeech) { additional, sttEndOfSpeech ->
        additional.copy(sttEndOfSpeech = sttEndOfSpeech)
    }.stateIn(scope, SharingStarted.Eagerly, AdditionalPreferences(true, false, "", "", true))

    /** The single public UI state that merges DataStore preferences with imperative state. */
    val uiState: StateFlow<SettingsUiState> = combine(
        baseState,
        dataStorePreferences,
        extraPreferences,
        deviceTtsPreferences,
        additionalPreferences,
    ) { state, prefs, extra, deviceTts, additional ->
        val selectedVoice = extra.selectedVoiceId?.let { id -> state.availableVoices.find { it.id == id } }
        state.copy(
            themeMode = prefs.themeMode,
            accentColor = prefs.accentColor,
            useDynamicColor = prefs.useDynamicColor,
            dynamicColorSupported = supportsDynamicColor(),
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
            sttOnDevice = additional.sttOnDevice,
            sttEndOfSpeech = additional.sttEndOfSpeech,
            chatLayoutStyle = additional.chatLayoutStyle,
            showAvatars = additional.showAvatars,
            showBubbles = additional.showBubbles,
            latexRenderer = additional.latexRenderer,
            inlineArtifactPrefs = additional.inlineArtifactPrefs,
            artifactDisplayPrefs = additional.artifactDisplayPrefs,
            starredModelsDisplay = additional.starredModelsDisplay,
            selectedLanguage = additional.selectedLanguage,
            chatHeaderContent = additional.chatHeaderContent,
            chatHeaderAlignment = additional.chatHeaderAlignment,
            contextBarPlacement = additional.contextBarPlacement,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SettingsUiState())

    // ── Write setters (fire-and-forget; return Unit, not the launched Job) ──

    fun setThemeMode(mode: ThemeMode) {
        scope.launch { themeDataStore.setThemeMode(mode) }
    }

    fun setAccentColor(argb: Int) {
        scope.launch { themeDataStore.setAccentColor(argb) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        scope.launch { themeDataStore.setUseDynamicColor(enabled) }
    }

    fun setChatFontSize(size: ChatFontSize) {
        scope.launch { settingsDataStore.setChatFontSize(size) }
    }

    fun setStarredModelsDisplay(display: StarredModelsDisplay) {
        scope.launch { settingsDataStore.setStarredModelsDisplay(display) }
    }

    fun setChatHeaderContent(content: ChatHeaderContent) {
        scope.launch { settingsDataStore.setChatHeaderContent(content) }
    }

    fun setChatHeaderAlignment(alignment: ChatHeaderAlignment) {
        scope.launch { settingsDataStore.setChatHeaderAlignment(alignment) }
    }

    fun setAutoScrollEnabled(enabled: Boolean) {
        scope.launch { settingsDataStore.setAutoScrollEnabled(enabled) }
    }

    fun setShowThinkingBlocks(show: Boolean) {
        scope.launch { settingsDataStore.setShowThinkingBlocks(show) }
    }

    fun setContextBarPlacement(placement: ContextBarPlacement) {
        scope.launch { settingsDataStore.setContextBarPlacement(placement) }
    }

    fun setShowImageDescriptions(show: Boolean) {
        scope.launch { settingsDataStore.setShowImageDescriptions(show) }
    }

    fun setDismissKeyboardOnSend(enabled: Boolean) {
        scope.launch { settingsDataStore.setDismissKeyboardOnSend(enabled) }
    }

    fun setChatLayoutStyle(style: String) {
        scope.launch { settingsDataStore.setChatLayoutStyle(style) }
    }

    fun setShowAvatars(show: Boolean) {
        scope.launch { settingsDataStore.setShowAvatars(show) }
    }

    fun setShowBubbles(show: Boolean) {
        scope.launch { settingsDataStore.setShowBubbles(show) }
    }

    fun setLatexRenderer(renderer: LatexRenderer) {
        scope.launch { settingsDataStore.setLatexRenderer(renderer) }
    }

    fun setInlineArtifactMermaid(enabled: Boolean) {
        scope.launch { settingsDataStore.setInlineArtifactMermaid(enabled) }
    }

    fun setInlineArtifactSvg(enabled: Boolean) {
        scope.launch { settingsDataStore.setInlineArtifactSvg(enabled) }
    }

    fun setInlineArtifactHtml(enabled: Boolean) {
        scope.launch { settingsDataStore.setInlineArtifactHtml(enabled) }
    }

    fun setInlineArtifactReact(enabled: Boolean) {
        scope.launch { settingsDataStore.setInlineArtifactReact(enabled) }
    }

    fun setInlineArtifactMarkdown(enabled: Boolean) {
        scope.launch { settingsDataStore.setInlineArtifactMarkdown(enabled) }
    }

    fun setArtifactDisplayMode(mode: ArtifactDisplayMode) {
        scope.launch { settingsDataStore.setArtifactDisplayMode(mode) }
    }

    fun setTabletSidebarGestureEnabled(enabled: Boolean) {
        scope.launch { settingsDataStore.setTabletSidebarGestureEnabled(enabled) }
    }

    fun setSelectedLanguage(languageCode: String) {
        scope.launch { settingsDataStore.setSelectedLanguage(languageCode) }
    }
}
