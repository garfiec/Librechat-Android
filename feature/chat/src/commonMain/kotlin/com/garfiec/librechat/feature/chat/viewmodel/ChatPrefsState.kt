package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay

/**
 * DataStore-backed chat preferences merged into [ChatUiState] by the `uiState` combine in
 * [ChatViewModel] (the sole writer). These are layered over the inner `_uiState` on every
 * emission, so a navigation reset need not carry them.
 */
@Immutable
data class ChatPrefsState(
    val serverUrl: String = "",
    val chatFontSize: ChatFontSize = ChatFontSize.MEDIUM,
    /**
     * Mobile-only preference for how pinned models/agents are surfaced in [ModelSelectorSheet]:
     * off (float within group), grouped (collapsible top section), or top (flat top list).
     */
    val starredModelsDisplay: StarredModelsDisplay = StarredModelsDisplay.OFF,
    /**
     * Mobile-only preferences for the chat floating top bar: what its bubble shows
     * ([chatHeaderContent]) and how the bubble is positioned ([chatHeaderAlignment]).
     */
    val chatHeaderContent: ChatHeaderContent = ChatHeaderContent.TITLE,
    val chatHeaderAlignment: ChatHeaderAlignment = ChatHeaderAlignment.LEFT,
    /** User preference (Settings → Chat) for where the context gauge is surfaced. */
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
    /** Whether the options-sheet context gauge's inline breakdown is expanded. */
    val contextGaugeExpanded: Boolean = false,
)

/**
 * Consolidated chat-related user preferences from [SettingsDataStore].
 * Exposed as a single [StateFlow] to reduce the number of individual subscriptions
 * in the UI layer.
 */
@Immutable
data class ChatPreferences(
    val showImageDescriptions: Boolean = false,
    val dismissKeyboardOnSend: Boolean = false,
    val chatLayoutStyle: String = ChatLayoutConstants.THREAD,
    val showAvatars: Boolean = true,
    val showBubbles: Boolean = false,
    val latexRenderer: LatexRenderer = LatexRenderer.KATEX,
    val autoSendAfterStt: Boolean = false,
    val sttEngine: String = "",
    val sttLanguage: String = "",
    val inlineArtifactPrefs: InlineArtifactPrefs = InlineArtifactPrefs(),
)

/**
 * Chat-screen display preferences (floating top bar + options-sheet context gauge), bundled
 * into a single flow so [ChatViewModel]'s `uiState` combine stays within Kotlin's 5-arg
 * typed limit.
 */
@Immutable
data class ChatDisplayPrefs(
    val content: ChatHeaderContent = ChatHeaderContent.TITLE,
    val alignment: ChatHeaderAlignment = ChatHeaderAlignment.LEFT,
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
    val contextGaugeExpanded: Boolean = false,
)
