package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.DuringRunAction
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.model.response.FileUploadConfig
import com.garfiec.librechat.core.model.response.UploadRoute
import com.garfiec.librechat.core.model.response.isProviderCapable
import com.garfiec.librechat.core.model.response.isProviderUnknown
import com.garfiec.librechat.core.model.response.isTextExtractable
import com.garfiec.librechat.core.model.response.resolveUploadRoute
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.TokenUsage
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.core.ui.media.MediaPreviewState
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.util.MessageNode

/**
 * The agents endpoint capability that enables server-side text extraction (`tool_resource=context`).
 * Not in [ToolConstants]: it is a file-delivery capability, not a tool the composer can toggle.
 */
private const val FILE_CONTEXT_CAPABILITY = "context"

@Immutable
data class ChatUiState(
    // ── Extracted sub-state slices. Flat compat accessors below delegate to these so the
    //    ~250 UI read sites keep compiling; delegates write only their own slice. ──
    val queue: QueueState = QueueState(),
    val steer: SteerState = SteerState(),
    val search: ChatSearchState = ChatSearchState(),
    val favorites: FavoritesState = FavoritesState(),
    val subagents: SubagentState = SubagentState(),
    val actions: ConversationActionsState = ConversationActionsState(),
    val editing: MessageEditingState = MessageEditingState(),
    val composer: ComposerState = ComposerState(),
    val presetPrompts: PresetPromptState = PresetPromptState(),
    val voice: VoiceState = VoiceState(),
    val gates: FeatureGatesState = FeatureGatesState(),
    val account: AccountConfigState = AccountConfigState(),
    val prefs: ChatPrefsState = ChatPrefsState(),
    val selection: ModelSelectionState = ModelSelectionState(),
    val content: MessagesState = MessagesState(),
    val conversation: ConversationMetaState = ConversationMetaState(),
    val error: String? = null,
    // Model comparison state
    val comparisonState: ComparisonState = ComparisonState(),
    /**
     * Open-state for the full-screen zoomable media viewer (null = closed). Computed once on
     * tap by [ChatViewModel.openMedia] from a state snapshot, so the branch-media walk never
     * runs on the per-token streaming hot path.
     */
    val mediaPreview: MediaPreviewState? = null,
) {
    // ── Flat compat accessors: preserve the pre-slice read API (`uiState.field`) for UI + tests.
    //    Each delegates to its owning slice; writes go through the slice, not these. ──
    val messageQueue: List<QueuedMessage> get() = queue.messageQueue
    val isQueuePaused: Boolean get() = queue.isQueuePaused
    val pendingSteers: List<PendingSteerChip> get() = steer.pendingSteers
    val isSearchOpen: Boolean get() = search.isSearchOpen
    val searchQuery: String get() = search.searchQuery
    val searchMatchIndices: List<SearchMatch> get() = search.searchMatchIndices
    val currentSearchMatchIndex: Int get() = search.currentSearchMatchIndex
    val searchFocusRequest: SearchFocusRequest? get() = search.searchFocusRequest
    val favoriteAgentIds: Set<String> get() = favorites.favoriteAgentIds
    val favoriteModelKeys: Set<String> get() = favorites.favoriteModelKeys
    val subagentProgress: Map<String, SubagentTrace> get() = subagents.subagentProgress
    val showRenameDialog: Boolean get() = actions.showRenameDialog
    val showDeleteConfirmation: Boolean get() = actions.showDeleteConfirmation
    val duplicatedConversationId: String? get() = actions.duplicatedConversationId
    val showForkOptionsForMessageId: String? get() = actions.showForkOptionsForMessageId
    val isForkInProgress: Boolean get() = actions.isForkInProgress
    val forkedConversationId: String? get() = actions.forkedConversationId
    val editingMessageId: String? get() = editing.editingMessageId
    val editingText: String get() = editing.editingText
    val selectedModel: String? get() = selection.selectedModel
    val selectedEndpoint: String get() = selection.selectedEndpoint
    val endpointConfigs: Map<String, EndpointConfig> get() = selection.endpointConfigs
    val endpointKeyStates: Map<String, KeyState> get() = selection.endpointKeyStates
    val availableModels: Map<String, List<String>> get() = selection.availableModels
    val agents: List<Agent> get() = selection.agents
    val selectedAgentProvider: String? get() = selection.selectedAgentProvider
    val modelParameters: ModelParameters get() = selection.modelParameters
    val showModelSheet: Boolean get() = selection.showModelSheet
    val enabledTools: Set<String> get() = selection.enabledTools
    val mcpServers: List<McpServerDisplayData> get() = selection.mcpServers
    val selectedMcpServerNames: Set<String> get() = selection.selectedMcpServerNames
    val extendedEffortSupported: Boolean get() = selection.extendedEffortSupported
    val inputText: String get() = composer.inputText
    val sendBlockReason: SendBlockReason? get() = composer.sendBlockReason
    val editingQueuedItem: QueuedEditSession? get() = composer.editingQueuedItem
    val isAwaitingUploadSend: Boolean get() = composer.isAwaitingUploadSend

    /**
     * True while picked files exist but are not yet in the attachment tray — mid-intake, or staged
     * awaiting a manual routing decision.
     *
     * Every send path refuses while it holds, because the files are in neither `attachedFiles` nor
     * `hasPendingUploads()` and an ungated send would go out without them. The composer reads it
     * too: a control that will refuse the tap must not render as though it will act on it.
     */
    val arePicksUnsettled: Boolean
        get() = composer.pendingUploadRouting != null || composer.isResolvingPickedFiles
    val presets: List<PresetDisplayData> get() = presetPrompts.presets
    val availablePrompts: List<PromptMentionDisplayData> get() = presetPrompts.availablePrompts
    val pendingVariablePrompt: PendingVariablePrompt? get() = presetPrompts.pendingVariablePrompt
    val isRecording: Boolean get() = voice.isRecording
    val isTranscribing: Boolean get() = voice.isTranscribing
    val serverSttEnabled: Boolean get() = voice.serverSttEnabled
    val currentlyReadingMessageId: String? get() = voice.currentlyReadingMessageId
    val userName: String? get() = account.userName
    val userAvatarUrl: String? get() = account.userAvatarUrl
    val fileUploadConfig: FileUploadConfig? get() = account.fileUploadConfig
    val serverUrl: String get() = prefs.serverUrl
    val chatFontSize: ChatFontSize get() = prefs.chatFontSize
    val starredModelsDisplay: StarredModelsDisplay get() = prefs.starredModelsDisplay
    val chatHeaderContent: ChatHeaderContent get() = prefs.chatHeaderContent
    val chatHeaderAlignment: ChatHeaderAlignment get() = prefs.chatHeaderAlignment
    val contextBarPlacement: ContextBarPlacement get() = prefs.contextBarPlacement
    val duringRunAction: DuringRunAction get() = prefs.duringRunAction
    val contextGaugeExpanded: Boolean get() = prefs.contextGaugeExpanded
    val promptsEnabled: Boolean get() = gates.promptsEnabled
    val promptsCreateEnabled: Boolean get() = gates.promptsCreateEnabled
    val agentsEnabled: Boolean get() = gates.agentsEnabled
    val agentsCreateEnabled: Boolean get() = gates.agentsCreateEnabled
    val mcpServersEnabled: Boolean get() = gates.mcpServersEnabled
    val multiConvoEnabled: Boolean get() = gates.multiConvoEnabled
    val temporaryChatEnabled: Boolean get() = gates.temporaryChatEnabled
    val webSearchEnabled: Boolean get() = gates.webSearchEnabled
    val runCodeEnabled: Boolean get() = gates.runCodeEnabled
    val fileSearchEnabled: Boolean get() = gates.fileSearchEnabled
    val bookmarksEnabled: Boolean get() = gates.bookmarksEnabled
    val presetsEnabled: Boolean get() = gates.presetsEnabled
    val modelSelectEnabled: Boolean get() = gates.modelSelectEnabled
    val parametersEnabled: Boolean get() = gates.parametersEnabled
    val pinnedTools: List<String> get() = gates.pinnedTools
    val contextUsageEnabled: Boolean get() = gates.contextUsageEnabled
    val screenState: ChatScreenState get() = content.screenState
    val messages: List<Message> get() = content.messages
    val displayMessages: List<MessageNode> get() = content.displayMessages
    val pendingResumeUserMessage: Message? get() = content.pendingResumeUserMessage
    val activeBranches: Map<String, Int> get() = content.activeBranches
    val justSettledMessageId: String? get() = content.justSettledMessageId
    val isStreaming: Boolean get() = content.isStreaming
    val streamingContent: String get() = content.streamingContent
    val activeToolCalls: List<ActiveToolCall> get() = content.activeToolCalls
    val streamingAttachments: List<Attachment> get() = content.streamingAttachments
    val retryInfo: RetryInfo? get() = content.retryInfo
    val isRefreshingMessages: Boolean get() = content.isRefreshingMessages
    val messagesLoadFailed: Boolean get() = content.messagesLoadFailed
    val contextUsage: ContextUsage? get() = content.contextUsage
    val tokenUsage: TokenUsage? get() = content.tokenUsage
    val pendingAction: PendingAction? get() = content.pendingAction
    val isResolvingPendingAction: Boolean get() = content.isResolvingPendingAction
    val memoryEnabled: Boolean get() = gates.memoryEnabled
    val conversationId: String? get() = conversation.conversationId
    val conversationTitle: String? get() = conversation.conversationTitle
    val isTemporaryChat: Boolean get() = conversation.isTemporaryChat
    val sharedLinksEnabled: Boolean get() = conversation.sharedLinksEnabled
    val pendingNavigationConversationId: String? get() = conversation.pendingNavigationConversationId

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
                    ToolConstants.MEMORY -> ToolConstants.MEMORY.takeIf { isMemoryToolAvailable }
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

    /**
     * Whether a message typed right now could be steered into the running turn (v0.8.8).
     *
     * Requires everything queueing requires, plus a server with the steer route
     * ([FeatureGatesState.steeringSupported]) and a run that is actually reachable. A run parked
     * on a human-review pause is not: the steer route answers `RUN_PAUSED` for it, so offering
     * steer there would only spend a round-trip to land in the queue anyway.
     *
     * This is availability, not preference — [effectiveDuringRunAction] applies the user's
     * choice on top, and the composer's during-run menu reads this to decide whether steering
     * can be picked per send.
     */
    val canSteerNow: Boolean
        get() = canQueueFollowUp && gates.steeringSupported && pendingAction == null

    /**
     * What the composer's send control will actually do mid-run: the user's preference, degraded
     * to [DuringRunAction.QUEUE] whenever steering is unavailable.
     *
     * Degradation is silent by design. Queueing is the behaviour mobile has always had and it
     * works against every server, so a preference that cannot be honoured costs the user
     * nothing — whereas a control that announced "steer" and then failed would.
     */
    val effectiveDuringRunAction: DuringRunAction
        get() = if (canSteerNow) duringRunAction else DuringRunAction.QUEUE

    /**
     * What the composer's send control does while a reply is generating.
     *
     * [DuringRunSendTarget.ANSWER_PAUSE] takes precedence over the user's steer/queue preference:
     * a run parked on `ask_user_question` is waiting for exactly this text, and the composer is
     * the input the user can actually see — the pause card carries its own field but sits at the
     * tail of the thread. Treating that send as an ordinary queued follow-up left the pause
     * unresolved and delivered the answer as a non-sequitur after the run expired.
     *
     * Tool-approval pauses are excluded: they take decisions, not prose, so free text there is a
     * genuine follow-up.
     */
    val duringRunSendTarget: DuringRunSendTarget
        get() {
            val pause = renderablePendingAction
            if (pause != null && pause.isAskUserQuestion && !isResolvingPendingAction) {
                return DuringRunSendTarget.ANSWER_PAUSE
            }
            return when (effectiveDuringRunAction) {
                DuringRunAction.STEER -> DuringRunSendTarget.STEER
                DuringRunAction.QUEUE -> DuringRunSendTarget.QUEUE
            }
        }

    /**
     * The pause to render resolve controls for, or null.
     *
     * Deliberately NOT version-gated. The pause is self-proving: it can only be here because the
     * server pushed `on_pending_action` or reported it on `/chat/status`, which is proof that
     * server has the HITL plumbing and the resume route. A version/date gate here would instead
     * fail closed on the exact population that emits these — a server built from an upstream
     * commit newer than the pinned one resolves to a null `DetectedBackend`, and hiding the card
     * then leaves the user staring at a live cursor on a run that will never produce another
     * token, with Stop as the only way out.
     *
     * Only two things are required: an [PendingAction.actionId] to resolve against (the delegate
     * cannot post a resume without one) and a payload type the card can render — an unknown
     * future type is dropped rather than shown as an empty card, leaving the run to expire
     * server-side, the same outcome as before HITL existed.
     */
    val renderablePendingAction: PendingAction?
        get() = pendingAction?.takeIf {
            !it.actionId.isNullOrBlank() && (it.isToolApproval || it.isAskUserQuestion)
        }

    /** Number of queued messages a Stop/error pause is holding (0 = none / not paused). Drives
     *  the "Send queued" banner above the composer. */
    val pausedQueueCount: Int
        get() = if (isQueuePaused) messageQueue.size else 0

    /** True while the composer is editing a queued item rather than composing a new message. */
    val isEditingQueued: Boolean
        get() = editingQueuedItem != null

    /**
     * Bundle of the feature gates that flow into the composer ([ChatInput] →
     * [ChatToolsSheetContent]), so the four are threaded as one value across both
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
     * Whether the composer's memory toggle should be offered: the role/opt-out half from
     * [FeatureGatesState.memoryEnabled], AND the agents endpoint advertising the `memory`
     * capability.
     *
     * Unlike [isCodeInterpreterAvailable] this fails CLOSED on a config that hasn't arrived: the
     * capability is off by default server-side, so assuming it would flash a toggle that mostly
     * shouldn't be there and, worse, let a send carry `ephemeralAgent.memory` the server drops.
     */
    val isMemoryToolAvailable: Boolean
        get() = memoryEnabled &&
            !account.memoriesOptedOut &&
            endpointConfigs[EndpointConstants.AGENTS]?.capabilities?.contains(ToolConstants.MEMORY) == true

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
     * Whether the server can extract an attachment's text (the `context` capability), which is
     * what [UploadRoute.TEXT] needs.
     *
     * Fails OPEN on a config that hasn't arrived, unlike [isMemoryToolAvailable]: upstream's own
     * default is `capabilities ?? defaultAgentCapabilities`, which includes `context`.
     *
     * An **empty** list counts as "not stated" too, not as "none" — `EndpointConfig.capabilities`
     * defaults to `emptyList()`, so a server that simply doesn't send the field is indistinguishable
     * from one that sends `[]`, and no real deployment has zero agent capabilities. Reading `[]` as
     * a denial would ship the whole feature dead against exactly those servers. A non-empty list
     * that omits `context` is a genuine denial and is honoured.
     */
    val isFileContextAvailable: Boolean
        get() {
            // `capabilities` is the AGENTS endpoint's own policy and binds only there. `POST
            // /api/files` hands every non-assistants upload to the same handler, whose `context`
            // branch runs NO capability check (only `execute_code` and `file_search` do) — so
            // applying the agents list to an openAI/anthropic/custom selection would refuse an
            // extraction the server performs happily, and one admin narrowing agent capabilities
            // would switch the feature off app-wide.
            if (selectedEndpoint != EndpointConstants.AGENTS) return true
            val capabilities = endpointConfigs[EndpointConstants.AGENTS]?.capabilities ?: return true
            return capabilities.isEmpty() || FILE_CONTEXT_CAPABILITY in capabilities
        }

    /**
     * The delivery mode [mimeType] would get on the current selection, resolved against the
     * provider actually behind it — the agent's own provider on the agents endpoint, the
     * endpoint's `type` for a custom endpoint whose name is just an admin's label.
     */
    fun uploadRouteFor(mimeType: String?): UploadRoute {
        if (!isFileContextAvailable) return UploadRoute.PROVIDER
        return resolveUploadRoute(
            mimeType = mimeType,
            endpoint = selectedEndpoint,
            endpointType = endpointConfigs[selectedEndpoint]?.type,
            agentProvider = routingAgentProvider,
        )
    }

    /**
     * The agent provider a routing decision may be made against.
     *
     * [ModelSelectionState.selectedAgentProvider] is documented as null on every non-agents
     * endpoint, but the collector enforcing that resumes on a *later* Main dispatch than the write
     * that moved the endpoint. A pick issued in that gap would otherwise route against the provider
     * of an agent the selection has already left — sending a PDF to text on an endpoint that would
     * have taken it natively.
     */
    private val routingAgentProvider: String?
        get() = selectedAgentProvider.takeIf { selectedEndpoint == EndpointConstants.AGENTS }

    /** Whether both delivery modes are genuinely open for [mimeType] — the manual prompt's gate. */
    fun uploadRouteIsAmbiguous(mimeType: String?): Boolean {
        if (!isFileContextAvailable) return false
        // A type the server cannot extract has exactly one usable mode however capable the
        // provider is: there is no second option to offer.
        if (!isTextExtractable(mimeType)) return false
        val endpointType = endpointConfigs[selectedEndpoint]?.type
        // An unresolved provider is not a *provider-only* file. Auto still routes it to PROVIDER —
        // guessing is what this feature refuses to do — but a user who asked to be asked every time
        // must still be asked, and the sheet must not claim "this model can only take it directly"
        // about a model we could not identify. Without this the whole batch reads as unchoosable
        // and the sheet never opens at all.
        if (isProviderUnknown(selectedEndpoint, endpointType, routingAgentProvider)) return true
        return isProviderCapable(
            mimeType = mimeType,
            endpoint = selectedEndpoint,
            endpointType = endpointType,
            agentProvider = routingAgentProvider,
        )
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

/** Where the composer's send routes while a reply is generating. See [ChatUiState.duringRunSendTarget]. */
enum class DuringRunSendTarget {
    /** Resolve the live `ask_user_question` pause with the composer's text. */
    ANSWER_PAUSE,

    /** Inject into the running turn (v0.8.8 steering). */
    STEER,

    /** Hold as a follow-up for after the run. */
    QUEUE,
}
