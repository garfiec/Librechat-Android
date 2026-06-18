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
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.Preset
import com.garfiec.librechat.core.model.config.InterfaceConfig
import com.garfiec.librechat.core.model.error.UserKeyError
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.hasAccessOrPermissive
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.core.ui.media.MediaItem
import com.garfiec.librechat.core.ui.media.MediaPreviewState
import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.components.ParsedMarkdownCache
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.util.NEW_CHAT_DRAFT_KEY
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import com.garfiec.librechat.feature.chat.util.extractBranchMedia
import com.garfiec.librechat.feature.chat.viewmodel.delegate.ComparisonModeDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.ConversationActionsDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.EndpointKeyStatusDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.FavoritesDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.InConversationSearchDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.MessageEditingDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.MessageQueueDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.MessageTreeDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.ModelSelectionDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.OfficePreviewDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PlatformDelegateFactory
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PresetPromptDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.SendCompletionDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.StreamingManagerDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.SubagentTraceDelegate
import com.garfiec.librechat.feature.chat.viewmodel.delegate.toFileReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    private val requestBuilder = ChatRequestBuilder(stateHandle)
    private val treeDelegate = MessageTreeDelegate(stateHandle)
    private val comparisonDelegate = ComparisonModeDelegate(
        stateHandle = stateHandle,
        messageRepository = messageRepository,
        reloadConversation = ::loadConversation,
    )
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
    private val completionDelegate = SendCompletionDelegate(
        stateHandle = stateHandle,
        conversationRepository = conversationRepository,
        draftRepository = draftRepository,
        modelDelegate = modelDelegate,
        treeDelegate = treeDelegate,
        tts = ttsDelegate,
        selectionHandoff = selectionHandoff,
        reloadConversation = ::loadConversation,
    )

    private val queueDelegate = MessageQueueDelegate(
        stateHandle = stateHandle,
        // Drained items send with their snapshotted config but LIVE lineage. We first wait for
        // the previous reply to settle into the tree (the Final-triggered Room reload is async),
        // so the optimistic insert chains onto the freshly-finalized assistant message rather
        // than the still-optimistic user turn. No upload-wait is needed — attachments were
        // already resolved to FileReferences at queue time.
        sendWithSpec = { spec, awaitSettle ->
            viewModelScope.launch {
                if (awaitSettle) awaitReplySettled()
                runWhenSendReady { doSendWithSpec(spec) }
            }
        },
    )

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

    private var roomObserverJob: Job? = null

    private val streamingManager = StreamingManagerDelegate(
        stateHandle = stateHandle,
        chatRepository = chatRepository,
        connectivityObserver = connectivityObserver,
        comparisonDelegate = comparisonDelegate,
        subagentTraceDelegate = subagentTraceDelegate,
        officePreviewDelegate = officePreviewDelegate,
        completionDelegate = completionDelegate,
        queueDelegate = queueDelegate,
        emitUserKeyError = { _userKeyErrors.trySend(it) },
        reloadConversation = ::loadConversation,
        isNewConversation = { isNewConversation },
        isHandedOffNewChat = { isHandedOffNewChat },
    )

    private val editingDelegate = MessageEditingDelegate(
        stateHandle = stateHandle,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        treeDelegate = treeDelegate,
        streamingManager = streamingManager,
        requestBuilder = requestBuilder,
        getMessageText = ::getMessageText,
        runWhenSendReady = ::runWhenSendReady,
    )

    companion object {
        /** Timeout for the pre-send "is the endpoint/config ready" await. Snappier than the
         *  5 s role-load timeout because this only needs one of role OR availableModels to
         *  satisfy the check. */
        private const val SEND_READY_TIMEOUT_MS = 3_000L

        /** Upper bound on waiting for a finished reply to land in the tree before draining the
         *  next queued message. Generous so a slow post-Final reload still chains correctly. */
        private const val REPLY_SETTLE_TIMEOUT_MS = 8_000L
    }

    /** True when this ViewModel was opened for a brand-new chat (no conversationId from navigation). */
    private val isNewConversation: Boolean

    /**
     * True when this ViewModel took the [NewChatSelectionHandoff] for a conversation just
     * created from the NewChat landing. Navigation to Chat(id) fires at the `created` SSE
     * event and resets the landing VM, so THIS VM is the one whose resumed stream sees the
     * first Final — with [isNewConversation] false. This flag keeps new-chat-only work
     * (title generation) running for the handed-off chat.
     */
    private val isHandedOffNewChat: Boolean

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
            val handoff = selectionHandoff.take(conversationId)
            isHandedOffNewChat = handoff != null
            if (handoff != null) {
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
            streamingManager.resumeActiveStreamIfNeeded(conversationId)
        } else {
            isHandedOffNewChat = false
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
                val applied = modelDelegate.applyConversationModel(conversation)
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

    fun switchBranch(parentMessageId: String, siblingIndex: Int) =
        treeDelegate.switchBranch(parentMessageId, siblingIndex)

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
        // While editing a queued item the composer holds that item, not the persisted draft —
        // don't overwrite the on-disk new-message draft (it's restored on commit/cancel).
        if (_uiState.value.isEditingQueued) return
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
        // In queued-edit mode the composer holds a queued item, not a new message — a send
        // (e.g. voice auto-send, which bypasses the UPDATE button) commits the edit instead of
        // live-sending, so the edit session is never orphaned.
        if (_uiState.value.isEditingQueued) {
            commitQueuedEdit()
            return
        }
        if (_uiState.value.isStreaming) return
        val text = _uiState.value.inputText.trim()
        withUploadGate(text) { runWhenSendReady { sendNow(it) } }
    }

    /**
     * Queues a follow-up message while a reply streams, to auto-send (FIFO) when the current
     * reply completes. Only valid mid-stream and on an existing conversation (the queue
     * affordance is hidden on the landing/new-chat screen). Shares [sendMessage]'s upload-wait
     * gate so a queued message with a still-uploading attachment captures it.
     */
    fun queueMessage() {
        if (!_uiState.value.isStreaming) return
        if (_uiState.value.conversationId == null) return
        val text = _uiState.value.inputText.trim()
        withUploadGate(text) { enqueueNow(it) }
    }

    /**
     * Runs [action] once any pending file uploads have finished, guarding against a double-send
     * while a previous wait is still in flight. Shared by the live-send and queue paths so the
     * upload-wait semantics live in one place.
     */
    private fun withUploadGate(text: String, action: (String) -> Unit) {
        if (fileDelegate.pendingUploadSendJob?.isActive == true) return
        if (fileDelegate.hasPendingUploads()) {
            Logger.d { "withUploadGate: waiting for pending upload(s) to complete" }
            fileDelegate.pendingUploadSendJob = viewModelScope.launch {
                fileDelegate.waitForUploadsAndSend(text) { action(it) }
            }
            return
        }
        action(text)
    }

    private fun enqueueNow(text: String) {
        val spec = buildSendSpec(text) ?: return
        queueDelegate.enqueue(spec)
        clearComposer()
        // If the in-flight reply already finished, no Final will arrive to drain this — kick it now.
        tryResumeDrain()
    }

    /** Resumes FIFO draining when the queue is idle (not mid-stream, not paused). No-op otherwise;
     *  [MessageQueueDelegate.drainNext] additionally guards the paused / editing / empty cases. */
    private fun tryResumeDrain() {
        if (!_uiState.value.isStreaming && !_uiState.value.isQueuePaused) {
            queueDelegate.drainNext(awaitSettle = false)
        }
    }

    /**
     * Tap a queued ghost bubble: enter queued-edit mode. Stashes the current new-message draft,
     * pulls the item OUT of the queue, and loads its text + attachments + model/tools/params into
     * the composer for editing. Commit ([commitQueuedEdit]) or cancel ([cancelQueuedEdit]) puts the
     * item back in its slot and restores the stashed draft. Ignored if already editing one.
     */
    fun editQueued(localId: String) {
        if (_uiState.value.isEditingQueued) return
        val taken = queueDelegate.takeForEdit(localId) ?: return
        val stashed = captureComposer()
        applyComposer(taken.value.toComposerSnapshot())
        _uiState.update {
            it.copy(
                editingQueuedItem = QueuedEditSession(
                    original = taken.value,
                    originalIndex = taken.index,
                    stashed = stashed,
                ),
            )
        }
    }

    /** "Update" in queued-edit mode: re-queue the edited item at its original slot (or drop it if
     *  emptied), then restore the stashed new-message draft. Waits for any attachment added during
     *  the edit to finish uploading (same gate as send/queue) so it isn't silently dropped. */
    fun commitQueuedEdit() {
        val session = _uiState.value.editingQueuedItem ?: return
        withUploadGate(_uiState.value.inputText.trim()) { text ->
            // The upload wait is async — bail if the edit was cancelled (or replaced) meanwhile,
            // so we don't reinsert a duplicate after cancelQueuedEdit already restored the item.
            if (_uiState.value.editingQueuedItem != session) return@withUploadGate
            val edited = buildSendSpec(text)?.copy(localId = session.original.localId)
            if (edited != null) {
                queueDelegate.reinsert(session.originalIndex, edited)
            } else {
                // Composer emptied → treat as delete; the item is simply not put back.
                queueDelegate.clearPauseIfEmpty()
            }
            finishQueuedEdit(session)
        }
    }

    /** "Cancel edit": discard composer changes, restore the original item to its slot unchanged,
     *  and bring back the stashed new-message draft. */
    fun cancelQueuedEdit() {
        val session = _uiState.value.editingQueuedItem ?: return
        queueDelegate.reinsert(session.originalIndex, session.original)
        finishQueuedEdit(session)
    }

    private fun finishQueuedEdit(session: QueuedEditSession) {
        applyComposer(session.stashed)
        _uiState.update { it.copy(editingQueuedItem = null) }
        // Draining was frozen during the edit; resume it now if the queue is idle (a reply may
        // have finished while editing).
        tryResumeDrain()
    }

    fun cancelQueued(localId: String) {
        // Ignore ghost ×/reorder while an edit is in flight, so the queue can't shift under the
        // session's captured originalIndex.
        if (_uiState.value.isEditingQueued) return
        queueDelegate.cancel(localId)
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        if (_uiState.value.isEditingQueued) return
        queueDelegate.reorder(fromIndex, toIndex)
    }

    /** "Send queued" control after a Stop/error pause: lift the pause and resume draining. */
    fun sendQueuedNow() = queueDelegate.resume()

    /** Snapshots the editable composer surface (the new-message draft) for stashing during an edit. */
    private fun captureComposer(): ComposerSnapshot {
        val state = _uiState.value
        return ComposerSnapshot(
            text = state.inputText,
            attachments = fileDelegate.attachedFiles.value,
            endpoint = state.selectedEndpoint,
            model = state.selectedModel,
            enabledTools = state.enabledTools,
            mcpServerNames = state.selectedMcpServerNames,
            modelParameters = state.modelParameters,
        )
    }

    /** Writes a [ComposerSnapshot] back onto the composer (text, attachments, model, tools, params).
     *  Sets [ChatUiState.inputText] directly rather than via [onInputChanged] so swapping composer
     *  contents for an edit never overwrites the persisted on-disk new-message draft. */
    private fun applyComposer(snapshot: ComposerSnapshot) {
        fileDelegate.restoreAttachedFiles(snapshot.attachments)
        _uiState.update {
            it.copy(
                inputText = snapshot.text,
                selectedEndpoint = snapshot.endpoint,
                selectedModel = snapshot.model,
                enabledTools = snapshot.enabledTools,
                selectedMcpServerNames = snapshot.mcpServerNames,
                modelParameters = snapshot.modelParameters,
            )
        }
    }

    /**
     * Snapshots the current send config into a [QueuedMessage]. Used both for a normal send
     * (fired immediately) and for queueing (fired later, unchanged by intervening config edits).
     * Returns null when there is nothing to send (blank text and no uploaded files).
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun buildSendSpec(text: String): QueuedMessage? {
        // Snapshot the uploaded AttachedFiles (not just FileReferences) so a queued item can
        // round-trip losslessly back into the composer on edit — keeping its local-uri thumbnail.
        val allFiles = fileDelegate.attachedFiles.value
        val files = allFiles.filter { it.fileId != null }
        // Surface attachments excluded from the send (still uploading or failed) so a dropped
        // file leaves a diagnostic trail rather than vanishing silently.
        val dropped = allFiles.filter { it.fileId == null }
        if (dropped.isNotEmpty()) {
            Logger.w {
                "buildSendSpec: ${dropped.size} attachment(s) not yet uploaded, excluded from send: " +
                    dropped.joinToString { it.name }
            }
        }
        if (text.isBlank() && files.isEmpty()) return null
        val state = _uiState.value
        val isAgent = state.selectedEndpoint == EndpointConstants.AGENTS
        return QueuedMessage(
            localId = Uuid.random().toString(),
            text = text,
            attachments = files,
            endpoint = state.selectedEndpoint,
            model = state.selectedModel,
            agentId = if (isAgent) state.selectedModel else null,
            enabledTools = state.enabledTools,
            mcpServerNames = state.selectedMcpServerNames,
            modelParameters = state.modelParameters,
            ephemeralAgent = requestBuilder.buildEphemeralAgent(),
            dispatch = requestBuilder.currentDispatch(),
            isTemporary = state.isTemporaryChat,
        )
    }

    private fun sendNow(text: String) {
        val spec = buildSendSpec(text) ?: return
        doSendWithSpec(spec, clearComposerOnSend = true)
    }

    /**
     * Suspends until the previous reply has settled into the message tree: streaming is over and
     * the active path ends in an assistant message (the post-Final Room reload has landed). Used
     * before draining a queued follow-up so its optimistic insert chains onto that reply. Bounded
     * by [REPLY_SETTLE_TIMEOUT_MS]; on timeout (e.g. a failed reload) we proceed best-effort.
     */
    private suspend fun awaitReplySettled() {
        withTimeoutOrNull(REPLY_SETTLE_TIMEOUT_MS) {
            _uiState.first { state ->
                !state.isStreaming &&
                    state.displayMessages.lastOrNull()?.message?.isCreatedByUser == false
            }
        }
    }

    /** Clears the input, its persisted draft, and any attached files. */
    private fun clearComposer() {
        val draftKey = _uiState.value.conversationId ?: NEW_CHAT_DRAFT_KEY
        _uiState.update { it.copy(inputText = "") }
        viewModelScope.launch { draftRepository.deleteDraft(draftKey) }
        fileDelegate.clearAttachedFiles()
    }

    /**
     * Sends one message from a [QueuedMessage] config snapshot. The config (endpoint/model/
     * tools/webSearch/attachments/dispatch/ephemeralAgent) comes from the spec, but the
     * lineage — conversationId, parentMessageId, and the minted optimistic user-message id —
     * is recomputed from the *current* tree, so a drained item chains onto the freshly-
     * finalized turn.
     *
     * [clearComposerOnSend] clears the composer only once the streaming guard has passed — set
     * true on the live-send path (so a lost readiness race can't wipe an unsent message) and
     * false for drains (which must leave the user's in-progress composer untouched).
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun doSendWithSpec(spec: QueuedMessage, clearComposerOnSend: Boolean = false) {
        val fileRefs = spec.attachments.map { it.toFileReference() }
        val hasFiles = fileRefs.isNotEmpty()
        val messageText = spec.text
        if ((messageText.isBlank() && !hasFiles) || _uiState.value.isStreaming) return
        // Guard passed: safe to clear the composer for a live send without risking message loss.
        if (clearComposerOnSend) clearComposer()

        val conversationId = _uiState.value.conversationId
        val lastMessageId = _uiState.value.displayMessages.lastOrNull()?.message?.messageId

        // Add optimistic user message to display immediately
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
        val isNewChat = conversationId == null
        _uiState.update {
            val updatedMessages = it.messages + optimisticMessage
            val updatedDisplay = buildActiveMessagePath(updatedMessages, it.activeBranches, optimisticMessage.messageId)
            it.copy(
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
        streamingManager.beginStreaming(isEdit = false)

        val isAgent = spec.endpoint == EndpointConstants.AGENTS
        Logger.d {
            "sendMessage: webSearch=${spec.modelParameters.webSearch}, " +
                "endpoint=${spec.endpoint}, " +
                "model=${spec.model}, " +
                "files=${fileRefs.size}, " +
                "ephemeralAgent=${spec.ephemeralAgent}"
        }

        // Resolve effective endpoint/agentId for comparison mode.
        // All requests go through api/agents/chat/{endpoint} — the server's
        // middleware creates ephemeral agents for non-agent endpoints, so no
        // swapping is needed. Just keep the primary's original endpoint.
        val effectiveEndpoint = spec.endpoint
        val effectiveAgentId = if (isAgent) spec.agentId else null
        comparisonDelegate.onSendStart()

        val effectiveAddedConvo = comparisonDelegate.buildAddedConvo(parentMessageId = lastMessageId)
        val stream = chatRepository.startChat(
            text = messageText,
            conversationId = conversationId,
            endpoint = effectiveEndpoint,
            endpointType = spec.dispatch.endpointType,
            key = spec.dispatch.key,
            modelDisplayLabel = spec.dispatch.modelDisplayLabel,
            model = spec.model,
            userMessageId = optimisticMessage.messageId,
            parentMessageId = lastMessageId,
            agentId = effectiveAgentId,
            webSearch = spec.modelParameters.webSearch,
            files = fileRefs.takeIf { it.isNotEmpty() },
            addedConvo = effectiveAddedConvo,
            ephemeralAgent = spec.ephemeralAgent,
            isTemporary = spec.isTemporary,
        )
        streamingManager.launchStream(stream) {
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

    fun editMessage(messageId: String, newText: String) {
        if (_uiState.value.isEditingQueued) return
        editingDelegate.editMessage(messageId, newText)
    }

    fun regenerateMessage(messageId: String) {
        if (_uiState.value.isEditingQueued) return
        editingDelegate.regenerateMessage(messageId)
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

    fun stopGeneration() = streamingManager.stopGeneration()

    fun continueGeneration() {
        if (_uiState.value.isEditingQueued) return
        editingDelegate.continueGeneration()
    }

    fun onPause() = streamingManager.onPause()

    fun onResume() = streamingManager.onResume()

    fun submitFeedback(messageId: String, rating: String?) {
        val conversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch {
            messageRepository.updateFeedback(conversationId, messageId, rating)
        }
    }

    fun startEditing(messageId: String) {
        // Don't start a tree-message edit while a queued item occupies the composer (it would
        // build its resubmit from the queued item's loaded model/tools).
        if (_uiState.value.isEditingQueued) return
        editingDelegate.startEditing(messageId)
    }

    fun onEditTextChanged(text: String) = editingDelegate.onEditTextChanged(text)

    fun cancelEditing() = editingDelegate.cancelEditing()

    fun submitEdit() = editingDelegate.submitEdit()

    fun saveEditOnly() = editingDelegate.saveEditOnly()

    fun onPendingNavigationHandled() {
        streamingManager.reset()
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
    }

    fun toggleTemporaryChat() = treeDelegate.toggleTemporaryChat()

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
    fun toggleComparison() = comparisonDelegate.toggleComparison()
    fun setSecondaryModel(endpoint: String, model: String) = comparisonDelegate.setSecondaryModel(endpoint, model)
    fun getSecondaryModelDisplayName(): String? = comparisonDelegate.getSecondaryModelDisplayName()
    fun toggleMcpServer(serverName: String) = modelDelegate.toggleMcpServer(serverName)
    fun toggleTool(toolName: String) = modelDelegate.toggleTool(toolName)
    fun showModelParameters() = modelDelegate.showModelParameters()
    fun hideModelParameters() = modelDelegate.hideModelParameters()
    fun updateModelParameters(parameters: ModelParameters) = modelDelegate.updateModelParameters(parameters)

    fun branchFromComparison(agentId: String) = comparisonDelegate.branchFromComparison(agentId)
}
