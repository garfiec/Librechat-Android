package com.librechat.android.feature.chat.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.librechat.android.core.common.EndpointConstants
import com.librechat.android.core.common.ToolConstants
import com.librechat.android.core.common.network.ConnectivityObserver
import com.librechat.android.core.common.result.Result
import com.librechat.android.core.common.result.getOrNull
import com.librechat.android.core.data.datastore.LatexRenderer
import com.librechat.android.core.data.datastore.ServerDataStore
import com.librechat.android.core.data.datastore.SettingsDataStore
import com.librechat.android.core.data.repository.AgentRepository
import com.librechat.android.core.data.repository.ChatRepository
import com.librechat.android.core.data.repository.ConfigRepository
import com.librechat.android.core.data.repository.ConversationRepository
import com.librechat.android.core.data.repository.DraftRepository
import com.librechat.android.core.data.repository.FileRepository
import com.librechat.android.core.data.repository.McpRepository
import com.librechat.android.core.data.repository.MessageRepository
import com.librechat.android.core.data.repository.PresetRepository
import com.librechat.android.core.data.repository.PromptRepository
import com.librechat.android.core.data.repository.ShareRepository
import com.librechat.android.core.data.repository.SpeechRepository
import com.librechat.android.core.data.repository.UserRepository
import com.librechat.android.core.model.Preset
import com.librechat.android.core.model.StreamEvent
import com.librechat.android.core.model.request.EphemeralAgent
import com.librechat.android.core.ui.components.ModelParameters
import com.librechat.android.feature.chat.PresetDisplayData
import com.librechat.android.feature.chat.PromptMentionDisplayData
import com.librechat.android.feature.chat.ShareIntentConsumer
import com.librechat.android.feature.chat.components.AttachedFile
import com.librechat.android.feature.chat.util.NEW_CHAT_DRAFT_KEY
import com.librechat.android.feature.chat.util.buildActiveMessagePath
import com.librechat.android.feature.chat.viewmodel.delegate.ConversationActionsDelegate
import com.librechat.android.feature.chat.viewmodel.delegate.FileAttachmentDelegate
import com.librechat.android.feature.chat.viewmodel.delegate.InConversationSearchDelegate
import com.librechat.android.feature.chat.viewmodel.delegate.ModelSelectionDelegate
import com.librechat.android.feature.chat.viewmodel.delegate.PresetPromptDelegate
import com.librechat.android.feature.chat.viewmodel.delegate.TextToSpeechDelegate
import com.librechat.android.feature.chat.viewmodel.delegate.VoiceInputDelegate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
class ChatViewModel(
    savedStateHandle: SavedStateHandle,
    private val appContext: Context,
    private val agentRepository: AgentRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val configRepository: ConfigRepository,
    private val conversationRepository: ConversationRepository,
    private val draftRepository: DraftRepository,
    fileRepository: FileRepository,
    presetRepository: PresetRepository,
    promptRepository: PromptRepository,
    shareRepository: ShareRepository,
    private val speechRepository: SpeechRepository,
    mcpRepository: McpRepository,
    private val userRepository: UserRepository,
    private val connectivityObserver: ConnectivityObserver,
    serverDataStore: ServerDataStore,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    private val stateHandle = ChatStateHandle(_uiState, viewModelScope)

    // --- Delegates ---
    private val searchDelegate = InConversationSearchDelegate(stateHandle)
    private val conversationActionsDelegate =
        ConversationActionsDelegate(stateHandle, conversationRepository, shareRepository)
    private val ttsDelegate =
        TextToSpeechDelegate(stateHandle, appContext, speechRepository, settingsDataStore, ::getMessageText)
    private val voiceDelegate =
        VoiceInputDelegate(
            stateHandle, appContext, speechRepository,
            autoSendAfterStt = settingsDataStore.autoSendAfterStt
                .stateIn(viewModelScope, SharingStarted.Eagerly, false),
            onTranscriptionComplete = ::sendMessage,
        )
    private val fileDelegate = FileAttachmentDelegate(stateHandle, appContext, fileRepository)
    private val presetPromptDelegate = PresetPromptDelegate(stateHandle, presetRepository, promptRepository)
    private val modelDelegate =
        ModelSelectionDelegate(stateHandle, configRepository, agentRepository, mcpRepository, settingsDataStore)

    // --- Delegate-owned flows exposed to the UI ---
    val attachedFiles: StateFlow<List<AttachedFile>> get() = fileDelegate.attachedFiles
    val shareLinkUrl: StateFlow<String?> get() = conversationActionsDelegate.shareLinkUrl

    @Suppress("UNCHECKED_CAST")
    val chatPreferences: StateFlow<ChatPreferences> = combine<Any, ChatPreferences>(
        listOf(
            settingsDataStore.showImageDescriptions,
            settingsDataStore.dismissKeyboardOnSend,
            settingsDataStore.chatLayoutStyle,
            settingsDataStore.showAvatars,
            settingsDataStore.showBubbles,
            settingsDataStore.latexRenderer,
            settingsDataStore.autoSendAfterStt,
            settingsDataStore.sttEngine,
            settingsDataStore.sttLanguage,
        ),
    ) { values ->
        ChatPreferences(
            showImageDescriptions = values[0] as Boolean,
            dismissKeyboardOnSend = values[1] as Boolean,
            chatLayoutStyle = values[2] as String,
            showAvatars = values[3] as Boolean,
            showBubbles = values[4] as Boolean,
            latexRenderer = values[5] as LatexRenderer,
            autoSendAfterStt = values[6] as Boolean,
            sttEngine = values[7] as String,
            sttLanguage = values[8] as String,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatPreferences())

    val uiState: StateFlow<ChatUiState> = combine(
        _uiState,
        serverDataStore.currentUrlFlow,
        settingsDataStore.chatFontSize,
    ) { state, url, fontSize ->
        state.copy(
            serverUrl = url,
            chatFontSize = fontSize,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

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
    }

    /** True when the current stream is from an edit, regenerate, or continue operation. */
    private var isEditOrRegenerate = false

    /** True when this ViewModel was opened for a brand-new chat (no conversationId from navigation). */
    private val isNewConversation: Boolean

    /** Guard to ensure we only attempt title generation once per conversation. */
    private var titleGenerationRequested = false

    init {
        val conversationId = savedStateHandle.get<String>("conversationId")
        isNewConversation = conversationId == null
        if (conversationId != null) {
            _uiState.value = _uiState.value.copy(
                conversationId = conversationId,
                screenState = ChatScreenState.LOADING,
            )
            loadConversation(conversationId)
            loadConversationModel(conversationId)
            restoreDraft(conversationId)
            // Check if there's an active stream for this conversation (e.g. when
            // navigating here from new_chat immediately after sending). If so,
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
            ShareIntentConsumer.shareAvailable.collect {
                consumeShareIntent()
            }
        }

        // Eagerly load last-used model from DataStore so refilterModels can
        // use it as a fallback.
        viewModelScope.launch {
            val endpoint = settingsDataStore.lastUsedEndpoint.first()
            val model = settingsDataStore.lastUsedModel.first()
            modelDelegate.cachedLastUsedEndpoint = endpoint
            modelDelegate.cachedLastUsedModel = model
            modelDelegate.lastUsedModelLoaded = true
            // For new chats, apply the last-used model directly as the
            // initial selection. refilterModels will validate it when
            // the available models list arrives.
            if (isNewConversation && endpoint != null && model != null) {
                _uiState.value = _uiState.value.copy(
                    selectedEndpoint = endpoint,
                    selectedModel = model,
                )
            }
            // Re-run validation now that we have the DataStore values.
            modelDelegate.refilterModels(isNewConversation)
        }

        viewModelScope.launch {
            configRepository.endpointConfigs.collect { configs ->
                _uiState.value = _uiState.value.copy(endpointConfigs = configs)
                modelDelegate.refilterModels(isNewConversation)
                // If code interpreter is no longer available, remove it from enabled tools
                val agentsCapabilities = configs[EndpointConstants.AGENTS]?.capabilities ?: emptyList()
                if (agentsCapabilities.isNotEmpty() && ToolConstants.EXECUTE_CODE !in agentsCapabilities) {
                    val currentTools = _uiState.value.enabledTools
                    if (ToolConstants.CODE_INTERPRETER in currentTools) {
                        _uiState.value = _uiState.value.copy(
                            enabledTools = currentTools - ToolConstants.CODE_INTERPRETER,
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            configRepository.availableModels.collect { models ->
                _uiState.value = _uiState.value.copy(availableModels = models)
                modelDelegate.refilterModels(isNewConversation)
            }
        }

        viewModelScope.launch {
            val endpointsResult = configRepository.fetchEndpoints()
            if (endpointsResult is Result.Error) {
                _uiState.value = _uiState.value.copy(
                    error = endpointsResult.message ?: "Could not load endpoint configuration",
                )
                return@launch
            }
            val modelsResult = configRepository.fetchModels()
            if (modelsResult is Result.Error) {
                _uiState.value = _uiState.value.copy(
                    error = modelsResult.message ?: "Could not load available models",
                )
            }
        }

        // Restore MCP server and tool selections from DataStore so they
        // survive the new_chat -> chat/{id} navigation re-creation.
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
        presetPromptDelegate.loadAvailablePrompts()
        modelDelegate.loadMcpServers()
        loadUserProfile()
        modelDelegate.loadAgents()
        loadSharedLinksEnabled()
        voiceDelegate.loadSpeechConfig()
    }

    // ── Core chat flow ──────────────────────────────────────────────

    private fun loadConversation(conversationId: String) {
        // Cancel any previous Room observer to avoid duplicate collectors
        roomObserverJob?.cancel()
        roomObserverJob = viewModelScope.launch {
            try {
                messageRepository.getMessages(conversationId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch messages for $conversationId")
                _uiState.value = _uiState.value.copy(
                    error = "Could not load messages",
                    screenState = ChatScreenState.ACTIVE,
                )
            }
            messageRepository.observeMessages(conversationId).collect { messages ->
                val state = _uiState.value
                val displayMessages = buildActiveMessagePath(messages, state.activeBranches)
                _uiState.value = state.copy(
                    messages = messages,
                    displayMessages = displayMessages,
                    screenState = ChatScreenState.ACTIVE,
                )
            }
        }
    }

    /**
     * Restores a previously saved draft for the given key (conversation ID or [NEW_CHAT_DRAFT_KEY]).
     */
    private fun restoreDraft(draftKey: String) {
        viewModelScope.launch {
            val draft = draftRepository.getDraft(draftKey)
            if (!draft.isNullOrBlank() && _uiState.value.inputText.isBlank()) {
                _uiState.value = _uiState.value.copy(inputText = draft)
            }
        }
    }

    private fun loadConversationModel(conversationId: String) {
        viewModelScope.launch {
            val result = conversationRepository.getConversation(conversationId)
            val conversation = result.getOrNull()
            if (conversation != null) {
                val endpoint = conversation.endpoint?.takeIf { it.isNotBlank() }
                val model = conversation.model
                val isAgentConversation = endpoint.equals(EndpointConstants.AGENTS, ignoreCase = true)
                val resolvedModel = if (isAgentConversation) {
                    conversation.agentId ?: model
                } else {
                    model
                }
                _uiState.value = _uiState.value.copy(
                    selectedEndpoint = if (endpoint != null && (resolvedModel != null || isAgentConversation)) {
                        endpoint
                    } else {
                        _uiState.value.selectedEndpoint
                    },
                    selectedModel = if (endpoint != null && resolvedModel != null) {
                        resolvedModel
                    } else {
                        _uiState.value.selectedModel
                    },
                    conversationTitle = conversation.title,
                )
            }
            modelDelegate.conversationModelLoaded = true
            modelDelegate.refilterModels(isNewConversation)
        }
    }

    private fun refreshConversationTitle(conversationId: String) {
        viewModelScope.launch {
            val result = conversationRepository.getConversation(conversationId)
            val conversation = result.getOrNull() ?: return@launch
            _uiState.value = _uiState.value.copy(
                conversationTitle = conversation.title,
            )
        }
    }

    private fun generateAndSetTitle(conversationId: String) {
        viewModelScope.launch {
            when (val result = conversationRepository.generateTitle(conversationId)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        conversationTitle = result.data,
                    )
                }
                is Result.Error -> {
                    Timber.d("Title generation failed for $conversationId: ${result.message}")
                    refreshConversationTitle(conversationId)
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    private fun rebuildDisplayMessages() {
        val state = _uiState.value
        val displayMessages = buildActiveMessagePath(state.messages, state.activeBranches)
        _uiState.value = state.copy(displayMessages = displayMessages)
    }

    fun switchBranch(parentMessageId: String, siblingIndex: Int) {
        val state = _uiState.value
        val newBranches = state.activeBranches.toMutableMap()
        newBranches[parentMessageId] = siblingIndex
        _uiState.value = state.copy(activeBranches = newBranches)
        rebuildDisplayMessages()
    }

    fun onInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
        val draftKey = _uiState.value.conversationId ?: NEW_CHAT_DRAFT_KEY
        viewModelScope.launch {
            draftRepository.saveDraft(draftKey, text)
        }
    }

    private fun consumeShareIntent() {
        val shareData = ShareIntentConsumer.consume() ?: return
        Timber.d("consumeShareIntent: text=%s, files=%d", shareData.text != null, shareData.fileUris.size)

        if (!shareData.text.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(inputText = shareData.text)
        }

        if (shareData.fileUris.isNotEmpty()) {
            fileDelegate.onFilesSelected(shareData.fileUris)
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
            Timber.d("sendMessage: waiting for pending upload(s) to complete")
            fileDelegate.pendingUploadSendJob = viewModelScope.launch {
                fileDelegate.waitForUploadsAndSend(text) { doSendMessage(it) }
            }
            return
        }

        doSendMessage(text)
    }

    /**
     * Builds an [EphemeralAgent] from the current UI state (selected MCP servers
     * and enabled tools). Returns null when there is nothing to send.
     */
    private fun buildEphemeralAgent(): EphemeralAgent? {
        val state = _uiState.value
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
        val optimisticMessage = com.librechat.android.core.model.Message(
            messageId = UUID.randomUUID().toString(),
            conversationId = conversationId ?: "",
            parentMessageId = lastMessageId,
            text = messageText,
            isCreatedByUser = true,
            sender = "User",
            createdAt = java.time.Instant.now().toString(),
            files = fileRefs.takeIf { it.isNotEmpty() },
        )
        val updatedMessages = _uiState.value.messages + optimisticMessage
        val updatedDisplay = buildActiveMessagePath(updatedMessages, _uiState.value.activeBranches)

        isEditOrRegenerate = false

        val isNewChat = conversationId == null
        _uiState.value = _uiState.value.copy(
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
        // Clear draft
        val draftKey = conversationId ?: NEW_CHAT_DRAFT_KEY
        viewModelScope.launch { draftRepository.deleteDraft(draftKey) }
        // Clear attached files
        fileDelegate.clearAttachedFiles()
        streamingBuffer.clear()
        streamingBufferDirty = false
        startStreamingUpdater()

        val isAgent = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Timber.d(
            "sendMessage: webSearch=%s, endpoint=%s, model=%s, files=%d, ephemeralAgent=%s",
            webSearchEnabled, _uiState.value.selectedEndpoint,
            _uiState.value.selectedModel, fileRefs.size, ephemeralAgent,
        )

        // Build addedConvo if comparison mode is enabled
        val addedConvo = if (_uiState.value.comparisonState.isEnabled) {
            modelDelegate.buildAddedConvo(parentMessageId = lastMessageId)
        } else {
            null
        }

        // Resolve effective endpoint/agentId for comparison mode.
        // All requests go through api/agents/chat/{endpoint} — the server's
        // middleware creates ephemeral agents for non-agent endpoints, so no
        // swapping is needed. Just keep the primary's original endpoint.
        val effectiveEndpoint = _uiState.value.selectedEndpoint
        val effectiveAgentId = if (isAgent) _uiState.value.selectedModel else null
        val effectiveAddedConvo = addedConvo // null when no comparison
        // If comparison enabled, prepare comparison streaming state
        if (addedConvo != null) {
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
            collectStreamSafely(
                chatRepository.startChat(
                    text = messageText,
                    conversationId = conversationId,
                    endpoint = effectiveEndpoint,
                    model = _uiState.value.selectedModel,
                    parentMessageId = lastMessageId,
                    agentId = effectiveAgentId,
                    webSearch = webSearchEnabled,
                    files = fileRefs.takeIf { it.isNotEmpty() },
                    addedConvo = effectiveAddedConvo,
                    ephemeralAgent = ephemeralAgent,
                ),
            )
            // Safety net: if the flow ends without Final or Error, clear streaming
            if (_uiState.value.isStreaming) {
                val cid = _uiState.value.conversationId
                if (cid != null && roomObserverJob?.isActive != true) {
                    loadConversation(cid)
                } else if (cid == null) {
                    _uiState.value = _uiState.value.copy(isStreaming = false)
                }
            }
        }
    }

    /**
     * Resets streaming-related UI state and clears the buffer in preparation
     * for a new stream (edit, regenerate, or continue).
     */
    private fun prepareForStreaming() {
        _uiState.value = _uiState.value.copy(
            isStreaming = true,
            streamingContent = "",
            activeToolCalls = emptyList(),
            streamingAttachments = emptyList(),
            error = null,
        )
        streamingBuffer.clear()
        streamingBufferDirty = false
        startStreamingUpdater()
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
        _uiState.value = _uiState.value.copy(
            streamingContent = streamingBuffer.toString(),
        )
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

        if (originalMessage.isCreatedByUser) {
            editUserMessage(originalMessage, newText)
        } else {
            editAiMessage(originalMessage, newText)
        }
    }

    private fun editUserMessage(originalMessage: com.librechat.android.core.model.Message, newText: String) {
        val parentMessageId = originalMessage.parentMessageId

        isEditOrRegenerate = true
        prepareForStreaming()

        val isAgent = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Timber.d("editUserMessage: webSearch=%s, ephemeralAgent=%s", webSearchEnabled, ephemeralAgent)
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            collectStreamSafely(
                chatRepository.startChat(
                    text = newText,
                    conversationId = _uiState.value.conversationId,
                    endpoint = _uiState.value.selectedEndpoint,
                    model = _uiState.value.selectedModel,
                    parentMessageId = parentMessageId,
                    agentId = if (isAgent) _uiState.value.selectedModel else null,
                    isEdited = true,
                    webSearch = webSearchEnabled,
                    ephemeralAgent = ephemeralAgent,
                ),
            )
        }
    }

    private fun editAiMessage(aiMessage: com.librechat.android.core.model.Message, newText: String) {
        val parentUserMessage = _uiState.value.messages.find {
            it.messageId == aiMessage.parentMessageId
        } ?: return

        val conversationId = _uiState.value.conversationId ?: return

        isEditOrRegenerate = true
        prepareForStreaming()

        val isAgent = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Timber.d("editAiMessage: webSearch=%s, ephemeralAgent=%s", webSearchEnabled, ephemeralAgent)
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            messageRepository.updateMessageText(conversationId, aiMessage.messageId, newText)
            collectStreamSafely(
                chatRepository.startChat(
                    text = parentUserMessage.text,
                    conversationId = conversationId,
                    endpoint = _uiState.value.selectedEndpoint,
                    model = _uiState.value.selectedModel,
                    parentMessageId = parentUserMessage.parentMessageId,
                    agentId = if (isAgent) _uiState.value.selectedModel else null,
                    overrideParentMessageId = parentUserMessage.messageId,
                    isEdited = true,
                    isRegenerate = true,
                    webSearch = webSearchEnabled,
                    ephemeralAgent = ephemeralAgent,
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

        isEditOrRegenerate = true
        prepareForStreaming()

        val isAgentRegen = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Timber.d("regenerateMessage: webSearch=%s, ephemeralAgent=%s", webSearchEnabled, ephemeralAgent)
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            collectStreamSafely(
                chatRepository.startChat(
                    text = parentUserMessage.text,
                    conversationId = _uiState.value.conversationId,
                    endpoint = _uiState.value.selectedEndpoint,
                    model = _uiState.value.selectedModel,
                    parentMessageId = parentUserMessage.parentMessageId,
                    agentId = if (isAgentRegen) _uiState.value.selectedModel else null,
                    overrideParentMessageId = parentUserMessage.messageId,
                    isRegenerate = true,
                    webSearch = webSearchEnabled,
                    ephemeralAgent = ephemeralAgent,
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Never swallow cancellation
        } catch (e: Exception) {
            Timber.e(e, "Stream collection failed")
            stopStreamingUpdater()
            // Preserve partial content so users can read/copy what was received
            val partialContent = streamingBuffer.toString()
            _uiState.value = _uiState.value.copy(
                isStreaming = false,
                streamingContent = partialContent,
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
                error = e.message ?: "Chat request failed",
                comparisonState = _uiState.value.comparisonState.copy(
                    primaryIsStreaming = false,
                    secondaryIsStreaming = false,
                    primaryActiveToolCalls = emptyList(),
                    secondaryActiveToolCalls = emptyList(),
                ),
            )
            // If the server already created a conversation, fetch whatever it persisted
            val conversationId = _uiState.value.conversationId
            if (conversationId != null) {
                loadConversation(conversationId)
            }
        }
    }

    private fun handleStreamEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.Created -> {
                if (isNewConversation && event.conversationId != null) {
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
                val createdCopy = if (_uiState.value.retryInfo != null) {
                    _uiState.value.copy(conversationId = event.conversationId, retryInfo = null)
                } else {
                    _uiState.value.copy(conversationId = event.conversationId)
                }
                _uiState.value = createdCopy
                if (isNewConversation && event.conversationId != null &&
                    _uiState.value.pendingNavigationConversationId == null &&
                    !_uiState.value.comparisonState.isEnabled
                ) {
                    _uiState.value = _uiState.value.copy(
                        pendingNavigationConversationId = event.conversationId,
                    )
                }
                // Eagerly fetch and cache the conversation the server just created,
                // so it appears in the conversation list even if the stream fails later
                if (event.conversationId != null) {
                    viewModelScope.launch {
                        conversationRepository.getConversation(event.conversationId)
                    }
                }
            }
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
            is StreamEvent.Final -> {
                stopStreamingUpdater()
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
                    _uiState.value = _uiState.value.copy(
                        isStreaming = false,
                        streamingContent = "",
                        activeToolCalls = emptyList(),
                        streamingAttachments = emptyList(),
                        conversationId = conversationId ?: _uiState.value.conversationId,
                    )
                }
                val finalConversation = event.conversation
                if (finalConversation?.conversationId != null) {
                    viewModelScope.launch {
                        conversationRepository.saveConversation(finalConversation)
                    }
                }
                if (conversationId != null) {
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
                if (shouldAutoRead && completedResponseText.isNotBlank()) {
                    ttsDelegate.maybeAutoReadResponse(completedResponseText)
                }
            }
            is StreamEvent.Error -> {
                stopStreamingUpdater()
                // Track network errors so auto-reconnect can kick in when connectivity returns
                lastErrorWasNetwork = event.isNetworkError
                if (event.isNetworkError) {
                    startConnectivityObserver()
                }
                // Preserve partial content so users can read/copy what was received
                val partialContent = streamingBuffer.toString()
                _uiState.value = _uiState.value.copy(
                    isStreaming = false,
                    streamingContent = partialContent,
                    error = event.message,
                    retryInfo = null,
                    activeToolCalls = emptyList(),
                    streamingAttachments = emptyList(),
                    comparisonState = _uiState.value.comparisonState.copy(
                        primaryIsStreaming = false,
                        secondaryIsStreaming = false,
                        primaryActiveToolCalls = emptyList(),
                        secondaryActiveToolCalls = emptyList(),
                    ),
                )
                // If the server already created a conversation, fetch whatever it persisted
                val conversationId = _uiState.value.conversationId
                if (conversationId != null) {
                    loadConversation(conversationId)
                }
            }
            is StreamEvent.Retrying -> {
                _uiState.value = _uiState.value.copy(
                    retryInfo = RetryInfo(
                        attempt = event.attempt,
                        maxAttempts = event.maxAttempts,
                    ),
                )
            }
            is StreamEvent.ToolCallStart -> {
                val newToolCall = ActiveToolCall(
                    id = event.toolCallId,
                    name = event.toolName,
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
                    _uiState.value = _uiState.value.copy(
                        activeToolCalls = _uiState.value.activeToolCalls + newToolCall,
                    )
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
                    val updated = _uiState.value.activeToolCalls.map { tc ->
                        if (tc.id == event.toolCallId) {
                            tc.copy(isComplete = true, output = event.output)
                        } else {
                            tc
                        }
                    }
                    _uiState.value = _uiState.value.copy(activeToolCalls = updated)
                }
            }
            is StreamEvent.AttachmentCreated -> {
                val attachment = com.librechat.android.core.model.Attachment(
                    fileId = event.fileId,
                    filename = event.filename,
                    filepath = event.filepath,
                    type = event.type,
                    toolCallId = event.toolCallId,
                    width = event.width,
                    height = event.height,
                )
                _uiState.value = _uiState.value.copy(
                    streamingAttachments = _uiState.value.streamingAttachments + attachment,
                )
            }
            is StreamEvent.Sync -> {
                // State-snapshot protocol: replace streaming buffer with synced content
                if (lastErrorWasNetwork) {
                    lastErrorWasNetwork = false
                    cancelConnectivityObserver()
                }
                if (_uiState.value.retryInfo != null) {
                    _uiState.value = _uiState.value.copy(retryInfo = null)
                }
                val textContent = event.aggregatedContent
                    .mapNotNull { it.text }
                    .joinToString("")
                streamingBuffer.clear()
                streamingBuffer.append(textContent)
                streamingBufferDirty = true
                flushStreamingBuffer()
            }
            is StreamEvent.Step -> { /* no-op */ }
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
                Timber.w(abortResult.exception, "Failed to abort chat: %s", abortResult.message)
            }
            // Clean up comparison state if active
            val isComparison = _uiState.value.comparisonState.isEnabled
            _uiState.value = _uiState.value.copy(
                isStreaming = false,
                streamingContent = "",
                activeToolCalls = emptyList(),
                streamingAttachments = emptyList(),
                comparisonState = if (isComparison) {
                    _uiState.value.comparisonState.copy(
                        primaryIsStreaming = false,
                        secondaryIsStreaming = false,
                        primaryStreamingContent = "",
                        secondaryStreamingContent = "",
                        primaryActiveToolCalls = emptyList(),
                        secondaryActiveToolCalls = emptyList(),
                    )
                } else {
                    _uiState.value.comparisonState
                },
            )
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

        isEditOrRegenerate = true
        prepareForStreaming()

        val isAgentContinue = _uiState.value.selectedEndpoint == EndpointConstants.AGENTS
        val webSearchEnabled = _uiState.value.modelParameters.webSearch
        val ephemeralAgent = buildEphemeralAgent()
        Timber.d("continueGeneration: webSearch=%s, ephemeralAgent=%s", webSearchEnabled, ephemeralAgent)
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            collectStreamSafely(
                chatRepository.startChat(
                    text = parentUserMessage.text,
                    conversationId = _uiState.value.conversationId,
                    endpoint = _uiState.value.selectedEndpoint,
                    model = _uiState.value.selectedModel,
                    parentMessageId = parentUserMessage.parentMessageId,
                    agentId = if (isAgentContinue) _uiState.value.selectedModel else null,
                    overrideParentMessageId = parentUserMessage.messageId,
                    responseMessageId = lastAiMessage.message.messageId,
                    isEdited = true,
                    isRegenerate = true,
                    isContinued = true,
                    webSearch = webSearchEnabled,
                    ephemeralAgent = ephemeralAgent,
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
                    _uiState.value = _uiState.value.copy(isStreaming = true)
                    resumeStream(conversationId)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isStreaming = false,
                        streamingContent = "",
                    )
                    loadConversation(conversationId)
                }
            } catch (e: Exception) {
                Timber.e(e, "Could not resume stream")
                _uiState.value = _uiState.value.copy(
                    isStreaming = false,
                    streamingContent = "",
                    error = "Could not resume stream",
                )
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
                    _uiState.value = _uiState.value.copy(
                        isStreaming = true,
                        screenState = ChatScreenState.ACTIVE,
                    )
                    resumeStream(conversationId)
                }
            } catch (e: Exception) {
                Timber.d(e, "No active stream to resume for $conversationId")
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
        Timber.d("Network recovered, attempting to resume conversation $conversationId")

        viewModelScope.launch {
            try {
                val status = chatRepository.checkStreamStatus(conversationId)
                if (status.active) {
                    _uiState.value = _uiState.value.copy(
                        isStreaming = true,
                        error = null,
                        retryInfo = null,
                    )
                    resumeStream(conversationId)
                } else {
                    // Stream expired while offline — reload conversation from server
                    _uiState.value = _uiState.value.copy(
                        error = null,
                        retryInfo = null,
                    )
                    loadConversation(conversationId)
                }
            } catch (e: Exception) {
                Timber.w(e, "Network recovery: could not check stream status")
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
        _uiState.value = _uiState.value.copy(
            editingMessageId = messageId,
            editingText = text,
        )
    }

    fun onEditTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(editingText = text)
    }

    fun cancelEditing() {
        _uiState.value = _uiState.value.copy(editingMessageId = null, editingText = "")
    }

    fun submitEdit() {
        val messageId = _uiState.value.editingMessageId ?: return
        val newText = _uiState.value.editingText.trim()
        if (newText.isBlank()) return

        _uiState.value = _uiState.value.copy(editingMessageId = null, editingText = "")
        editMessage(messageId, newText)
    }

    fun saveEditOnly() {
        val messageId = _uiState.value.editingMessageId ?: return
        val conversationId = _uiState.value.conversationId ?: return
        val newText = _uiState.value.editingText.trim()
        if (newText.isBlank()) return

        _uiState.value = _uiState.value.copy(editingMessageId = null, editingText = "")
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
        val current = _uiState.value
        _uiState.value = ChatUiState(
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
        streamingBuffer.clear()
    }

    fun toggleTemporaryChat() {
        _uiState.value = _uiState.value.copy(
            isTemporaryChat = !_uiState.value.isTemporaryChat,
        )
    }

    fun refreshMessages() {
        val conversationId = _uiState.value.conversationId ?: return
        if (_uiState.value.isRefreshingMessages) return
        _uiState.value = _uiState.value.copy(isRefreshingMessages = true)
        viewModelScope.launch {
            messageRepository.refreshMessages(conversationId)
            loadConversation(conversationId)
            _uiState.value = _uiState.value.copy(isRefreshingMessages = false)
        }
    }

    private fun loadSharedLinksEnabled() {
        viewModelScope.launch {
            configRepository.startupConfig.collect { config ->
                _uiState.value = _uiState.value.copy(
                    sharedLinksEnabled = config?.sharedLinksEnabled ?: false,
                )
            }
        }
    }

    private fun loadUserProfile() {
        viewModelScope.launch {
            when (val result = userRepository.getUser()) {
                is Result.Success -> {
                    val user = result.data
                    _uiState.value = _uiState.value.copy(
                        userName = user.name ?: user.username,
                        userAvatarUrl = user.avatar,
                    )
                }
                is Result.Error -> {
                    Timber.d(result.exception, "Failed to load user profile: ${result.message}")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        voiceDelegate.release()
        ttsDelegate.release()
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
    fun onFilesSelected(uris: List<Uri>) = fileDelegate.onFilesSelected(uris)
    fun removeFile(file: AttachedFile) = fileDelegate.removeFile(file)
    fun retryUpload(file: AttachedFile) = fileDelegate.retryUpload(file)

    // Presets and prompts
    fun savePreset(name: String) = presetPromptDelegate.savePreset(name)
    fun loadPreset(displayData: PresetDisplayData) = presetPromptDelegate.loadPreset(displayData)
    fun deletePreset(presetId: String) = presetPromptDelegate.deletePreset(presetId)
    fun editPreset(preset: Preset) = presetPromptDelegate.editPreset(preset)
    fun handlePromptMention(displayData: PromptMentionDisplayData) = presetPromptDelegate.handlePromptMention(displayData)
    fun handleSlashCommand(displayData: PromptMentionDisplayData) = presetPromptDelegate.handleSlashCommand(displayData)

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
                Timber.e(e, "Failed to branch comparison message")
                _uiState.update {
                    it.copy(error = "Failed to continue with selected response")
                }
            }
        }
    }
}
