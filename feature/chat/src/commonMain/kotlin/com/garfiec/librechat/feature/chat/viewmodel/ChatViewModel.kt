package com.garfiec.librechat.feature.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.getOrNull
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.logging.LogOrigin
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.Preset
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.config.InterfaceConfig
import com.garfiec.librechat.core.model.error.UserKeyError
import com.garfiec.librechat.core.model.error.parseUserKeyError
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.model.request.EphemeralAgent
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.core.ui.media.MediaItem
import com.garfiec.librechat.core.ui.media.MediaPreviewState
import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.components.ParsedMarkdownCache
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.util.NEW_CHAT_DRAFT_KEY
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.util.extractBranchMedia
import com.garfiec.librechat.feature.chat.util.mergeFinalMessagesInMemory
import com.garfiec.librechat.feature.chat.viewmodel.delegate.ConversationActionsDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.EndpointKeyStatusDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.FavoritesDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.InConversationSearchDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.ModelSelectionDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.OfficePreviewDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PlatformDelegateFactory
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PresetPromptDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.SubagentTraceDelegate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Suppress("TooManyFunctions", "LongParameterList")
class ChatViewModel(
    initialConversationId: String? = null,
    initialAgentId: String? = null,
    private val agentRepository: AgentRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val fileRepository: FileRepository,
    private val configRepository: ConfigRepository,
    private val conversationRepository: ConversationRepository,
    private val draftRepository: DraftRepository,
    favoritesRepository: FavoritesRepository,
    private val keyRepository: KeyRepository,
    presetRepository: PresetRepository,
    promptRepository: PromptRepository,
    shareRepository: ShareRepository,
    mcpRepository: McpRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val permissionGate: PermissionGate,
    private val connectivityObserver: ConnectivityObserver,
    serverDataStore: ServerDataStore,
    private val settingsDataStore: SettingsDataStore,
    platformDelegateFactory: PlatformDelegateFactory,
    private val json: Json,
    private val defaultDispatcher: CoroutineDispatcher,
    private val selectionHandoff: NewChatSelectionHandoff,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    private val stateHandle = ChatStateHandle(_uiState, viewModelScope)

    // Mermaid SVG cache, scoped to this ViewModel's lifecycle. Filled by inline-
    // artifact WebViews via a JS bridge; read by SharedContentParts when a
    // recompose reaches an already-rendered flowchart mermaid.
    val mermaidRenderCache: com.garfiec.librechat.feature.chat.components.artifact.MermaidRenderCache =
        com.garfiec.librechat.feature.chat.components.artifact.MermaidRenderCache()

    // Parsed-AST cache for chat-text markdown. m3 parses async; when a LazyColumn
    // item is recycled its remembered state dies and parsing restarts, producing
    // a 0-px → final-height cascade that pushes adjacent inline-artifact slots
    // around (the scroll-jump root cause). Caching the parsed State.Success lets
    // re-entry render directly from the cached AST. See CachedMarkdown.
    val parsedMarkdownCache: ParsedMarkdownCache = ParsedMarkdownCache()

    // --- Platform delegates (created via factory so they get the ViewModel's stateHandle) ---
    private val fileDelegate = platformDelegateFactory.createFileHandler(stateHandle)
    private val ttsDelegate = platformDelegateFactory.createTts(stateHandle, ::getMessageText)
    private val voiceDelegate = platformDelegateFactory.createVoiceInput(stateHandle, ::sendMessage)
    private val shareConsumer = platformDelegateFactory.createShareConsumer()

    // --- Delegates ---
    private val searchDelegate = InConversationSearchDelegate(stateHandle)
    private val conversationActionsDelegate =
        ConversationActionsDelegate(stateHandle, conversationRepository, shareRepository)
    private val presetPromptDelegate = PresetPromptDelegate(stateHandle, presetRepository, promptRepository)
    private val favoritesDelegate = FavoritesDelegate(stateHandle, favoritesRepository)
    private val modelDelegate = ModelSelectionDelegate(
        stateHandle = stateHandle,
        configRepository = configRepository,
        agentRepository = agentRepository,
        mcpRepository = mcpRepository,
        settingsDataStore = settingsDataStore,
        permissionGate = permissionGate,
        initialAgentId = initialAgentId,
    )
    private val keyStatusDelegate = EndpointKeyStatusDelegate(
        stateHandle = stateHandle,
        keyRepository = keyRepository,
    )
    private val subagentTraceDelegate = SubagentTraceDelegate(stateHandle, json)
    private val officePreviewDelegate = OfficePreviewDelegate(stateHandle, fileRepository)

    // --- Delegate-owned flows exposed to the UI ---
    val attachedFiles: StateFlow<List<AttachedFile>> get() = fileDelegate.attachedFiles
    val shareLinkUrl: StateFlow<String?> get() = conversationActionsDelegate.shareLinkUrl

    private data class BaseChatPrefs(
        val showImageDescriptions: Boolean,
        val dismissKeyboardOnSend: Boolean,
        val chatLayoutStyle: String,
        val showAvatars: Boolean,
        val showBubbles: Boolean,
    )

    private data class SttAndRendererPrefs(
        val latexRenderer: LatexRenderer,
        val autoSendAfterStt: Boolean,
        val sttEngine: String,
        val sttLanguage: String,
        val inlineArtifactPrefs: com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs,
    )

    // Combined in stages because Kotlin's `combine` maxes out at 5 args. Each stage
    // produces a typed sub-record, and they're folded into `ChatPreferences` at the end.
    // Adding a new pref: extend a sub-record (or add a third combine) — no positional casts.
    private val baseChatPrefs = combine(
        settingsDataStore.showImageDescriptions,
        settingsDataStore.dismissKeyboardOnSend,
        settingsDataStore.chatLayoutStyle,
        settingsDataStore.showAvatars,
        settingsDataStore.showBubbles,
    ) { imgDesc, dismissKb, layout, avatars, bubbles ->
        BaseChatPrefs(imgDesc, dismissKb, layout, avatars, bubbles)
    }

    private val sttAndRendererPrefs = combine(
        settingsDataStore.latexRenderer,
        settingsDataStore.autoSendAfterStt,
        settingsDataStore.sttEngine,
        settingsDataStore.sttLanguage,
        settingsDataStore.inlineArtifactPrefs,
    ) { latex, autoSendStt, sttEngine, sttLang, inlineArtifacts ->
        SttAndRendererPrefs(latex, autoSendStt, sttEngine, sttLang, inlineArtifacts)
    }

    val chatPreferences: StateFlow<ChatPreferences> = combine(
        baseChatPrefs,
        sttAndRendererPrefs,
    ) { base, sttRenderer ->
        ChatPreferences(
            showImageDescriptions = base.showImageDescriptions,
            dismissKeyboardOnSend = base.dismissKeyboardOnSend,
            chatLayoutStyle = base.chatLayoutStyle,
            showAvatars = base.showAvatars,
            showBubbles = base.showBubbles,
            latexRenderer = sttRenderer.latexRenderer,
            autoSendAfterStt = sttRenderer.autoSendAfterStt,
            sttEngine = sttRenderer.sttEngine,
            sttLanguage = sttRenderer.sttLanguage,
            inlineArtifactPrefs = sttRenderer.inlineArtifactPrefs,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatPreferences())

    val uiState: StateFlow<ChatUiState> = combine(
        _uiState,
        serverDataStore.currentUrlFlow,
        settingsDataStore.chatFontSize,
        settingsDataStore.starredModelsDisplay,
    ) { state, url, fontSize, starredDisplay ->
        state.copy(
            serverUrl = url,
            chatFontSize = fontSize,
            starredModelsDisplay = starredDisplay,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    // Channel-backed for exactly-once snackbar delivery across rotation.
    private val _userKeyErrors = Channel<UserKeyError>(Channel.BUFFERED)
    val userKeyErrors: Flow<UserKeyError> = _userKeyErrors.receiveAsFlow()

    private var streamJob: Job? = null
    private var roomObserverJob: Job? = null
    private var streamingUpdateJob: Job? = null
    private val streamingBuffer = StringBuilder()
    private var streamingBufferDirty = false
    private var wasStreaming = false

    /** Tracks whether the last stream failure was a network error, to enable auto-reconnect. */
    private var lastErrorWasNetwork = false

    /** Job for the connectivity observer; started lazily only when a network error occurs. */
    private var connectivityJob: Job? = null

    companion object {
        /** Minimum interval between streaming UI state updates to avoid recomposition spam. */
        private const val STREAMING_UI_UPDATE_INTERVAL_MS = 50L

        /** Timeout for the pre-send "is the endpoint/config ready" await. Snappier than the
         *  5 s role-load timeout because this only needs one of role OR availableModels to
         *  satisfy the check. */
        private const val SEND_READY_TIMEOUT_MS = 3_000L
    }

    /** True when the current stream is from an edit, regenerate, or continue operation. */
    private var isEditOrRegenerate = false

    /** True when this ViewModel was opened for a brand-new chat (no conversationId from navigation). */
    private val isNewConversation: Boolean

    /** Guard to ensure we only attempt title generation once per conversation. */
    private var titleGenerationRequested = false

    init {
        val conversationId = initialConversationId
        isNewConversation = conversationId == null
        if (conversationId != null) {
            _uiState.update {
                it.copy(
                    conversationId = conversationId,
                    screenState = ChatScreenState.LOADING,
                )
            }
            // If we arrived here straight from the NewChat landing (the common case for a
            // just-created chat), the landing VM staged the exact (endpoint, model) it sent.
            // Apply it up front so the header/selection is correct immediately and the
            // racy loadConversationModel GET below can't clobber it with a fallback guess
            // when the server hasn't persisted the conversation yet (the created-before-save
            // race). See NewChatSelectionHandoff.
            selectionHandoff.take(conversationId)?.let { handoff ->
                Diag.d(
                    tag = "ModelSel",
                    attrs = mapOf("endpoint" to handoff.endpoint, "model" to (handoff.model ?: "null")),
                ) { "handoff applied for $conversationId" }
                modelDelegate.applyResolvedConversationModel(handoff.endpoint, handoff.model)
            }
            loadConversation(conversationId)
            loadConversationModel(conversationId)
            restoreDraft(conversationId)
            // Check if there's an active stream for this conversation (e.g. when
            // navigating here from NewChat immediately after sending). If so,
            // resume it so the user sees streaming content on this screen.
            resumeActiveStreamIfNeeded(conversationId)
        } else {
            // For new chats, mark conversationModelLoaded so refilterModels
            // doesn't wait for a conversation model that will never arrive.
            modelDelegate.conversationModelLoaded = true
            // Consume any pending share intent data (text and/or files shared from another app)
            consumeShareIntent()
            restoreDraft(NEW_CHAT_DRAFT_KEY)
        }

        // Observe share intents that arrive while this ViewModel is already active
        viewModelScope.launch {
            shareConsumer.shareAvailable.collect {
                consumeShareIntent()
            }
        }

        // Single authority for a new chat's initial model selection. Continuous so
        // the retained NewChat landing VM re-syncs to last-used when it changes
        // (a model picked later inside a conversation), and deterministic so the
        // selection no longer races between the last-used read, agent auto-select,
        // and model fallbacks. No-ops for existing conversations (loadConversationModel
        // owns those). See ModelSelectionDelegate.seedInitialSelection.
        modelDelegate.seedInitialSelection(isNewConversation)

        viewModelScope.launch {
            configRepository.endpointConfigs.collect { configs ->
                _uiState.update { it.copy(endpointConfigs = configs) }
                modelDelegate.refilterModels(isNewConversation)
                keyStatusDelegate.recomputeFor(configs)
                // If code interpreter is no longer available, remove it from enabled tools
                val agentsCapabilities = configs[EndpointConstants.AGENTS]?.capabilities ?: emptyList()
                if (agentsCapabilities.isNotEmpty() && ToolConstants.EXECUTE_CODE !in agentsCapabilities) {
                    _uiState.update {
                        if (ToolConstants.CODE_INTERPRETER in it.enabledTools) {
                            it.copy(enabledTools = it.enabledTools - ToolConstants.CODE_INTERPRETER)
                        } else {
                            it
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            // refilterModels publishes the filtered availableModels into state; no
            // need to write the raw map first (it would only be overwritten).
            configRepository.availableModels.collect {
                modelDelegate.refilterModels(isNewConversation)
            }
        }

        // Gate the `xhigh` and `max` reasoning-effort dropdown values to v0.8.5+ servers.
        // Older servers reject the unknown enums at request time. See VERSION_GATES.md.
        viewModelScope.launch {
            configRepository.detectedBackendVersion.collect { version ->
                val supported = version != null &&
                    BackendVersion.isCompatibleOrNewer(version, "0.8.5")
                _uiState.update { it.copy(extendedEffortSupported = supported) }
            }
        }

        viewModelScope.launch {
            val endpointsResult = configRepository.fetchEndpoints()
            if (endpointsResult is Result.Error) {
                _uiState.update {
                    it.copy(error = endpointsResult.message ?: "Could not load endpoint configuration")
                }
                return@launch
            }
            val modelsResult = configRepository.fetchModels()
            if (modelsResult is Result.Error) {
                _uiState.update {
                    it.copy(error = modelsResult.message ?: "Could not load available models")
                }
            }
        }

        // Restore MCP server and tool selections from DataStore so they
        // survive the NewChat -> Chat(id) navigation re-creation.
        viewModelScope.launch {
            val mcpServers = settingsDataStore.selectedMcpServers.first()
            val tools = settingsDataStore.enabledTools.first()
            if (mcpServers.isNotEmpty() || tools.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        selectedMcpServerNames = mcpServers,
                        enabledTools = tools,
                    )
                }
            }
        }

        presetPromptDelegate.loadPresets()
        // Favorites is user-personal (not server-permission-gated upstream); load eagerly
        // so the chat-side pin stars and Settings → Favorites stay in sync from cold start.
        favoritesDelegate.load()
        loadUserProfile()
        loadFlags()
        loadFileConfig()
        voiceDelegate.loadSpeechConfig()

        // Gated loads share a single 5-second role-await budget so offline/timeout
        // launches don't serialize into N×5s. `role?.hasAccess(...) != false`
        // preserves permissive default: null role (timeout/never-loaded) → true,
        // missing type/action → true, explicit false → false.
        viewModelScope.launch {
            val role = permissionGate.awaitRole()
            if (role?.hasAccess(PermissionType.PROMPTS, Permission.USE) != false) {
                presetPromptDelegate.loadAvailablePrompts()
            }
            if (role?.hasAccess(PermissionType.MCP_SERVERS, Permission.USE) != false) {
                modelDelegate.loadMcpServers()
            }
            // Always call loadAgents — it self-gates on the AGENTS.USE permission and
            // flips its agentsLoaded flag on every path (including denial). Skipping it
            // here would leave the flag false and park the seeder on the agents tier
            // forever (no model on the landing) for agents-denied users.
            modelDelegate.loadAgents(isNewConversation)
        }
    }

    // ── Core chat flow ──────────────────────────────────────────────

    private fun loadConversation(conversationId: String) {
        // SECURITY: do not remove — temp-chat data-at-rest guard.
        // Defense-in-depth for temporary chats (v0.8.6): never route a temp conversation
        // through the Room read-through, which would upsert its message rows to disk (the
        // convo is hidden from history but the text would persist). The temp chat's
        // display is finalized in memory by finalizeTemporaryChatDisplay; any stray
        // loadConversation call (safety-net, error/abort paths) must not touch the DB.
        if (_uiState.value.isTemporaryChat) return
        // Cancel any previous Room observer to avoid duplicate collectors
        roomObserverJob?.cancel()
        roomObserverJob = viewModelScope.launch {
            try {
                messageRepository.getMessages(conversationId)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to fetch messages for $conversationId" }
                _uiState.update {
                    it.copy(
                        error = "Could not load messages",
                        screenState = ChatScreenState.ACTIVE,
                    )
                }
            }
            // buildActiveMessagePath is pure/synchronous CPU work; computing it on the Default
            // dispatcher keeps the tree walk off Main. Combining the active-branch selection in
            // (rather than peeking _uiState.value inside the map) keeps the branch snapshot
            // consistent with the emission — no torn read — and means switchBranch only has to
            // mutate activeBranches: the heavy recompute happens here off Main, not on the click
            // thread. The result feeds a StateFlow (not a Compose snapshot), so it's safe off-Main.
            combine(
                messageRepository.observeMessages(conversationId),
                _uiState.map { it.activeBranches }.distinctUntilChanged(),
            ) { messages, branches ->
                messages to buildActiveMessagePath(messages, branches)
            }
                .flowOn(defaultDispatcher)
                .collect { (messages, displayMessages) ->
                    _uiState.update {
                        it.copy(
                            messages = messages,
                            displayMessages = displayMessages,
                            screenState = ChatScreenState.ACTIVE,
                        )
                    }
                }
        }
    }

    /**
     * Restores a previously saved draft for the given key (conversation ID or [NEW_CHAT_DRAFT_KEY]).
     */
    private fun restoreDraft(draftKey: String) {
        viewModelScope.launch {
            val draft = draftRepository.getDraft(draftKey)
            if (!draft.isNullOrBlank()) {
                _uiState.update {
                    if (it.inputText.isBlank()) it.copy(inputText = draft) else it
                }
            }
        }
    }

    private fun loadConversationModel(conversationId: String) {
        viewModelScope.launch {
            val result = conversationRepository.getConversation(conversationId)
            val conversation = result.getOrNull()
            if (conversation != null) {
                _uiState.update { it.copy(conversationTitle = conversation.title) }
                val applied = applyConversationModel(conversation)
                Diag.d(
                    tag = "ModelSel",
                    attrs = mapOf(
                        "found" to "true",
                        "applied" to applied.toString(),
                        "endpoint" to (conversation.endpoint ?: "null"),
                    ),
                ) { "loadConversationModel resolved for $conversationId" }
            } else {
                // The just-created conversation isn't readable yet: the server emits the
                // `created` SSE event before the unawaited save persists it, so this GET can
                // race that save and 404. The in-process handoff already seeded the correct
                // selection in init, so we deliberately leave it untouched here. Only mark
                // "load attempted" — never "resolved" — so handleFinal can re-derive later.
                Diag.w(
                    tag = "ModelSel",
                    origin = LogOrigin.SERVER,
                    attrs = mapOf("found" to "false"),
                ) { "loadConversationModel: conversation not readable for $conversationId" }
            }
            modelDelegate.conversationModelLoaded = true
            modelDelegate.refilterModels(isNewConversation)
        }
    }

    /**
     * Resolves a loaded [conversation]'s authoritative (endpoint, model) and applies it as
     * the active selection via [ModelSelectionDelegate.applyResolvedConversationModel].
     * Agents conversations carry the agent in `agentId`, so prefer that over `model` for the
     * AGENTS endpoint. Returns true when a concrete selection was applied (i.e. the
     * conversation model is now resolved), false when the conversation lacked enough info.
     */
    private fun applyConversationModel(conversation: Conversation): Boolean {
        val endpoint = conversation.endpoint
        val isAgentConversation = endpoint == EndpointConstants.AGENTS
        val resolvedModel = if (isAgentConversation) {
            conversation.agentId ?: conversation.model
        } else {
            conversation.model
        }
        if (endpoint != null && resolvedModel != null) {
            modelDelegate.applyResolvedConversationModel(endpoint, resolvedModel)
            return true
        }
        return false
    }

    private fun refreshConversationTitle(conversationId: String) {
        viewModelScope.launch {
            val result = conversationRepository.getConversation(conversationId)
            val conversation = result.getOrNull() ?: return@launch
            _uiState.update { it.copy(conversationTitle = conversation.title) }
        }
    }

    private fun generateAndSetTitle(conversationId: String) {
        viewModelScope.launch {
            when (val result = conversationRepository.generateTitle(conversationId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(conversationTitle = result.data) }
                }
                is Result.Error -> {
                    Logger.d { "Title generation failed for $conversationId: ${result.message}" }
                    refreshConversationTitle(conversationId)
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun switchBranch(parentMessageId: String, siblingIndex: Int) {
        // Ignore branch switches mid-stream: the in-flight reply's path is truncated at
        // its parent (see anchorStreamTo / buildActiveMessagePath's streamingLeafId), and
        // mutating activeBranches re-triggers loadConversation's combine, which rebuilds
        // displayMessages WITHOUT the leaf — un-truncating the path and dropping the
        // streaming reply back to the end. editMessage/regenerateMessage are likewise
        // gated on isStreaming; this closes the same gap for sibling navigation.
        if (_uiState.value.isStreaming) return
        // Only mutate the branch selection; the observeMessages/activeBranches combine in
        // loadConversation recomputes displayMessages off the Main thread in response, so the
        // tree walk no longer runs synchronously on this UI click path.
        _uiState.update {
            val newBranches = it.activeBranches.toMutableMap()
            newBranches[parentMessageId] = siblingIndex
            it.copy(activeBranches = newBranches)
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
        val draftKey = _uiState.value.conversationId ?: NEW_CHAT_DRAFT_KEY
        viewModelScope.launch {
            draftRepository.saveDraft(draftKey, text)
        }
    }

    private fun consumeShareIntent() {
        val shareData = shareConsumer.consume() ?: return
        Logger.d { "consumeShareIntent: text=${shareData.text != null}, files=${shareData.fileRefs.size}" }

        if (!shareData.text.isNullOrBlank()) {
            _uiState.update { it.copy(inputText = shareData.text) }
        }

        if (shareData.fileRefs.isNotEmpty()) {
            fileDelegate.onFilesSelected(shareData.fileRefs)
        }
    }

    // --- Message sending ---

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (_uiState.value.isStreaming) return

        // Prevent double-send while waiting for uploads to finish
        if (fileDelegate.pendingUploadSendJob?.isActive == true) return

        // Check if there are files still uploading.
        if (fileDelegate.hasPendingUploads()) {
            Logger.d { "sendMessage: waiting for pending upload(s) to complete" }
            fileDelegate.pendingUploadSendJob = viewModelScope.launch {
                fileDelegate.waitForUploadsAndSend(text) { runWhenSendReady { doSendMessage(it) } }
            }
            return
        }

        runWhenSendReady { doSendMessage(text) }
    }

    /**
     * Builds an [EphemeralAgent] from the current UI state (selected MCP servers
     * and enabled tools). Returns null when there is nothing to send.
     */
    private fun buildEphemeralAgent(): EphemeralAgent? {
        val state = _uiState.value
        // A saved agent uses its own configured tools; the backend ignores client-supplied
        // ephemeral tools for agent runs. Mirror web's `showEphemeralBadges` (ChatForm.tsx)
        // and never serialize leftover UI selections on the agents endpoint.
        if (!state.showEphemeralTools) return null
        val mcpServers = state.selectedMcpServerNames.toList().ifEmpty { null }
        val enabledTools = state.enabledTools
        val webSearchEnabled = state.modelParameters.webSearch

        val hasAnything = mcpServers != null || enabledTools.isNotEmpty() || webSearchEnabled
        if (!hasAnything) return null

        return EphemeralAgent(
            mcp = mcpServers,
            webSearch = if (webSearchEnabled) true else null,
            fileSearch = if (ToolConstants.FILE_SEARCH in enabledTools) true else null,
            executeCode = if (ToolConstants.CODE_INTERPRETER in enabledTools || ToolConstants.EXECUTE_CODE in enabledTools) true else null,
        )
    }

    /**
     * Resolves the chat-send dispatch for the currently-selected endpoint by snapshotting
     * `_uiState.value` once. Used by every chat-send code path (new message, edit, regenerate,
     * continue) — callers that target a different endpoint (e.g. the comparison-mode
     * secondary) must call [resolveEndpointDispatch] directly with that endpoint name.
     */
    private fun currentDispatch(): EndpointDispatch {
        val state = _uiState.value
        return resolveEndpointDispatch(
            endpointName = state.selectedEndpoint,
            endpointConfigs = state.endpointConfigs,
            endpointKeyStates = state.endpointKeyStates,
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun doSendMessage(text: String) {
        val fileRefs = fileDelegate.buildFileReferences()
        val hasFiles = fileRefs.isNotEmpty()
        if ((text.isBlank() && !hasFiles) || _uiState.value.isStreaming) return

        val conversationId = _uiState.value.conversationId
        val lastMessageId = _uiState.value.displayMessages.lastOrNull()?.message?.messageId

        // Add optimistic user message to display immediately
        val messageText = text.ifBlank {
            if (hasFiles) "" else return
        }
        val optimisticMessage = Message(
            messageId = Uuid.random().toString(),
            conversationId = conversationId ?: "",
            parentMessageId = lastMessageId,
            text = messageText,
            isCreatedByUser = true,
            sender = "User",
            createdAt = Clock.System.now().toString(),
            files = fileRefs.takeIf { it.isNotEmpty() },
        )
        isEditOrRegenerate = false

        val isNewChat = conversationId == null
        _uiState.update {
            val updatedMessages = it.messages + optimisticMessage
            val updatedDisplay = buildActiveMessagePath(updatedMessages, it.activeBranches, optimisticMessage.messageId)
            it.copy(
                inputText = "",
                isStreaming = true,
                streamingContent = "",
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
                screenState = if (isNewChat) ChatScreenState.LANDING else ChatScreenState.ACTIVE,
                error = null,
                messages = updatedMessages,
                displayMessages = updatedDisplay,
            )
        }
        // Clear draft
        val draftKey = conversationId ?: NEW_CHAT_DRAFT_KEY
        viewModelScope.launch { draftRepository.deleteDraft(draftKey) }
        // Clear attached files
        fileDelegate.clearAttachedFiles()
        streamingBuffer.clear()
        streamingBufferDirty = false
        subagentTraceDelegate.reset()
        officePreviewDelegate.reset()
        startStreamingUpdater()

        val isAgent = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Logger.d {
            "sendMessage: webSearch=$webSearchEnabled, " +
                "endpoint=${_uiState.value.selectedEndpoint}, " +
                "model=${_uiState.value.selectedModel}, " +
                "files=${fileRefs.size}, " +
                "ephemeralAgent=$ephemeralAgent"
        }

        // Resolve effective endpoint/agentId for comparison mode.
        // All requests go through api/agents/chat/{endpoint} — the server's
        // middleware creates ephemeral agents for non-agent endpoints, so no
        // swapping is needed. Just keep the primary's original endpoint.
        val effectiveEndpoint = _uiState.value.selectedEndpoint
        val effectiveAgentId = if (isAgent) _uiState.value.selectedModel else null
        val isComparisonEnabled = _uiState.value.comparisonState.isEnabled
        if (isComparisonEnabled) {
            modelDelegate.primaryComparisonBuffer.clear()
            modelDelegate.secondaryComparisonBuffer.clear()
            _uiState.update {
                it.copy(comparisonState = it.comparisonState.copy(
                    primaryIsStreaming = true,
                    secondaryIsStreaming = true,
                    primaryStreamingContent = "",
                    secondaryStreamingContent = "",
                    primaryActiveToolCalls = emptyList(),
                    secondaryActiveToolCalls = emptyList(),
                    primaryAgentId = null,
                    secondaryAgentId = null,
                    parallelMessageId = null,
                    primaryFinalContent = null,
                    secondaryFinalContent = null,
                ))
            }
        }

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val effectiveAddedConvo = if (isComparisonEnabled) {
                modelDelegate.buildAddedConvo(parentMessageId = lastMessageId)
            } else {
                null
            }
            val dispatch = currentDispatch()
            collectStreamSafely(
                chatRepository.startChat(
                    text = messageText,
                    conversationId = conversationId,
                    endpoint = effectiveEndpoint,
                    endpointType = dispatch.endpointType,
                    key = dispatch.key,
                    modelDisplayLabel = dispatch.modelDisplayLabel,
                    model = _uiState.value.selectedModel,
                    userMessageId = optimisticMessage.messageId,
                    parentMessageId = lastMessageId,
                    agentId = effectiveAgentId,
                    webSearch = webSearchEnabled,
                    files = fileRefs.takeIf { it.isNotEmpty() },
                    addedConvo = effectiveAddedConvo,
                    ephemeralAgent = ephemeralAgent,
                    isTemporary = _uiState.value.isTemporaryChat,
                ),
            )
            // Safety net: if the flow ends without Final or Error, clear streaming
            if (_uiState.value.isStreaming) {
                val cid = _uiState.value.conversationId
                if (cid != null && roomObserverJob?.isActive != true) {
                    loadConversation(cid)
                } else if (cid == null) {
                    _uiState.update { it.copy(isStreaming = false) }
                }
            }
        }
    }

    /**
     * Resets streaming-related UI state and clears the buffer in preparation
     * for a new stream (edit, regenerate, or continue).
     */
    private fun prepareForStreaming() {
        _uiState.update {
            it.copy(
                isStreaming = true,
                streamingContent = "",
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
                error = null,
            )
        }
        streamingBuffer.clear()
        streamingBufferDirty = false
        subagentTraceDelegate.reset()
        officePreviewDelegate.reset()
        startStreamingUpdater()
    }

    /**
     * Rebuilds the active path truncated at [parentMessageId] — the message the
     * in-flight reply attaches to — so the streaming bubble renders in place
     * (replacing the stale branch for edit/regenerate) rather than being appended
     * after it. Used by the paths that reuse an existing message as the parent
     * (regenerate, edit-AI); the optimistic-message paths (send, edit-user) pass the
     * same leaf to [buildActiveMessagePath] inline alongside their message insert.
     *
     * The full tree stays in `messages` and the DB, so the old branch remains
     * reachable via sibling navigation, and loadConversation() rebuilds the real
     * path on Final. This truncated displayMessages then simply persists in state
     * for the duration of the stream: safe because no streaming entry point writes
     * to Room mid-stream and none mutate activeBranches, so the Room observer never
     * re-emits to rebuild (and un-truncate) the path before the stream completes.
     */
    private fun anchorStreamTo(parentMessageId: String) {
        _uiState.update {
            it.copy(
                displayMessages = buildActiveMessagePath(it.messages, it.activeBranches, parentMessageId),
            )
        }
    }

    /**
     * Launches a periodic coroutine that flushes the [streamingBuffer] to UI state
     * at most every [STREAMING_UI_UPDATE_INTERVAL_MS] ms. This avoids recomposition spam
     * from high-frequency SSE chunks (each chunk would otherwise trigger a full state copy).
     */
    private fun startStreamingUpdater() {
        streamingUpdateJob?.cancel()
        streamingUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(STREAMING_UI_UPDATE_INTERVAL_MS)
                flushStreamingBuffer()
            }
        }
    }

    /**
     * Flushes the streaming buffer to UI state if it has been modified since the last flush.
     * Called both periodically (by the updater) and immediately on stream completion/error.
     */
    private fun flushStreamingBuffer() {
        if (!streamingBufferDirty) return
        streamingBufferDirty = false
        _uiState.update { it.copy(streamingContent = streamingBuffer.toString()) }
    }

    /**
     * Stops the periodic streaming updater and performs a final flush so the last
     * chunk is never lost.
     */
    private fun stopStreamingUpdater() {
        streamingUpdateJob?.cancel()
        streamingUpdateJob = null
        flushStreamingBuffer()
    }

    fun editMessage(messageId: String, newText: String) {
        if (newText.isBlank() || _uiState.value.isStreaming) return

        val originalMessage = _uiState.value.messages.find { it.messageId == messageId } ?: return

        runWhenSendReady {
            if (originalMessage.isCreatedByUser) {
                editUserMessage(originalMessage, newText)
            } else {
                editAiMessage(originalMessage)
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun editUserMessage(originalMessage: Message, newText: String) {
        val parentMessageId = originalMessage.parentMessageId

        isEditOrRegenerate = true

        // Optimistically insert the edited text as a new sibling of the original user
        // message and anchor the stream to it, so the new message — with the response
        // streaming below it — replaces the old branch in place. The anchor truncates
        // the active path here (see buildActiveMessagePath), mirroring the web client's
        // `currentMsg + initialResponse` placeholder insert. The optimistic id is sent
        // as `userMessageId` so the server adopts it; that lets the message reconcile by
        // id on Final — via loadConversation() for normal chats, or in-memory via
        // mergeFinalMessagesInMemory for temp chats (which never touch Room).
        val optimisticMessage = Message(
            messageId = Uuid.random().toString(),
            conversationId = _uiState.value.conversationId ?: "",
            parentMessageId = parentMessageId,
            text = newText,
            isCreatedByUser = true,
            sender = "User",
            createdAt = Clock.System.now().toString(),
            files = originalMessage.files,
        )
        _uiState.update {
            val updatedMessages = it.messages + optimisticMessage
            it.copy(
                messages = updatedMessages,
                displayMessages = buildActiveMessagePath(updatedMessages, it.activeBranches, optimisticMessage.messageId),
            )
        }

        prepareForStreaming()

        val isAgent = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Logger.d { "editUserMessage: webSearch=$webSearchEnabled, ephemeralAgent=$ephemeralAgent" }
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val dispatch = currentDispatch()
            collectStreamSafely(
                chatRepository.startChat(
                    text = newText,
                    conversationId = _uiState.value.conversationId,
                    endpoint = _uiState.value.selectedEndpoint,
                    endpointType = dispatch.endpointType,
                    key = dispatch.key,
                    modelDisplayLabel = dispatch.modelDisplayLabel,
                    model = _uiState.value.selectedModel,
                    userMessageId = optimisticMessage.messageId,
                    parentMessageId = parentMessageId,
                    agentId = if (isAgent) _uiState.value.selectedModel else null,
                    isEdited = true,
                    webSearch = webSearchEnabled,
                    ephemeralAgent = ephemeralAgent,
                    isTemporary = _uiState.value.isTemporaryChat,
                ),
            )
        }
    }

    private fun editAiMessage(aiMessage: Message) {
        val parentUserMessage = _uiState.value.messages.find {
            it.messageId == aiMessage.parentMessageId
        } ?: return

        val conversationId = _uiState.value.conversationId ?: return

        isEditOrRegenerate = true
        // Editing an assistant message resubmits its parent user turn (isEdited +
        // isRegenerate) — the same shape as regenerate, so anchor the stream to the
        // parent user message and let the new response stream in below it, replacing
        // the old one. The web client seeds the placeholder with the edited content
        // for a transient preview; we don't (the regenerated server response is
        // authoritative on Final either way).
        anchorStreamTo(parentUserMessage.messageId)
        prepareForStreaming()

        val isAgent = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Logger.d { "editAiMessage: webSearch=$webSearchEnabled, ephemeralAgent=$ephemeralAgent" }
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val dispatch = currentDispatch()
            collectStreamSafely(
                chatRepository.startChat(
                    text = parentUserMessage.text,
                    conversationId = conversationId,
                    endpoint = _uiState.value.selectedEndpoint,
                    endpointType = dispatch.endpointType,
                    key = dispatch.key,
                    modelDisplayLabel = dispatch.modelDisplayLabel,
                    model = _uiState.value.selectedModel,
                    parentMessageId = parentUserMessage.parentMessageId,
                    agentId = if (isAgent) _uiState.value.selectedModel else null,
                    overrideParentMessageId = parentUserMessage.messageId,
                    isEdited = true,
                    isRegenerate = true,
                    webSearch = webSearchEnabled,
                    ephemeralAgent = ephemeralAgent,
                    isTemporary = _uiState.value.isTemporaryChat,
                ),
            )
        }
    }

    fun regenerateMessage(messageId: String) {
        if (_uiState.value.isStreaming) return

        val aiMessage = _uiState.value.messages.find { it.messageId == messageId } ?: return
        if (aiMessage.isCreatedByUser) return

        val parentUserMessage = _uiState.value.messages.find {
            it.messageId == aiMessage.parentMessageId
        } ?: return

        runWhenSendReady { regenerateMessageNow(parentUserMessage) }
    }

    private fun regenerateMessageNow(parentUserMessage: Message) {
        isEditOrRegenerate = true
        anchorStreamTo(parentUserMessage.messageId)
        prepareForStreaming()

        val isAgentRegen = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Logger.d { "regenerateMessage: webSearch=$webSearchEnabled, ephemeralAgent=$ephemeralAgent" }
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val dispatch = currentDispatch()
            collectStreamSafely(
                chatRepository.startChat(
                    text = parentUserMessage.text,
                    conversationId = _uiState.value.conversationId,
                    endpoint = _uiState.value.selectedEndpoint,
                    endpointType = dispatch.endpointType,
                    key = dispatch.key,
                    modelDisplayLabel = dispatch.modelDisplayLabel,
                    model = _uiState.value.selectedModel,
                    parentMessageId = parentUserMessage.parentMessageId,
                    agentId = if (isAgentRegen) _uiState.value.selectedModel else null,
                    overrideParentMessageId = parentUserMessage.messageId,
                    isRegenerate = true,
                    webSearch = webSearchEnabled,
                    ephemeralAgent = ephemeralAgent,
                    isTemporary = _uiState.value.isTemporaryChat,
                ),
            )
        }
    }

    fun getMessageText(messageId: String): String {
        val message = _uiState.value.messages.find { it.messageId == messageId } ?: return ""
        val contentParts = message.content
        if (!contentParts.isNullOrEmpty()) {
            return contentParts.mapNotNull { part ->
                part.text ?: part.think
            }.joinToString("")
        }
        return message.text
    }

    private suspend fun collectStreamSafely(stream: Flow<StreamEvent>) {
        try {
            stream.collect { event -> handleStreamEvent(event) }
        } catch (e: CancellationException) {
            throw e // Never swallow cancellation
        } catch (e: Exception) {
            Logger.e(e) { "Stream collection failed" }
            stopStreamingUpdater()
            // Preserve partial content so users can read/copy what was received
            val partialContent = streamingBuffer.toString()
            _uiState.update {
                it.copy(
                    isStreaming = false,
                    streamingContent = partialContent,
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                    error = e.message ?: "Chat request failed",
                    comparisonState = it.comparisonState.copy(
                        primaryIsStreaming = false,
                        secondaryIsStreaming = false,
                        primaryActiveToolCalls = emptyList(),
                        secondaryActiveToolCalls = emptyList(),
                    ),
                )
            }
            // If the server already created a conversation, fetch whatever it persisted
            val conversationId = _uiState.value.conversationId
            if (conversationId != null) {
                loadConversation(conversationId)
            }
        }
    }

    private fun handleStreamEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.Created -> handleCreated(event)
            is StreamEvent.ContentDelta -> {
                val isComparison = _uiState.value.comparisonState.isEnabled
                if (isComparison && modelDelegate.isSecondaryEvent(event.agentId)) {
                    modelDelegate.secondaryComparisonBuffer.append(event.chunk)
                    _uiState.update {
                        it.copy(comparisonState = it.comparisonState.copy(
                            secondaryStreamingContent = modelDelegate.secondaryComparisonBuffer.toString(),
                            secondaryIsStreaming = true,
                            secondaryAgentId = it.comparisonState.secondaryAgentId ?: event.agentId,
                        ))
                    }
                } else if (isComparison) {
                    modelDelegate.primaryComparisonBuffer.append(event.chunk)
                    _uiState.update {
                        it.copy(
                            comparisonState = it.comparisonState.copy(
                                primaryStreamingContent = modelDelegate.primaryComparisonBuffer.toString(),
                                primaryIsStreaming = true,
                                primaryAgentId = it.comparisonState.primaryAgentId ?: event.agentId,
                            ),
                            streamingContent = modelDelegate.primaryComparisonBuffer.toString(),
                        )
                    }
                } else {
                    streamingBuffer.append(event.chunk)
                    streamingBufferDirty = true
                }
            }
            is StreamEvent.ThinkingDelta -> {
                val isComparison = _uiState.value.comparisonState.isEnabled
                if (isComparison && modelDelegate.isSecondaryEvent(event.agentId)) {
                    modelDelegate.secondaryComparisonBuffer.append(event.chunk)
                    _uiState.update {
                        it.copy(comparisonState = it.comparisonState.copy(
                            secondaryStreamingContent = modelDelegate.secondaryComparisonBuffer.toString(),
                            secondaryIsStreaming = true,
                            secondaryAgentId = it.comparisonState.secondaryAgentId ?: event.agentId,
                        ))
                    }
                } else if (isComparison) {
                    modelDelegate.primaryComparisonBuffer.append(event.chunk)
                    _uiState.update {
                        it.copy(
                            comparisonState = it.comparisonState.copy(
                                primaryStreamingContent = modelDelegate.primaryComparisonBuffer.toString(),
                                primaryIsStreaming = true,
                                primaryAgentId = it.comparisonState.primaryAgentId ?: event.agentId,
                            ),
                            streamingContent = modelDelegate.primaryComparisonBuffer.toString(),
                        )
                    }
                } else {
                    streamingBuffer.append(event.chunk)
                    streamingBufferDirty = true
                }
            }
            is StreamEvent.Final -> handleFinal(event)
            is StreamEvent.Error -> {
                stopStreamingUpdater()
                // Track network errors so auto-reconnect can kick in when connectivity returns
                lastErrorWasNetwork = event.isNetworkError
                if (event.isNetworkError) {
                    startConnectivityObserver()
                }
                // Try to parse the message as a typed user-provided-key error envelope.
                // If recognized, emit a one-shot effect so the UI can surface a snackbar
                // with a deep-link CTA to Settings → Provider Keys, and skip the generic
                // `error = event.message` fallback to avoid double-surfacing.
                val keyError = parseUserKeyError(event.message)
                // Preserve partial content so users can read/copy what was received
                val partialContent = streamingBuffer.toString()
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        streamingContent = partialContent,
                        error = if (keyError != null) null else event.message,
                        retryInfo = null,
                        activeToolCalls = emptyList(),
                        streamingAttachments = emptyList(),
                        comparisonState = it.comparisonState.copy(
                            primaryIsStreaming = false,
                            secondaryIsStreaming = false,
                            primaryActiveToolCalls = emptyList(),
                            secondaryActiveToolCalls = emptyList(),
                        ),
                    )
                }
                if (keyError != null) {
                    _userKeyErrors.trySend(keyError)
                }
                // If the server already created a conversation, fetch whatever it persisted
                val conversationId = _uiState.value.conversationId
                if (conversationId != null) {
                    loadConversation(conversationId)
                }
            }
            is StreamEvent.Retrying -> {
                _uiState.update {
                    it.copy(
                        retryInfo = RetryInfo(
                            attempt = event.attempt,
                            maxAttempts = event.maxAttempts,
                        ),
                    )
                }
            }
            is StreamEvent.ToolCallStart -> {
                val newToolCall = ActiveToolCall(
                    id = event.toolCallId,
                    name = event.toolName,
                    input = event.input,
                )
                val isComparison = _uiState.value.comparisonState.isEnabled
                if (isComparison && modelDelegate.isSecondaryEvent(event.agentId)) {
                    _uiState.update {
                        it.copy(comparisonState = it.comparisonState.copy(
                            secondaryActiveToolCalls = it.comparisonState.secondaryActiveToolCalls + newToolCall,
                        ))
                    }
                } else if (isComparison) {
                    _uiState.update {
                        it.copy(comparisonState = it.comparisonState.copy(
                            primaryActiveToolCalls = it.comparisonState.primaryActiveToolCalls + newToolCall,
                        ))
                    }
                } else {
                    _uiState.update {
                        it.copy(activeToolCalls = it.activeToolCalls + newToolCall)
                    }
                }
            }
            is StreamEvent.ToolCallComplete -> {
                val isComparison = _uiState.value.comparisonState.isEnabled
                if (isComparison && modelDelegate.isSecondaryEvent(event.agentId)) {
                    _uiState.update { state ->
                        val updated = state.comparisonState.secondaryActiveToolCalls.map { tc ->
                            if (tc.id == event.toolCallId) tc.copy(isComplete = true, output = event.output) else tc
                        }
                        state.copy(comparisonState = state.comparisonState.copy(secondaryActiveToolCalls = updated))
                    }
                } else if (isComparison) {
                    _uiState.update { state ->
                        val updated = state.comparisonState.primaryActiveToolCalls.map { tc ->
                            if (tc.id == event.toolCallId) tc.copy(isComplete = true, output = event.output) else tc
                        }
                        state.copy(comparisonState = state.comparisonState.copy(primaryActiveToolCalls = updated))
                    }
                } else {
                    _uiState.update { state ->
                        val updated = state.activeToolCalls.map { tc ->
                            if (tc.id == event.toolCallId) {
                                tc.copy(isComplete = true, output = event.output)
                            } else {
                                tc
                            }
                        }
                        state.copy(activeToolCalls = updated)
                    }
                    // If this was a `subagent` tool_call, freeze its live trace —
                    // the child run is done; stop accumulating for that key.
                    subagentTraceDelegate.onParentToolCallResolved(event.toolCallId)
                }
            }
            is StreamEvent.AttachmentCreated -> {
                val attachment = Attachment(
                    fileId = event.fileId,
                    filename = event.filename,
                    filepath = event.filepath,
                    type = event.type,
                    toolCallId = event.toolCallId,
                    width = event.width,
                    height = event.height,
                    status = event.status,
                    text = event.text,
                    textFormat = event.textFormat,
                    previewError = event.previewError,
                )
                // Office-doc previews (v0.8.6) arrive twice per file_id (pending →
                // ready/failed) — route through the delegate for upsert-by-file_id +
                // poll-while-pending. Ordinary attachments keep the simple append path.
                if (ArtifactType.isOfficePreviewMime(event.type)) {
                    officePreviewDelegate.onAttachment(attachment)
                } else {
                    _uiState.update {
                        it.copy(streamingAttachments = it.streamingAttachments + attachment)
                    }
                }
            }
            is StreamEvent.Sync -> {
                // Resume snapshot: `aggregatedContent` is the authoritative state of
                // the response so far, so we REPLACE (not append) the streaming
                // pipeline's fields from it — both the text buffer and the tool-call
                // list. Any pendingEvents in the same frame arrive as their own
                // StreamEvents after this and fold on top via the normal handlers.
                if (lastErrorWasNetwork) {
                    lastErrorWasNetwork = false
                    cancelConnectivityObserver()
                }
                _uiState.update {
                    if (it.retryInfo != null) it.copy(retryInfo = null) else it
                }
                val textContent = event.aggregatedContent
                    .mapNotNull { it.text }
                    .joinToString("")
                streamingBuffer.clear()
                streamingBuffer.append(textContent)
                streamingBufferDirty = true

                // Rebuild active tool calls from the snapshot's tool_call parts so an
                // in-progress image gen (or any tool call) started before we resumed
                // still renders its live card. The same ActiveToolCall the live path
                // produces, so the existing StreamingToolCallCard / ImageGenCard render
                // it identically. A part with a non-blank output is already complete.
                val syncedToolCalls = event.aggregatedContent
                    .mapNotNull { part -> part.toolCall?.takeIf { !it.id.isNullOrBlank() } }
                    .map { tc ->
                        ActiveToolCall(
                            id = tc.id.orEmpty(),
                            name = tc.name.orEmpty(),
                            input = tc.args?.toString(),
                            isComplete = !tc.output.isNullOrBlank(),
                            output = tc.output,
                        )
                    }
                _uiState.update { it.copy(activeToolCalls = syncedToolCalls) }
                flushStreamingBuffer()
            }
            is StreamEvent.Step -> { /* no-op */ }
            is StreamEvent.ContextSummary -> {
                // Server compacted earlier turns into a summary. The compacted text is
                // persisted to the final message as a SUMMARY content part and rendered
                // there; nothing extra to do during streaming.
            }
            is StreamEvent.SubagentUpdate -> subagentTraceDelegate.onUpdate(event)
        }
    }

    private fun handleCreated(event: StreamEvent.Created) {
        if (isNewConversation) {
            viewModelScope.launch {
                val existingDraft = draftRepository.getDraft(NEW_CHAT_DRAFT_KEY)
                if (existingDraft != null) {
                    draftRepository.saveDraft(event.conversationId, existingDraft)
                    draftRepository.deleteDraft(NEW_CHAT_DRAFT_KEY)
                }
            }
        }
        if (lastErrorWasNetwork) {
            lastErrorWasNetwork = false
            cancelConnectivityObserver()
        }
        _uiState.update {
            if (it.retryInfo != null) {
                it.copy(conversationId = event.conversationId, retryInfo = null)
            } else {
                it.copy(conversationId = event.conversationId)
            }
        }
        if (isNewConversation) {
            // Stage the selection we actually sent so the about-to-be-created Chat(id) VM can
            // apply it directly instead of re-deriving it from a GET that races the server's
            // unawaited conversation save. Keyed by id, so a deferred nav (comparison-mode
            // branch) still picks it up. See NewChatSelectionHandoff.
            val sent = _uiState.value
            selectionHandoff.put(event.conversationId, sent.selectedEndpoint, sent.selectedModel)
            _uiState.update {
                if (it.pendingNavigationConversationId == null && !it.comparisonState.isEnabled) {
                    it.copy(pendingNavigationConversationId = event.conversationId)
                } else {
                    it
                }
            }
        }
        // Eagerly fetch and cache the conversation the server just created,
        // so it appears in the conversation list even if the stream fails later
        viewModelScope.launch {
            conversationRepository.getConversation(event.conversationId)
        }
    }

    private fun handleFinal(event: StreamEvent.Final) {
        stopStreamingUpdater()
        // The stream has ended: any office-doc attachment still `pending` (its
        // `ready` SSE update may never arrive once the run closes) now falls back
        // to polling GET /api/files/:id/preview. De-duped + bounded in the delegate.
        officePreviewDelegate.onStreamEnded()
        val isComparison = _uiState.value.comparisonState.isEnabled
        val conversationId = _uiState.value.conversationId
            ?: event.conversation?.conversationId
        val completedResponseText = if (isComparison) {
            modelDelegate.primaryComparisonBuffer.toString()
        } else {
            streamingBuffer.toString()
        }
        val shouldAutoRead = !isEditOrRegenerate
        if (isComparison) {
            val responseMessage = event.responseMessage ?: event.message
            val primaryContent = modelDelegate.primaryComparisonBuffer.toString()
            val secondaryContent = modelDelegate.secondaryComparisonBuffer.toString()
            _uiState.update {
                it.copy(
                    isStreaming = false,
                    streamingContent = "",
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                    conversationId = conversationId ?: it.conversationId,
                    comparisonState = it.comparisonState.copy(
                        primaryIsStreaming = false,
                        secondaryIsStreaming = false,
                        primaryStreamingContent = "",
                        secondaryStreamingContent = "",
                        primaryActiveToolCalls = emptyList(),
                        secondaryActiveToolCalls = emptyList(),
                        parallelMessageId = responseMessage?.messageId,
                        primaryFinalContent = primaryContent,
                        secondaryFinalContent = secondaryContent,
                    ),
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isStreaming = false,
                    streamingContent = "",
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                    conversationId = conversationId ?: it.conversationId,
                )
            }
        }
        val finalConversation = event.conversation
        // Belt-and-braces: if no handoff seeded the selection and the initial GET 404'd
        // against the created-before-save race, the conversation model is still unresolved.
        // The Final event carries the authoritative conversation, so re-derive from it here
        // rather than leaving a fallback guess on screen.
        if (!modelDelegate.conversationModelResolved && finalConversation != null) {
            val applied = applyConversationModel(finalConversation)
            Diag.i(
                tag = "ModelSel",
                attrs = mapOf("applied" to applied.toString()),
            ) { "handleFinal re-derived conversation model" }
        }
        // SECURITY: do not remove — temp-chat data-at-rest guard.
        // Temporary chats (v0.8.6) are kept out of normal history — the server excludes
        // them from the conversation list, so don't cache them to Room either (it would
        // leak a temp chat into the local list the server hides).
        val isTemporary = _uiState.value.isTemporaryChat || finalConversation?.isTemporary == true
        if (finalConversation?.conversationId != null && !isTemporary) {
            viewModelScope.launch {
                conversationRepository.saveConversation(finalConversation)
            }
        }
        if (conversationId != null) {
            if (isTemporary) {
                // SECURITY: do not remove — temp-chat data-at-rest guard.
                // Temp chats are never persisted: don't round-trip through the Room
                // read-through (which would upsert the message rows to disk). Drive the
                // display from the final event in memory instead. Title generation is
                // also skipped server-side for temp chats, so there's nothing to refresh.
                finalizeTemporaryChatDisplay(event)
            } else {
                loadConversation(conversationId)
                val currentTitle = _uiState.value.conversationTitle
                val needsTitle = currentTitle.isNullOrBlank() || currentTitle == "New Chat"
                if (isNewConversation && needsTitle && !titleGenerationRequested) {
                    titleGenerationRequested = true
                    generateAndSetTitle(conversationId)
                } else {
                    refreshConversationTitle(conversationId)
                }
            }
        }
        if (shouldAutoRead && completedResponseText.isNotBlank()) {
            ttsDelegate.maybeAutoReadResponse(completedResponseText)
        }
    }

    /**
     * Finalizes a temporary chat's display purely in memory, WITHOUT persisting to
     * Room. For a normal chat, [handleFinal] calls [loadConversation], which routes
     * through [MessageRepository.getMessages]'s read-through cache and upserts the
     * message rows to disk — for a temp chat that would leave the message content on
     * disk forever even though the conversation never appears in history. Instead we
     * merge the final request/response messages from the SSE event into the existing
     * in-memory list (replacing the optimistic user message by id) and recompute the
     * display path. Nothing touches the DB.
     */
    private fun finalizeTemporaryChatDisplay(event: StreamEvent.Final) {
        val finalMessages = listOfNotNull(event.requestMessage, event.responseMessage ?: event.message)
        if (finalMessages.isEmpty()) return
        _uiState.update { state ->
            val mergedMessages = mergeFinalMessagesInMemory(state.messages, finalMessages)
            state.copy(
                messages = mergedMessages,
                displayMessages = buildActiveMessagePath(mergedMessages, state.activeBranches),
                screenState = ChatScreenState.ACTIVE,
            )
        }
    }

    fun stopGeneration() {
        val conversationId = _uiState.value.conversationId ?: return
        streamJob?.cancel()
        stopStreamingUpdater()
        streamingBuffer.clear()
        streamingBufferDirty = false
        viewModelScope.launch {
            val abortResult = chatRepository.abortChat(conversationId)
            if (abortResult is Result.Error) {
                Logger.w(abortResult.exception) { "Failed to abort chat: ${abortResult.message}" }
            }
            // Clean up comparison state if active
            _uiState.update {
                it.copy(
                    isStreaming = false,
                    streamingContent = "",
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                    comparisonState = if (it.comparisonState.isEnabled) {
                        it.comparisonState.copy(
                            primaryIsStreaming = false,
                            secondaryIsStreaming = false,
                            primaryStreamingContent = "",
                            secondaryStreamingContent = "",
                            primaryActiveToolCalls = emptyList(),
                            secondaryActiveToolCalls = emptyList(),
                        )
                    } else {
                        it.comparisonState
                    },
                )
            }
            // Refresh messages from server so the message tree reflects
            // the partially-streamed response that was aborted.
            loadConversation(conversationId)
        }
    }

    fun continueGeneration() {
        if (_uiState.value.isStreaming) return
        val lastAiMessage = _uiState.value.displayMessages.lastOrNull {
            !it.message.isCreatedByUser
        } ?: return

        val parentUserMessage = _uiState.value.messages.find {
            it.messageId == lastAiMessage.message.parentMessageId
        } ?: return

        runWhenSendReady { continueGenerationNow(lastAiMessage.message, parentUserMessage) }
    }

    private fun continueGenerationNow(lastAiMessage: Message, parentUserMessage: Message) {
        isEditOrRegenerate = true
        prepareForStreaming()

        val isAgentContinue = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Logger.d { "continueGeneration: webSearch=$webSearchEnabled, ephemeralAgent=$ephemeralAgent" }
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val dispatch = currentDispatch()
            collectStreamSafely(
                chatRepository.startChat(
                    text = parentUserMessage.text,
                    conversationId = _uiState.value.conversationId,
                    endpoint = _uiState.value.selectedEndpoint,
                    endpointType = dispatch.endpointType,
                    key = dispatch.key,
                    modelDisplayLabel = dispatch.modelDisplayLabel,
                    model = _uiState.value.selectedModel,
                    parentMessageId = parentUserMessage.parentMessageId,
                    agentId = if (isAgentContinue) _uiState.value.selectedModel else null,
                    overrideParentMessageId = parentUserMessage.messageId,
                    responseMessageId = lastAiMessage.messageId,
                    isEdited = true,
                    isRegenerate = true,
                    isContinued = true,
                    webSearch = webSearchEnabled,
                    ephemeralAgent = ephemeralAgent,
                    isTemporary = _uiState.value.isTemporaryChat,
                ),
            )
        }
    }

    fun onPause() {
        wasStreaming = _uiState.value.isStreaming
        if (wasStreaming) {
            streamJob?.cancel()
            stopStreamingUpdater()
        }
    }

    fun onResume() {
        if (!wasStreaming) return
        wasStreaming = false

        val conversationId = _uiState.value.conversationId ?: return

        viewModelScope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    _uiState.update { it.copy(isStreaming = true) }
                    resumeStream(conversationId)
                } else {
                    _uiState.update {
                        it.copy(isStreaming = false, streamingContent = "")
                    }
                    loadConversation(conversationId)
                }
            } catch (e: Exception) {
                Logger.e(e) { "Could not resume stream" }
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        streamingContent = "",
                        error = "Could not resume stream",
                    )
                }
            }
        }
    }

    /**
     * Shared resume logic: clears the buffer, starts the updater, and launches
     * stream collection. Caller is responsible for setting any UI state fields
     * (e.g. isStreaming, error) before calling this.
     */
    private fun resumeStream(conversationId: String) {
        streamingBuffer.clear()
        streamingBufferDirty = false
        startStreamingUpdater()
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            collectStreamSafely(chatRepository.resumeStream(conversationId))
        }
    }

    private fun resumeActiveStreamIfNeeded(conversationId: String) {
        viewModelScope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    _uiState.update {
                        it.copy(
                            isStreaming = true,
                            screenState = ChatScreenState.ACTIVE,
                        )
                    }
                    resumeStream(conversationId)
                }
            } catch (e: Exception) {
                Logger.d(e) { "No active stream to resume for $conversationId" }
            }
        }
    }

    /**
     * Starts observing connectivity for auto-reconnect after a network error.
     * Cancels any existing observer first. The observer self-cancels after recovery fires.
     */
    private fun startConnectivityObserver() {
        connectivityJob?.cancel()
        connectivityJob = viewModelScope.launch {
            var wasConnected = true
            connectivityObserver.isConnected.collect { connected ->
                val recovered = !wasConnected && connected
                wasConnected = connected
                if (recovered) {
                    attemptNetworkRecovery()
                }
            }
        }
    }

    /** Cancels the connectivity observer and clears the network-error flag. */
    private fun cancelConnectivityObserver() {
        connectivityJob?.cancel()
        connectivityJob = null
    }

    /**
     * Called when network connectivity transitions from offline to online.
     * If the last stream ended due to a network error, attempts to resume it
     * or falls back to reloading the conversation from the server.
     */
    private fun attemptNetworkRecovery() {
        if (!lastErrorWasNetwork) return
        val state = _uiState.value
        val conversationId = state.conversationId ?: return
        if (state.isStreaming) return

        lastErrorWasNetwork = false
        cancelConnectivityObserver()
        Logger.d { "Network recovered, attempting to resume conversation $conversationId" }

        viewModelScope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    _uiState.update {
                        it.copy(
                            isStreaming = true,
                            error = null,
                            retryInfo = null,
                        )
                    }
                    resumeStream(conversationId)
                } else {
                    // Stream expired while offline — reload conversation from server
                    _uiState.update { it.copy(error = null, retryInfo = null) }
                    loadConversation(conversationId)
                }
            } catch (e: Exception) {
                Logger.w(e) { "Network recovery: could not check stream status" }
            }
        }
    }

    fun submitFeedback(messageId: String, rating: String?) {
        val conversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch {
            messageRepository.updateFeedback(conversationId, messageId, rating)
        }
    }

    fun startEditing(messageId: String) {
        val text = getMessageText(messageId)
        _uiState.update {
            it.copy(editingMessageId = messageId, editingText = text)
        }
    }

    fun onEditTextChanged(text: String) {
        _uiState.update { it.copy(editingText = text) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessageId = null, editingText = "") }
    }

    fun submitEdit() {
        val messageId = _uiState.value.editingMessageId ?: return
        val newText = _uiState.value.editingText.trim()
        if (newText.isBlank()) return

        _uiState.update { it.copy(editingMessageId = null, editingText = "") }
        editMessage(messageId, newText)
    }

    fun saveEditOnly() {
        val messageId = _uiState.value.editingMessageId ?: return
        val conversationId = _uiState.value.conversationId ?: return
        val newText = _uiState.value.editingText.trim()
        if (newText.isBlank()) return

        _uiState.update { it.copy(editingMessageId = null, editingText = "") }
        viewModelScope.launch {
            messageRepository.updateMessageText(conversationId, messageId, newText)
        }
    }

    fun onPendingNavigationHandled() {
        streamJob?.cancel()
        streamJob = null
        stopStreamingUpdater()
        roomObserverJob?.cancel()
        roomObserverJob = null
        _uiState.update { current ->
            ChatUiState(
                selectedEndpoint = current.selectedEndpoint,
                selectedModel = current.selectedModel,
                availableModels = current.availableModels,
                endpointConfigs = current.endpointConfigs,
                agents = current.agents,
                presets = current.presets,
                availablePrompts = current.availablePrompts,
                mcpServers = current.mcpServers,
                selectedMcpServerNames = current.selectedMcpServerNames,
                enabledTools = current.enabledTools,
                serverSttEnabled = current.serverSttEnabled,
                userName = current.userName,
                userAvatarUrl = current.userAvatarUrl,
                sharedLinksEnabled = current.sharedLinksEnabled,
            )
        }
        streamingBuffer.clear()
    }

    fun toggleTemporaryChat() {
        // Only togglable before the conversation exists. Once a temporary chat is
        // active the toggle is a read-only indicator — the server already created
        // it temporary, so flipping it off here would be misleading.
        if (_uiState.value.conversationId != null) return
        _uiState.update { it.copy(isTemporaryChat = !it.isTemporaryChat) }
    }

    fun refreshMessages() {
        val conversationId = _uiState.value.conversationId ?: return
        // SECURITY: do not remove — temp-chat data-at-rest guard.
        // Temp chats aren't persisted server- or client-side; a pull-to-refresh would
        // call refreshMessages → replaceAllForConversation, writing the temp message rows
        // to Room. Skip — there's nothing to refresh for a temporary chat.
        if (_uiState.value.isTemporaryChat) return
        if (_uiState.value.isRefreshingMessages) return
        _uiState.update { it.copy(isRefreshingMessages = true) }
        viewModelScope.launch {
            messageRepository.refreshMessages(conversationId)
            loadConversation(conversationId)
            _uiState.update { it.copy(isRefreshingMessages = false) }
        }
    }

    private fun loadFlags() {
        // startupConfig-driven flags (UI-only toggles, not permission gates).
        viewModelScope.launch {
            configRepository.startupConfig.collect { config ->
                _uiState.update {
                    it.copy(sharedLinksEnabled = config?.sharedLinksEnabled ?: false)
                }
            }
        }
        // Feature gates. The effective rule mirrors web: `interface.* flag AND role permission`.
        // Combining the two flows lets us AND them in one place. Both inputs fail open:
        //  - Role permissions: null role (not loaded) → true; missing type/action → true
        //    (see UserRolePermissions.hasAccess).
        //  - Interface flags: an absent `interface` block (older backend) → null → treated
        //    as enabled, so we never hide a control just because config is missing.
        // The `interface.*` booleans (modelSelect/parameters/presets/multiConvo/temporaryChat/
        // runCode/webSearch/fileSearch/bookmarks) default to true in InterfaceConfig, so an
        // omitted individual flag is also fail-open.
        viewModelScope.launch {
            combine(
                roleRepository.userPermissions,
                configRepository.startupConfig,
            ) { role, config ->
                role to config?.interfaceConfig
            }.distinctUntilChanged().collect { (role, iface) ->
                // Effective gate = role permission AND interface flag, both fail-open
                // (null role → permissive; absent/omitted flag → enabled).
                fun gate(type: PermissionType, action: Permission, flag: (InterfaceConfig) -> Boolean?) =
                    role.hasAccessOrPermissive(type, action) && (iface?.let(flag) ?: true)
                _uiState.update {
                    it.copy(
                        promptsEnabled = role.hasAccessOrPermissive(PermissionType.PROMPTS, Permission.USE),
                        promptsCreateEnabled = role.hasAccessOrPermissive(PermissionType.PROMPTS, Permission.CREATE),
                        agentsEnabled = role.hasAccessOrPermissive(PermissionType.AGENTS, Permission.USE),
                        agentsCreateEnabled = role.hasAccessOrPermissive(PermissionType.AGENTS, Permission.CREATE),
                        mcpServersEnabled = role.hasAccessOrPermissive(PermissionType.MCP_SERVERS, Permission.USE),
                        multiConvoEnabled = gate(PermissionType.MULTI_CONVO, Permission.USE) { it.multiConvo },
                        temporaryChatEnabled = gate(PermissionType.TEMPORARY_CHAT, Permission.USE) { it.temporaryChat },
                        webSearchEnabled = gate(PermissionType.WEB_SEARCH, Permission.USE) { it.webSearch },
                        runCodeEnabled = gate(PermissionType.RUN_CODE, Permission.USE) { it.runCode },
                        fileSearchEnabled = gate(PermissionType.FILE_SEARCH, Permission.USE) { it.fileSearch },
                        bookmarksEnabled = gate(PermissionType.BOOKMARKS, Permission.USE) { it.bookmarks },
                        // Interface-only gates (no role permission counterpart on web).
                        modelSelectEnabled = iface?.modelSelect ?: true,
                        parametersEnabled = iface?.parameters ?: true,
                        // Web gates the presets menu on `presets && modelSelect` (Header.tsx).
                        presetsEnabled = (iface?.presets ?: true) && (iface?.modelSelect ?: true),
                    )
                }
            }
        }
    }

    /**
     * Fetches the server's upload config once so the attach controls can be gated per
     * endpoint (see [ChatUiState.fileUploadEnabled]). Fails open: on error the config
     * stays null and attaching remains enabled.
     */
    private fun loadFileConfig() {
        viewModelScope.launch {
            fileRepository.getFileConfig().getOrNull()?.let { config ->
                _uiState.update { it.copy(fileUploadConfig = config) }
            }
        }
    }

    /**
     * Suspends until [ChatUiState.isSendReady] becomes true, up to [timeoutMs]. Returns
     * true if the state became ready; false on timeout. Used as a pre-flight guard on all
     * send variants to avoid the cold-start race where endpoint/config hasn't arrived yet
     * and firing `chatRepository.startChat(...)` would produce a mislabeled 403.
     *
     * 3 s chosen to be snappier than the role-load timeout (5 s) since this only needs
     * one of the async inits (role OR availableModels) to complete enough to satisfy
     * `isSendReady` — usually both have landed by the time a human can tap send.
     */
    private suspend fun awaitSendReady(timeoutMs: Long = SEND_READY_TIMEOUT_MS): Boolean {
        if (_uiState.value.isSendReady) return true
        return withTimeoutOrNull(timeoutMs) {
            _uiState.map { it.isSendReady }.distinctUntilChanged().first { it }
        } != null
    }

    /**
     * Guard for each of the four send variants (send / edit / regenerate / continue).
     * Runs a synchronous pre-flight that fails fast on user-input errors (e.g., no model
     * selected, agents denied with role already loaded) so the user isn't made to wait
     * for the readiness timeout just to be told something they could have acted on
     * immediately. Otherwise, awaits readiness up to 3 s and falls back to a
     * selection-aware availability message if the wait times out.
     */
    private fun runWhenSendReady(action: () -> Unit) {
        val current = _uiState.value
        preflightSendBlockReason(current)?.let { reason ->
            _uiState.update { it.copy(sendBlockReason = reason, showModelSheet = true) }
            return
        }
        if (current.isSendReady) {
            action()
            return
        }
        viewModelScope.launch {
            if (awaitSendReady()) {
                action()
            } else {
                _uiState.update {
                    it.copy(
                        sendBlockReason = sendReadinessTimeoutReason(it),
                        showModelSheet = true,
                    )
                }
            }
        }
    }

    /**
     * Synchronous pre-flight. Returns a typed reason when sending is guaranteed
     * to fail regardless of outstanding async inits; null when we still need to wait
     * for the readiness signal. This keeps "no model selected" and "agents denied"
     * instantaneous instead of waiting out the readiness timeout.
     */
    private fun preflightSendBlockReason(state: ChatUiState): SendBlockReason? {
        if (state.selectedModel == null) {
            return if (state.selectedEndpoint == EndpointConstants.AGENTS) {
                SendBlockReason.SelectAgent
            } else {
                SendBlockReason.SelectModel
            }
        }
        if (state.selectedEndpoint == EndpointConstants.AGENTS && !state.agentsEnabled) {
            return SendBlockReason.AgentsUnavailable
        }
        return null
    }

    /**
     * Fallback for when readiness didn't resolve within the timeout. At this point the
     * async model list is most likely in its final shape, so we can confidently flag
     * stale selections that aren't in the available models.
     */
    private fun sendReadinessTimeoutReason(state: ChatUiState): SendBlockReason {
        if (state.selectedEndpoint == EndpointConstants.AGENTS) {
            return SendBlockReason.AgentNotAvailable
        }
        val modelsForEndpoint = state.availableModels[state.selectedEndpoint].orEmpty()
        val selectedModel = state.selectedModel
        return if (selectedModel != null && selectedModel !in modelsForEndpoint) {
            SendBlockReason.ModelNotAvailable
        } else {
            SendBlockReason.ModelLoadFailed
        }
    }

    /** Opens the model-selector sheet. Called when the user taps the model chip. */
    fun openModelSheet() {
        _uiState.update { it.copy(showModelSheet = true) }
    }

    /** Dismisses the model-selector sheet. Called on sheet dismiss and model selection. */
    fun dismissModelSheet() {
        _uiState.update { it.copy(showModelSheet = false) }
    }

    private fun loadUserProfile() {
        viewModelScope.launch {
            when (val result = userRepository.getUser()) {
                is Result.Success -> {
                    val user = result.data
                    _uiState.update {
                        it.copy(
                            userName = user.name ?: user.username,
                            userAvatarUrl = user.avatar,
                        )
                    }
                }
                is Result.Error -> {
                    Logger.d(result.exception) { "Failed to load user profile: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun dismissSendBlockReason() {
        _uiState.update { it.copy(sendBlockReason = null) }
    }

    /**
     * Opens the full-screen media viewer at [url]. The swipeable list is the image set of the
     * current branch, computed once here from a state snapshot (never on the streaming hot path).
     * If [url] isn't in the derived list (an edge case), it opens as a single item rather than
     * silently jumping to index 0. Reads only state — no Room write, no `activeBranches` mutation —
     * so it never trips the streaming invariant.
     */
    fun openMedia(url: String) {
        if (url.isBlank()) return
        val state = uiState.value
        val items = extractBranchMedia(
            displayMessages = state.displayMessages,
            activeToolCalls = state.activeToolCalls,
            streamingAttachments = state.streamingAttachments,
            baseUrl = state.serverUrl,
        )
        val index = items.indexOfFirst { it.url == url }
        val preview = if (index >= 0) {
            MediaPreviewState(items = items, initialIndex = index)
        } else {
            MediaPreviewState(items = listOf(MediaItem(url = url, contentDescription = "")), initialIndex = 0)
        }
        _uiState.update { it.copy(mediaPreview = preview) }
    }

    fun closeMedia() {
        _uiState.update { it.copy(mediaPreview = null) }
    }

    override fun onCleared() {
        super.onCleared()
        voiceDelegate.release()
        ttsDelegate.release()
        officePreviewDelegate.cancelPolls()
    }

    // ── Delegated public API ────────────────────────────────────────

    // Search
    fun openSearch() = searchDelegate.openSearch()
    fun closeSearch() = searchDelegate.closeSearch()
    fun onSearchQueryChanged(query: String) = searchDelegate.onSearchQueryChanged(query)
    fun nextSearchMatch() = searchDelegate.nextSearchMatch()
    fun previousSearchMatch() = searchDelegate.previousSearchMatch()
    fun onSearchScrollHandled() = searchDelegate.onSearchScrollHandled()

    // Conversation actions
    fun showRenameDialog() = conversationActionsDelegate.showRenameDialog()
    fun dismissRenameDialog() = conversationActionsDelegate.dismissRenameDialog()
    fun renameConversation(newTitle: String) = conversationActionsDelegate.renameConversation(newTitle)
    fun showDeleteConfirmation() = conversationActionsDelegate.showDeleteConfirmation()
    fun dismissDeleteConfirmation() = conversationActionsDelegate.dismissDeleteConfirmation()
    fun deleteConversation() = conversationActionsDelegate.deleteConversation()
    fun archiveConversation() = conversationActionsDelegate.archiveConversation()
    fun duplicateConversation() = conversationActionsDelegate.duplicateConversation()
    fun onDuplicatedConversationHandled() = conversationActionsDelegate.onDuplicatedConversationHandled()
    fun shareConversation() = conversationActionsDelegate.shareConversation()
    fun onShareLinkHandled() = conversationActionsDelegate.onShareLinkHandled()
    fun showForkOptions(messageId: String) = conversationActionsDelegate.showForkOptions(messageId)
    fun dismissForkOptions() = conversationActionsDelegate.dismissForkOptions()
    fun forkFromMessage(messageId: String, option: String, splitAtTarget: Boolean = false) =
        conversationActionsDelegate.forkFromMessage(messageId, option, splitAtTarget)
    fun onForkedConversationHandled() = conversationActionsDelegate.onForkedConversationHandled()

    // TTS
    fun readAloud(messageId: String) = ttsDelegate.readAloud(messageId)
    fun stopReading() = ttsDelegate.stopReading()

    // Voice input
    fun startRecording() = voiceDelegate.startRecording()
    fun stopRecording() = voiceDelegate.stopRecording()
    fun cancelRecording() = voiceDelegate.cancelRecording()
    fun onDeviceSpeechResult(transcribedText: String) = voiceDelegate.onDeviceSpeechResult(transcribedText)

    // File attachments
    fun onFilesSelected(platformRefs: List<Any>) = fileDelegate.onFilesSelected(platformRefs)
    fun removeFile(file: AttachedFile) = fileDelegate.removeFile(file)
    fun retryUpload(file: AttachedFile) = fileDelegate.retryUpload(file)

    // Presets and prompts
    fun savePreset(name: String) = presetPromptDelegate.savePreset(name)
    fun loadPreset(displayData: PresetDisplayData) = presetPromptDelegate.loadPreset(displayData)
    fun deletePreset(presetId: String) = presetPromptDelegate.deletePreset(presetId)
    fun editPreset(preset: Preset) = presetPromptDelegate.editPreset(preset)
    fun handlePromptMention(displayData: PromptMentionDisplayData) = presetPromptDelegate.handlePromptMention(displayData)
    fun handleSlashCommand(displayData: PromptMentionDisplayData) = presetPromptDelegate.handleSlashCommand(displayData)

    // Favorites (v0.8.5)
    fun toggleAgentFavorite(agentId: String) = favoritesDelegate.toggleAgent(agentId)
    fun toggleModelFavorite(endpoint: String, model: String) = favoritesDelegate.toggleModel(endpoint, model)

    // Model selection and comparison
    fun onModelSelected(endpoint: String, model: String) = modelDelegate.onModelSelected(endpoint, model)
    fun toggleComparison() = modelDelegate.toggleComparison()
    fun setSecondaryModel(endpoint: String, model: String) = modelDelegate.setSecondaryModel(endpoint, model)
    fun getSecondaryModelDisplayName(): String? = modelDelegate.getSecondaryModelDisplayName()
    fun toggleMcpServer(serverName: String) = modelDelegate.toggleMcpServer(serverName)
    fun toggleTool(toolName: String) = modelDelegate.toggleTool(toolName)
    fun showModelParameters() = modelDelegate.showModelParameters()
    fun hideModelParameters() = modelDelegate.hideModelParameters()
    fun updateModelParameters(parameters: ModelParameters) = modelDelegate.updateModelParameters(parameters)

    fun branchFromComparison(agentId: String) {
        val messageId = _uiState.value.comparisonState.parallelMessageId ?: return
        val conversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch {
            try {
                messageRepository.branchMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    agentId = agentId,
                )
                // Disable comparison and continue with the branched response
                _uiState.update {
                    it.copy(comparisonState = ComparisonState())
                }
                // Trigger deferred navigation for new chats that skipped navigation during comparison
                val cid = _uiState.value.conversationId
                if (cid != null && _uiState.value.pendingNavigationConversationId == null) {
                    _uiState.update { it.copy(pendingNavigationConversationId = cid) }
                }
                // Refresh messages to show the branched message
                loadConversation(conversationId)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to branch comparison message" }
                _uiState.update {
                    it.copy(error = "Failed to continue with selected response")
                }
            }
        }
    }
}
