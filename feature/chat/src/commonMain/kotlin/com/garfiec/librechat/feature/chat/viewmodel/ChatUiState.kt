package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.util.MessageNode

enum class ChatScreenState { LANDING, LOADING, ACTIVE }

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
    val availableModels: Map<String, List<String>> = emptyMap(),
    val agents: List<Agent> = emptyList(),
    val conversationId: String? = null,
    val error: String? = null,
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
    // Model comparison state
    val comparisonState: ComparisonState = ComparisonState(),
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
}
