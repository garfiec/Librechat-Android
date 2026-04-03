package com.librechat.android.feature.voice.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.librechat.android.core.common.result.Result
import com.librechat.android.core.data.repository.ChatRepository
import com.librechat.android.core.data.repository.ConfigRepository
import com.librechat.android.core.data.repository.ConversationRepository
import com.librechat.android.core.data.repository.MessageRepository
import com.librechat.android.core.data.repository.SpeechRepository
import com.librechat.android.core.data.repository.UserRepository
import com.librechat.android.core.model.ContentType
import com.librechat.android.core.model.StreamEvent
import com.librechat.android.core.model.UserFavorite
import com.librechat.android.feature.voice.R
import com.librechat.android.feature.voice.audio.VoiceAudioPlayer
import com.librechat.android.feature.voice.audio.VoiceRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class VoiceSessionViewModel(
    private val appContext: Context,
    private val chatRepository: ChatRepository,
    private val speechRepository: SpeechRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var initialized = false
    private var activeTurnJob: Job? = null
    private val recorder = VoiceRecorder(appContext)
    private val audioPlayer = VoiceAudioPlayer(appContext)

    fun loadHomeData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingHome = true, error = null) }
            launch {
                conversationRepository.observeConversations().collect { result ->
                    if (result is Result.Success) {
                        _state.update {
                            it.copy(
                                recentConversations = result.data.take(10),
                                isLoadingHome = false,
                            )
                        }
                    }
                }
            }
            configRepository.fetchModels()
            when (val favorites = userRepository.getFavorites()) {
                is Result.Success -> _state.update {
                    it.copy(
                        favoriteModels = favoritesForVoicePicker(
                            favorites = favorites.data,
                            availableModels = configRepository.availableModels.value,
                        ),
                    )
                }
                is Result.Error -> _state.update {
                    it.copy(error = favorites.message ?: "Could not load favorite models")
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun initializeSession(
        conversationId: String?,
        endpoint: String?,
        model: String?,
        agentId: String?,
    ) {
        if (initialized) return
        initialized = true

        viewModelScope.launch {
            var selectedEndpoint = endpoint ?: "agents"
            var selectedModel = model
            var selectedAgentId = agentId
            var resolvedParentMessageId: String? = null

            if (!conversationId.isNullOrBlank()) {
                when (val conversationResult = conversationRepository.getConversation(conversationId)) {
                    is Result.Success -> {
                        val convo = conversationResult.data
                        selectedEndpoint = convo.endpoint?.toSerialName() ?: selectedEndpoint
                        selectedModel = convo.model ?: selectedModel
                        selectedAgentId = convo.agentId ?: selectedAgentId
                    }
                    else -> Unit
                }
                resolvedParentMessageId = resolveParentMessageId(conversationId)
            }

            _state.update {
                it.copy(
                    conversationId = conversationId,
                    selectedEndpoint = selectedEndpoint,
                    selectedModel = selectedModel,
                    selectedAgentId = selectedAgentId,
                    lastParentMessageId = resolvedParentMessageId,
                )
            }
        }
    }

    fun onMicrophonePermissionDenied() {
        _state.update {
            it.copy(error = appContext.getString(R.string.voice_mic_permission_required))
        }
    }

    fun startListening() {
        if (_state.value.phase == VoicePhase.TRANSCRIBING || _state.value.phase == VoicePhase.THINKING) return
        if (audioPlayer.isPlaying()) {
            bargeIn()
            return
        }
        runCatching { recorder.start() }
            .onSuccess { _state.update { it.copy(phase = VoicePhase.LISTENING, error = null) } }
            .onFailure { e ->
                _state.update { s ->
                    s.copy(error = "Could not start recording: ${e.message}")
                }
            }
    }

    fun stopListening() {
        if (!recorder.isRecording) return
        val audioData = recorder.stop()
        if (audioData == null || audioData.isEmpty()) {
            _state.update { it.copy(phase = VoicePhase.IDLE, error = "Recording was empty") }
            return
        }

        activeTurnJob?.cancel()
        activeTurnJob = viewModelScope.launch {
            try {
                runTurnAfterRecording(audioData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Voice turn failed")
                _state.update {
                    it.copy(phase = VoicePhase.IDLE, error = e.message ?: "Voice session error")
                }
            }
        }
    }

    private suspend fun runTurnAfterRecording(audioData: ByteArray) {
        _state.update { it.copy(phase = VoicePhase.TRANSCRIBING, error = null, streamingText = "") }
        val transcribedText = when (val stt = speechRepository.transcribeAudio(audioData, recorder.mimeType)) {
            is Result.Success -> stt.data.text.orEmpty()
            is Result.Error -> {
                _state.update {
                    it.copy(
                        phase = VoicePhase.IDLE,
                        error = stt.message ?: "Speech transcription failed",
                    )
                }
                return
            }
            is Result.Loading -> return
        }

        if (transcribedText.isBlank()) {
            _state.update { it.copy(phase = VoicePhase.IDLE, error = "Transcription was empty") }
            return
        }

        _state.update {
            it.copy(
                phase = VoicePhase.THINKING,
                lastUserText = transcribedText,
                error = null,
                streamingText = "",
            )
        }

        val responseBuilder = StringBuilder()
        var createdConversationId: String? = _state.value.conversationId
        var lastParentMessageId = _state.value.lastParentMessageId

        chatRepository.startChat(
            text = transcribedText,
            conversationId = _state.value.conversationId,
            endpoint = _state.value.selectedEndpoint,
            model = _state.value.selectedModel,
            parentMessageId = _state.value.lastParentMessageId,
            agentId = _state.value.selectedAgentId,
        ).collect { event ->
            when (event) {
                is StreamEvent.ContentDelta -> {
                    responseBuilder.append(event.chunk)
                    _state.update { it.copy(streamingText = responseBuilder.toString()) }
                }
                is StreamEvent.Created -> {
                    if (event.conversationId.isNotBlank()) {
                        createdConversationId = event.conversationId
                    }
                    if (event.messageId.isNotBlank()) {
                        lastParentMessageId = event.messageId
                    }
                }
                is StreamEvent.Final -> {
                    lastParentMessageId = event.responseMessage?.messageId ?: lastParentMessageId
                    val finalText = event.responseMessage?.extractReadableText()
                    if (!finalText.isNullOrBlank()) {
                        responseBuilder.clear()
                        responseBuilder.append(finalText)
                    }
                }
                is StreamEvent.Error -> {
                    _state.update {
                        it.copy(
                            phase = VoicePhase.IDLE,
                            error = event.message,
                        )
                    }
                    return@collect
                }
                else -> Unit
            }
        }

        val responseText = responseBuilder.toString().trim()
        if (responseText.isBlank()) {
            _state.update {
                it.copy(
                    phase = VoicePhase.IDLE,
                    conversationId = createdConversationId,
                    lastParentMessageId = lastParentMessageId,
                    streamingText = "",
                )
            }
            return
        }

        _state.update {
            it.copy(
                phase = VoicePhase.SPEAKING,
                conversationId = createdConversationId,
                lastParentMessageId = lastParentMessageId,
                lastAssistantText = responseText,
                streamingText = "",
                turnCount = it.turnCount + 1,
            )
        }
        when (val tts = speechRepository.synthesizeSpeech(responseText)) {
            is Result.Success -> {
                audioPlayer.play(
                    audioBytes = tts.data,
                    onCompleted = {
                        _state.update { current -> current.copy(phase = VoicePhase.IDLE) }
                    },
                    onError = { message ->
                        _state.update { current ->
                            current.copy(
                                phase = VoicePhase.IDLE,
                                error = message,
                            )
                        }
                    },
                )
            }
            is Result.Error -> {
                _state.update {
                    it.copy(
                        phase = VoicePhase.IDLE,
                        error = tts.message ?: "Text-to-speech failed",
                    )
                }
            }
            is Result.Loading -> Unit
        }
    }

    fun bargeIn() {
        audioPlayer.stop()
        _state.update { it.copy(phase = VoicePhase.IDLE) }
        startListening()
    }

    fun stopPlayback() {
        audioPlayer.stop()
        if (_state.value.phase == VoicePhase.SPEAKING) {
            _state.update { it.copy(phase = VoicePhase.IDLE) }
        }
    }

    private suspend fun resolveParentMessageId(conversationId: String): String? {
        return when (val messagesResult = messageRepository.getMessages(conversationId)) {
            is Result.Success -> messagesResult.data.lastOrNull()?.messageId
            else -> null
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeTurnJob?.cancel()
        recorder.cancel()
        audioPlayer.stop()
    }
}

private fun com.librechat.android.core.model.Message.extractReadableText(): String {
    if (text.isNotBlank()) return text
    return content
        ?.filter { it.type == ContentType.TEXT }
        ?.joinToString(separator = "") { it.text.orEmpty() }
        .orEmpty()
}

/**
 * Voice picker: show favorites the user saved on the server. We only drop an entry when we are
 * sure the model is not in the current /api/models map (exact + case-insensitive). If the endpoint
 * key does not match the map (casing, custom names), we still show the favorite.
 */
private fun favoritesForVoicePicker(
    favorites: List<UserFavorite>,
    availableModels: Map<String, List<String>>,
): List<UserFavorite> {
    val candidates = favorites.filter {
        !it.agentId.isNullOrBlank() ||
            (!it.endpoint.isNullOrBlank() && !it.model.isNullOrBlank())
    }
    if (availableModels.isEmpty()) return candidates
    return candidates.filter { favorite ->
        if (!favorite.agentId.isNullOrBlank()) return@filter true
        val ep = favorite.endpoint ?: return@filter false
        val model = favorite.model ?: return@filter false
        val models = modelsForEndpoint(ep, availableModels) ?: return@filter true
        if (models.isEmpty()) return@filter true
        models.any { m -> m == model || m.equals(model, ignoreCase = true) }
    }
}

private fun modelsForEndpoint(
    endpoint: String,
    availableModels: Map<String, List<String>>,
): List<String>? {
    availableModels[endpoint]?.let { return it }
    val key = availableModels.keys.firstOrNull { it.equals(endpoint, ignoreCase = true) }
    return key?.let { availableModels[it] }
}

private fun com.librechat.android.core.model.EModelEndpoint.toSerialName(): String = when (this) {
    com.librechat.android.core.model.EModelEndpoint.AZURE_OPENAI -> "azureOpenAI"
    com.librechat.android.core.model.EModelEndpoint.OPENAI -> "openAI"
    com.librechat.android.core.model.EModelEndpoint.GOOGLE -> "google"
    com.librechat.android.core.model.EModelEndpoint.ANTHROPIC -> "anthropic"
    com.librechat.android.core.model.EModelEndpoint.ASSISTANTS -> "assistants"
    com.librechat.android.core.model.EModelEndpoint.AZURE_ASSISTANTS -> "azureAssistants"
    com.librechat.android.core.model.EModelEndpoint.AGENTS -> "agents"
    com.librechat.android.core.model.EModelEndpoint.CUSTOM -> "custom"
    com.librechat.android.core.model.EModelEndpoint.BEDROCK -> "bedrock"
}
