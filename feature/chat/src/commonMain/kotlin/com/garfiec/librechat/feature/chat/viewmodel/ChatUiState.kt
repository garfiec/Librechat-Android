package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.SubagentPhase
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.model.response.FileUploadConfig
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.TokenUsage
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.core.ui.media.MediaPreviewState
import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.util.MessageNode
import kotlinx.serialization.json.JsonObject

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
 * The chat floating top bar's mobile-only display preferences, bundled into a single
 * flow so [ChatViewModel]'s `uiState` combine stays within Kotlin's 5-arg typed limit.
 */
@Immutable
data class ChatHeaderPrefs(
    val content: ChatHeaderContent = ChatHeaderContent.TITLE,
    val alignment: ChatHeaderAlignment = ChatHeaderAlignment.LEFT,
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
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

/**
 * A request to scroll the message list to one specific search occurrence. [messageIndex] is the
 * scroll target; [requestId] is a monotonic id that both makes repeat jumps to the same occurrence
 * distinct (so the list's LaunchedEffect re-fires) and identifies the position report to act on.
 */
@Immutable
data class SearchFocusRequest(
    val messageIndex: Int,
    val requestId: Long,
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

/**
 * A follow-up message the user queued while a response was streaming, waiting to be
 * auto-sent (FIFO) once the current reply completes. Rendered as a dimmed "ghost" bubble
 * after the streaming bubble — it is NOT part of the message tree.
 *
 * Captures a full snapshot of the send config **at queue time** (model/endpoint/tools/
 * webSearch/attachments + the resolved [dispatch]/[ephemeralAgent]), so a mid-stream model
 * switch never retro-edits an already-queued item. The live-lineage fields
 * (conversationId / parentMessageId / userMessageId) are deliberately NOT snapshotted — they
 * are recomputed from the current tree when the item actually fires.
 *
 * [attachments] holds the already-uploaded [AttachedFile]s (not bare FileReferences) so editing
 * a queued item restores its composer chips — including the local-uri image thumbnail — intact.
 */
@Immutable
data class QueuedMessage(
    /** Stable local id for list keying, edit, and reorder. Not a server message id. */
    val localId: String,
    val text: String,
    val attachments: List<AttachedFile> = emptyList(),
    val endpoint: String,
    val model: String?,
    val agentId: String?,
    val enabledTools: Set<String> = emptySet(),
    /** Selected ephemeral MCP servers — snapshotted so editing the item restores its tool state. */
    val mcpServerNames: Set<String> = emptySet(),
    /** Full composer parameters (web search, reasoning effort, etc.) — restored to the composer on edit. */
    val modelParameters: ModelParameters = ModelParameters.DEFAULT,
    /**
     * Non-default model params (provider-keyed) serialized for the wire, snapshotted at enqueue time
     * so a queued send carries the params it was composed with. Null when nothing was customized.
     */
    val modelParamsPayload: JsonObject? = null,
    val ephemeralAgent: EphemeralAgent? = null,
    val dispatch: EndpointDispatch,
    val isTemporary: Boolean = false,
    /**
     * The active account when this item was queued. A drain guard drops any item whose account no
     * longer matches the active one (the user switched accounts since queueing), so a follow-up
     * composed under account A can never be POSTed to account B's server under B's bearer. Null for
     * items composed before multi-account (or in tests) — treated as "matches any", never dropped.
     */
    val accountId: String? = null,
)

/**
 * Snapshot of the editable composer surface (the "new message" draft) stashed when entering
 * queued-edit mode and restored on commit/cancel, so editing a queued item never clobbers what
 * the user was already composing.
 *
 * These fields mirror [QueuedMessage]'s source-config fields (model/endpoint/tools/mcp/params) and
 * are captured/applied by `ChatViewModel.captureComposer`/`applyComposer`/`toComposerSnapshot` — a
 * new composer setting must be threaded through all of them (no compiler enforcement).
 */
@Immutable
data class ComposerSnapshot(
    val text: String,
    val attachments: List<AttachedFile> = emptyList(),
    val endpoint: String,
    val model: String?,
    val enabledTools: Set<String> = emptySet(),
    val mcpServerNames: Set<String> = emptySet(),
    val modelParameters: ModelParameters = ModelParameters.DEFAULT,
)

/**
 * Active queued-edit session: the composer is loaded with [original]'s content + config for
 * editing, while [stashed] holds the new-message draft to restore afterward and [originalIndex]
 * is the FIFO slot to put the (possibly edited) item back into on commit/cancel.
 */
@Immutable
data class QueuedEditSession(
    val original: QueuedMessage,
    val originalIndex: Int,
    val stashed: ComposerSnapshot,
)

/** Loads a queued item's content + config onto the composer surface when entering edit mode. */
fun QueuedMessage.toComposerSnapshot(): ComposerSnapshot = ComposerSnapshot(
    text = text,
    attachments = attachments,
    endpoint = endpoint,
    model = model,
    enabledTools = enabledTools,
    mcpServerNames = mcpServerNames,
    modelParameters = modelParameters,
)

/**
 * Live progress of a single child agent's run (v0.8.6 subagents), accumulated
 * from `on_subagent_update` SSE envelopes and keyed in
 * [ChatUiState.subagentProgress] by the parent `subagent` tool_call id.
 *
 * [parts] are the child's flat content (reasoning / tool calls / text) folded in
 * arrival order — the same shapes a normal message renders, so the trace card
 * reuses the existing content-part renderers (depth 1; a subagent never nests
 * another subagent card). On reload this live trace is superseded by the
 * authoritative persisted `AgentToolCall.subagentContent`.
 */
@Immutable
data class SubagentTrace(
    val parentToolCallId: String,
    val subagentRunId: String? = null,
    val subagentType: String? = null,
    /** Latest phase label for the live ticker (e.g. the agent's display name). */
    val label: String? = null,
    /** Latest lifecycle/content phase reported by the server. */
    val phase: String = SubagentPhase.START,
    val parts: List<MessageContentPart> = emptyList(),
    /** True once the server reports a terminal phase (`stop`/`error`). */
    val isComplete: Boolean = false,
)

/**
 * Feature gates that flow into the composer ([ChatInput] → [ChatToolsBottomSheet]),
 * bundled so they thread as one value across Android and iOS. Each defaults to true
 * (shown) so a default-constructed bundle is fully permissive. Built by
 * [ChatUiState.chatInputGates]; see [ChatUiState.modelSelectEnabled] /
 * [ChatUiState.parametersEnabled] / [ChatUiState.showEphemeralTools] /
 * [ChatUiState.fileUploadEnabled] for the individual gating rules.
 */
@Immutable
data class ChatInputGates(
    val modelSelectEnabled: Boolean = true,
    val parametersEnabled: Boolean = true,
    val showEphemeralTools: Boolean = true,
    val fileUploadEnabled: Boolean = true,
)

@Immutable
data class ChatUiState(
    val screenState: ChatScreenState = ChatScreenState.LANDING,
    val messages: List<Message> = emptyList(),
    val displayMessages: List<MessageNode> = emptyList(),
    /**
     * A just-sent optimistic user message handed off from the NewChat landing VM, kept on screen
     * until the server persists its own copy. For a resumed new chat the server saves the request
     * message only when the reply finishes (see `agents/request.js`), so `getMessages` returns no
     * user message mid-stream; without this seed the user's own message would vanish for the whole
     * stream. `loadConversation` reconciles it away by id once the server's copy arrives. Null in
     * every other case. See [NewChatSelectionHandoff].
     */
    val pendingResumeUserMessage: Message? = null,
    val activeBranches: Map<String, Int> = emptyMap(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val streamingContent: String = "",
    val activeToolCalls: List<ActiveToolCall> = emptyList(),
    /** Live subagent traces (v0.8.6) keyed by the parent `subagent` tool_call id.
     *  Folded from `on_subagent_update` SSE events while streaming; reset on each
     *  new run and conversation switch alongside [activeToolCalls]. On reload the
     *  persisted `AgentToolCall.subagentContent` is authoritative instead. */
    val subagentProgress: Map<String, SubagentTrace> = emptyMap(),
    /** Attachments received during SSE streaming (e.g., tool-generated images).
     *  Cleared when streaming ends. Used to provide attachment context while the
     *  final message (with full attachments) has not yet been persisted to Room. */
    val streamingAttachments: List<Attachment> = emptyList(),
    /** Follow-up messages queued while a reply streams, drained FIFO on each successful
     *  completion. Rendered as ghost bubbles after the streaming bubble; never part of the
     *  message tree. In-memory only (dropped on conversation switch / process death). */
    val messageQueue: List<QueuedMessage> = emptyList(),
    /** True after Stop/stream-error with a non-empty queue: draining is held until the user
     *  explicitly taps "Send queued". A successful Final drains automatically instead. */
    val isQueuePaused: Boolean = false,
    /** Non-null while a queued item is being edited in the composer (queued-edit mode). Holds the
     *  stashed new-message draft + the item's slot so both are restored on commit/cancel. */
    val editingQueuedItem: QueuedEditSession? = null,
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
    /** The search occurrence to scroll to when the focused match changes. Consumed by MessageList. */
    val searchFocusRequest: SearchFocusRequest? = null,
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
    /**
     * Mobile-only preference for how pinned models/agents are surfaced in
     * [ModelSelectorSheet]: off (float within group), grouped (collapsible top
     * section), or top (flat top list). Read from [SettingsDataStore].
     */
    val starredModelsDisplay: StarredModelsDisplay = StarredModelsDisplay.OFF,
    /**
     * Mobile-only preferences for the chat floating top bar: what its bubble shows
     * ([chatHeaderContent]) and how the bubble is positioned ([chatHeaderAlignment]).
     * Read from [SettingsDataStore].
     */
    val chatHeaderContent: ChatHeaderContent = ChatHeaderContent.TITLE,
    val chatHeaderAlignment: ChatHeaderAlignment = ChatHeaderAlignment.LEFT,
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
    // Feature gates — effective value is `interface.* flag AND role permission`,
    // matching the web client. Default permissive (true); narrowed once both the
    // RoleRepository and the server's interface config emit. Fails open when an
    // interface flag is absent (older backends that don't send it).
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
     * Interface-only gates (no corresponding role permission on web). Driven solely by
     * the server's `interface.*` config: presets on `interface.presets && interface.modelSelect`,
     * model select on `interface.modelSelect`, parameters on `interface.parameters`.
     * See web `Chat/Header.tsx` and `Chat/Input/HeaderOptions.tsx`.
     */
    val presetsEnabled: Boolean = true,
    val modelSelectEnabled: Boolean = true,
    val parametersEnabled: Boolean = true,
    /** `interface.defaultPinnedTools` (v0.8.7): tool keys the server pins to the prompt bar.
     *  Raw, as sent; mapped/filtered to renderable chips by [pinnedToolChips]. */
    val pinnedTools: List<String> = emptyList(),
    /**
     * Context-usage gauge gate (v0.8.7). [contextUsageEnabled] = `interface.contextUsage`
     * AND backend ≥ 0.8.7. Fails closed on older/unknown servers (the gauge has no data source there).
     */
    val contextUsageEnabled: Boolean = false,
    /** Latest context-window usage snapshot for the gauge, from the `on_context_usage` SSE
     *  event or the context-projection endpoint. Null until the first snapshot arrives. */
    val contextUsage: ContextUsage? = null,
    /** Latest per-call provider token usage (`on_token_usage` SSE), feeding the context
     *  sheet's Input/Output rows. Null until a stream reports usage. */
    val tokenUsage: TokenUsage? = null,
    /** User preference (Settings → Chat) for where the context gauge is surfaced (above the
     *  composer, in the "+" sheet, in the overflow menu, or hidden). Default
     *  [ContextBarPlacement.OPTIONS_SHEET]; independent of [contextUsageEnabled] (the server/version gate). */
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
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
    /**
     * Server upload config (`GET /api/files/config`).
     * Null before fetch or on fetch failure; treated as no constraint (controls fail open).
     */
    val fileUploadConfig: FileUploadConfig? = null,
    /**
     * Open-state for the full-screen zoomable media viewer (null = closed). Computed once on
     * tap by [ChatViewModel.openMedia] from a state snapshot, so the branch-media walk never
     * runs on the per-token streaming hot path.
     */
    val mediaPreview: MediaPreviewState? = null,
) {
    /**
     * Whether the chat attach controls (Camera / Photos / Files) should be offered for
     * the current endpoint. Mirrors web's AttachFileChat gate:
     *  - The agents endpoint always allows attaching (file tools are gated per-agent
     *    inside the menu on web; mobile keeps the controls visible).
     *  - Other endpoints allow it unless the server marks the endpoint's file config
     *    `disabled` (or the global default disabled).
     *
     * Fails OPEN: a null/absent config (older backend, fetch not landed, or fetch failed)
     * leaves attaching enabled to preserve current behavior.
     */
    val fileUploadEnabled: Boolean
        get() {
            if (selectedEndpoint == EndpointConstants.AGENTS) return true
            val config = fileUploadConfig ?: return true
            // Per-endpoint override wins; fall back to the global default `disabled`.
            val endpointDisabled = config.endpoints[selectedEndpoint]?.disabled
            return when {
                endpointDisabled != null -> !endpointDisabled
                else -> !config.disabled
            }
        }

    /**
     * Effective tool set that merges [enabledTools] with the web search state from
     * [modelParameters]. This ensures the toolbar, bottom sheet, and dropdown all
     * reflect the same web search toggle as the Model Parameters sheet.
     */
    val effectiveEnabledTools: Set<String>
        get() {
            // web_search and url_context are model parameters, not entries in [enabledTools];
            // synthesize them in so the toolbar, bottom sheet, and pinned chips all reflect
            // the same toggle state as the Model Parameters sheet.
            var tools = enabledTools
            tools = if (modelParameters.webSearch) tools + ToolConstants.WEB_SEARCH else tools - ToolConstants.WEB_SEARCH
            tools = if (modelParameters.urlContext) tools + ToolConstants.URL_CONTEXT else tools - ToolConstants.URL_CONTEXT
            return tools
        }

    /**
     * Whether the active provider is Google/Gemini, the only provider that supports the
     * `url_context` toggle (upstream gates it to `googleConfig`). Computed (not folded into
     * the static permission/config flow) because it depends on the live model selection:
     * a direct `google` endpoint, or the agents endpoint backed by a Google-provider agent.
     * Mirrors the provider resolution in [ChatRequestBuilder.buildModelParams].
     */
    val urlContextProviderGate: Boolean
        get() {
            if (selectedEndpoint.equals("google", ignoreCase = true)) return true
            if (selectedEndpoint != EndpointConstants.AGENTS) return false
            val agentProvider = agents.firstOrNull { it.id == selectedModel }?.provider
            return agentProvider.equals("google", ignoreCase = true)
        }

    /**
     * The server's [pinnedTools] mapped to mobile tool keys and filtered to those mobile
     * recognizes AND whose own enable-gate is currently satisfied, preserving config order.
     * Rendered as inline quick-toggle chips on the input bar. Empty for the agents endpoint
     * (ephemeral tools are hidden there) and for any unsupported key (`artifacts`, `mcp`, …).
     */
    val pinnedToolChips: List<String>
        get() {
            if (!showEphemeralTools || pinnedTools.isEmpty()) return emptyList()
            return pinnedTools.mapNotNull { key ->
                when (key) {
                    ToolConstants.WEB_SEARCH -> ToolConstants.WEB_SEARCH.takeIf { webSearchEnabled }
                    ToolConstants.URL_CONTEXT -> ToolConstants.URL_CONTEXT.takeIf { urlContextProviderGate }
                    ToolConstants.FILE_SEARCH -> ToolConstants.FILE_SEARCH.takeIf { fileSearchEnabled }
                    ToolConstants.EXECUTE_CODE, ToolConstants.CODE_INTERPRETER ->
                        ToolConstants.CODE_INTERPRETER.takeIf { isCodeInterpreterAvailable && runCodeEnabled }
                    else -> null
                }
            }.distinct()
        }

    /**
     * Whether the per-request ephemeral tool controls (dynamic MCP servers, web search,
     * code interpreter, file search) should be offered. Mirrors web's `showEphemeralBadges`
     * (ChatForm.tsx): a saved agent uses its own configured tools, so the backend ignores
     * client-supplied ephemeral tools on agent runs. Mobile's only agent-type endpoint is
     * [EndpointConstants.AGENTS], so the gate is simply "not the agents endpoint".
     */
    val showEphemeralTools: Boolean
        get() = selectedEndpoint != EndpointConstants.AGENTS

    /**
     * Whether a follow-up may be queued mid-stream: only on an existing single-stream
     * conversation (the queue affordance is hidden on the landing screen and in comparison
     * mode). Single source of truth for the Android/iOS composer wiring.
     */
    val canQueueFollowUp: Boolean
        get() = conversationId != null && !comparisonState.isEnabled

    /** Number of queued messages a Stop/error pause is holding (0 = none / not paused). Drives
     *  the "Send queued" banner above the composer. */
    val pausedQueueCount: Int
        get() = if (isQueuePaused) messageQueue.size else 0

    /** True while the composer is editing a queued item rather than composing a new message. */
    val isEditingQueued: Boolean
        get() = editingQueuedItem != null

    /**
     * Bundle of the feature gates that flow into the composer ([ChatInput] →
     * [ChatToolsBottomSheet]), so the four are threaded as one value across both
     * platforms instead of four parallel params. Built from the existing fields;
     * see each property for its gating rule. (`presetsEnabled` is not included —
     * it flows to the top bar, a different path.)
     */
    val chatInputGates: ChatInputGates
        get() = ChatInputGates(
            modelSelectEnabled = modelSelectEnabled,
            parametersEnabled = parametersEnabled,
            showEphemeralTools = showEphemeralTools,
            fileUploadEnabled = fileUploadEnabled,
        )

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
