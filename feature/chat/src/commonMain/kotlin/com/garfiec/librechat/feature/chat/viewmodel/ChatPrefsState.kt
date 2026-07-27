package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.DuringRunAction
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
    /**
     * What the send control does while a reply is generating (v0.8.8 steering): inject into the
     * running turn, or queue for after it. Read through [ChatUiState.effectiveDuringRunAction],
     * which degrades to queueing when steering is unavailable.
     *
     * **Unlike every other field here this one drives BEHAVIOUR, not just rendering**, so
     * `ChatViewModel` mirrors it into the backing `_uiState` with its own collector rather than
     * relying on the `uiState` combine that fills the rest of this slice. A decision made from the
     * backing state would otherwise always read the default below: that is exactly how steering
     * became unreachable from the composer while the send button still drew itself as "Steer this
     * reply". Anything added here that a non-UI code path branches on needs the same treatment.
     */
    val duringRunAction: DuringRunAction = DuringRunAction.QUEUE,
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
 * Chat-screen display preferences (floating top bar, options-sheet context gauge, during-run
 * send action), bundled into a single flow so [ChatViewModel]'s `uiState` combine stays within
 * Kotlin's 5-arg typed limit.
 */
@Immutable
data class ChatDisplayPrefs(
    val content: ChatHeaderContent = ChatHeaderContent.TITLE,
    val alignment: ChatHeaderAlignment = ChatHeaderAlignment.LEFT,
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
    val contextGaugeExpanded: Boolean = false,
    val duringRunAction: DuringRunAction = DuringRunAction.QUEUE,
)
