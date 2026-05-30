package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.util.MessageNode

enum class ChatScreenState { LANDING, LOADING, ACTIVE }

/**
 * Reasons why a send attempt was blocked. Resolved to a user-facing string in the Compose
 * layer via `stringResource`, so the ViewModel stays free of hard-coded English UI copy.
 */
sealed interface SendBlockReason {
    data object SelectAgent : SendBlockReason
    data object SelectModel : SendBlockReason
    data object AgentsUnavailable : SendBlockReason
    data object AgentNotAvailable : SendBlockReason
    data object ModelNotAvailable : SendBlockReason
    data object ModelLoadFailed : SendBlockReason
}

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
 * Represents a single occurrence of a search query match.
 * @param messageIndex Index into [ChatUiState.displayMessages] containing this occurrence.
 * @param occurrenceInMessage 0-based index of this occurrence within the message text.
 */
@Immutable
data class SearchMatch(
    val messageIndex: Int,
    val occurrenceInMessage: Int,
)

@Immutable
data class RetryInfo(
    val attempt: Int,
    val maxAttempts: Int,
)

@Immutable
data class ActiveToolCall(
    val id: String,
    val name: String,
    val isComplete: Boolean = false,
    val output: String? = null,
    /** Raw tool-call arguments JSON from [StreamEvent.ToolCallStart]. Holds the
     *  image prompt/quality for image-gen tools so a placeholder can render mid-stream. */
    val input: String? = null,
)

@Immutable
data class ChatUiState(
    val screenState: ChatScreenState = ChatScreenState.LANDING,
    val messages: List<Message> = emptyList(),
    val displayMessages: List<MessageNode> = emptyList(),
    val activeBranches: Map<String, Int> = emptyMap(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val streamingContent: String = "",
    val activeToolCalls: List<ActiveToolCall> = emptyList(),
    /** Attachments received during SSE streaming (e.g., tool-generated images).
     *  Cleared when streaming ends. Used to provide attachment context while the
     *  final message (with full attachments) has not yet been persisted to Room. */
    val streamingAttachments: List<Attachment> = emptyList(),
    val selectedModel: String? = null,
    val selectedEndpoint: String = EndpointConstants.AGENTS,
    val endpointConfigs: Map<String, EndpointConfig> = emptyMap(),
    /**
     * Per-endpoint user-provided-key state for endpoints with `userProvide=true`
     * (or `userProvideURL=true`). Drives the model selector's greyed-out group +
     * "Set API Key" CTA. Endpoints absent from this map are implicitly "doesn't
     * need a key" (built-ins) — [ModelSelectorSheet] fail-opens on `null` and
     * on [KeyState.Loading] so the cold-start window doesn't flash to greyed.
     */
    val endpointKeyStates: Map<String, KeyState> = emptyMap(),
    val availableModels: Map<String, List<String>> = emptyMap(),
    val agents: List<Agent> = emptyList(),
    val conversationId: String? = null,
    val error: String? = null,
    /** Set when a send was blocked for a selection/readiness reason. Resolved to a
     *  user-facing string in the Compose layer. Null means no send-block to show. */
    val sendBlockReason: SendBlockReason? = null,
    val presets: List<PresetDisplayData> = emptyList(),
    val availablePrompts: List<PromptMentionDisplayData> = emptyList(),
    val editingMessageId: String? = null,
    val editingText: String = "",
    val modelParameters: ModelParameters = ModelParameters.DEFAULT,
    val showModelParameters: Boolean = false,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    /** Whether the server has STT configured. When false, use device speech recognition. */
    val serverSttEnabled: Boolean = false,
    // TTS state
    val currentlyReadingMessageId: String? = null,
    // Temporary chat state
    val isTemporaryChat: Boolean = false,
    // In-conversation search state
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchMatchIndices: List<SearchMatch> = emptyList(),
    val currentSearchMatchIndex: Int = 0,
    /** The displayMessages index to scroll to when search match changes. Consumed by MessageList. */
    val searchScrollToIndex: Int? = null,
    // Tool toolbar state
    val enabledTools: Set<String> = emptySet(),
    // MCP server state
    val mcpServers: List<McpServerDisplayData> = emptyList(),
    val selectedMcpServerNames: Set<String> = emptySet(),
    // SSE reconnection retry state (null when not retrying)
    val retryInfo: RetryInfo? = null,
    // Pull-to-refresh state
    val isRefreshingMessages: Boolean = false,
    // Merged from separate StateFlows
    val serverUrl: String = "",
    val chatFontSize: ChatFontSize = ChatFontSize.MEDIUM,
    val forkedConversationId: String? = null,
    val showForkOptionsForMessageId: String? = null,
    val isForkInProgress: Boolean = false,
    // User profile info for message avatars
    val userName: String? = null,
    val userAvatarUrl: String? = null,
    // Conversation actions state
    val conversationTitle: String? = null,
    val sharedLinksEnabled: Boolean = false,
    val showRenameDialog: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val duplicatedConversationId: String? = null,
    /** Set at StreamEvent.Created when a new conversation's conversationId becomes available.
     *  The UI navigates to Chat(id) and then clears this via [ChatViewModel.onPendingNavigationHandled],
     *  which also resets this ViewModel to a clean landing state. */
    val pendingNavigationConversationId: String? = null,
    /** Single source of truth for whether the model-selector sheet is open. Preflight
     *  failures and readiness timeouts flip this to true so the user sees the sheet with
     *  a send-block banner. Manual taps on the model chip also set it true via
     *  [ChatViewModel.openModelSheet]. UI dismissal routes through [ChatViewModel.dismissModelSheet]. */
    val showModelSheet: Boolean = false,
    // Model comparison state
    val comparisonState: ComparisonState = ComparisonState(),
    // Role-permission gates — default permissive; narrowed once RoleRepository emits.
    val promptsEnabled: Boolean = true,
    val promptsCreateEnabled: Boolean = true,
    val agentsEnabled: Boolean = true,
    val agentsCreateEnabled: Boolean = true,
    val mcpServersEnabled: Boolean = true,
    val multiConvoEnabled: Boolean = true,
    val temporaryChatEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val runCodeEnabled: Boolean = true,
    val fileSearchEnabled: Boolean = true,
    val bookmarksEnabled: Boolean = true,
    /**
     * User-pinned agent IDs (v0.8.5 favorites). Pinned agents sort to the top
     * of the "My Agents" group in [ModelSelectorSheet] and get a filled star.
     */
    val favoriteAgentIds: Set<String> = emptySet(),
    /**
     * User-pinned model keys. Each key is `"$endpoint::$model"` — compare with
     * `FavoritesDelegate.favoriteModelKey(endpoint, model)`.
     */
    val favoriteModelKeys: Set<String> = emptySet(),
    /**
     * Whether the detected backend is v0.8.5+ and therefore accepts the `xhigh`
     * and `max` values in the reasoning-effort and effort dropdowns. False on
     * older or unknown servers (per VERSION_GATES.md guideline #2). See VERSION_GATES.md.
     */
    val extendedEffortSupported: Boolean = false,
) {
    /**
     * Effective tool set that merges [enabledTools] with the web search state from
     * [modelParameters]. This ensures the toolbar, bottom sheet, and dropdown all
     * reflect the same web search toggle as the Model Parameters sheet.
     */
    val effectiveEnabledTools: Set<String>
        get() = if (modelParameters.webSearch) {
            enabledTools + ToolConstants.WEB_SEARCH
        } else {
            enabledTools - ToolConstants.WEB_SEARCH
        }

    /**
     * Whether code interpreter (execute_code) is available on this server.
     * Derived from the agents endpoint config's capabilities list.
     * Mirrors the web frontend's `useAgentCapabilities` + `codeEnabled` check.
     */
    val isCodeInterpreterAvailable: Boolean
        get() {
            val agentsConfig = endpointConfigs[EndpointConstants.AGENTS]
            // If no agents config is loaded yet, default to true to avoid
            // hiding the toggle before config arrives. Once config loads,
            // the actual capabilities list is authoritative.
            return agentsConfig?.capabilities?.contains(ToolConstants.EXECUTE_CODE) ?: true
        }

    /**
     * True when firing `chatRepository.startChat(...)` is unlikely to race against
     * cold-start initialization.
     *
     * Conditions:
     * - [availableModels] is non-empty (server's endpoint/model list has arrived).
     * - The currently-selected endpoint isn't a known-denied one. Specifically: if
     *   [selectedEndpoint] == "agents", [agentsEnabled] must be true.
     *
     * Not included: [selectedModel] != null. The agents endpoint auto-selects a
     * default agent when none is set (see ModelSelectionDelegate.loadAgents) and
     * the non-agents flows always set model+endpoint together, so a null here is
     * orthogonal to the cold-start race we're guarding against.
     */
    val isSendReady: Boolean
        get() = availableModels.isNotEmpty() &&
            (selectedEndpoint != EndpointConstants.AGENTS || agentsEnabled)
}
