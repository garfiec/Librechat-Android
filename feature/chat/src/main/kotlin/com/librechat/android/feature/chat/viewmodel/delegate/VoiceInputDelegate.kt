package com.librechat.android.feature.chat.viewmodel.delegate

import android.content.Context
import com.librechat.android.core.common.result.Result
import com.librechat.android.core.data.datastore.SettingsDataStore
import com.librechat.android.core.data.repository.SpeechRepository
import com.librechat.android.core.model.speech.resolveWhisperLanguageCode
import com.librechat.android.feature.chat.audio.VoiceRecorder
import com.librechat.android.feature.chat.viewmodel.ChatStateHandle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoiceInputDelegate(
    private val stateHandle: ChatStateHandle,
    private val appContext: Context,
    private val speechRepository: SpeechRepository,
    private val settingsDataStore: SettingsDataStore,
    private val autoSendAfterStt: StateFlow<Boolean>,
    private val onTranscriptionComplete: () -> Unit,
) {

    private var voiceRecorder: VoiceRecorder? = null

    fun startRecording() {
        if (stateHandle.state.isRecording) return
        try {
            val recorder = VoiceRecorder(appContext)
            recorder.start()
            voiceRecorder = recorder
            stateHandle.update { copy(isRecording = true) }
        } catch (e: Exception) {
            stateHandle.update { copy(error = "Could not start recording: ${e.message}") }
        }
    }

    fun stopRecording() {
        val recorder = voiceRecorder ?: return
        val mimeType = recorder.mimeType
        val audioData = recorder.stop()
        voiceRecorder = null
        stateHandle.update { copy(isRecording = false) }

        if (audioData == null || audioData.isEmpty()) {
            stateHandle.update { copy(error = "Recording was empty") }
            return
        }

        stateHandle.update { copy(isTranscribing = true) }
        stateHandle.scope.launch {
            val language = resolveWhisperLanguageCode(settingsDataStore.sttLanguage.first())
            when (val result = speechRepository.transcribeAudio(audioData, mimeType, language)) {
                is Result.Success -> {
                    val transcribedText = result.data.text.orEmpty()
                    val currentInput = stateHandle.state.inputText
                    val separator = if (currentInput.isNotBlank() && !currentInput.endsWith(" ")) " " else ""
                    stateHandle.update {
                        copy(
                            inputText = currentInput + separator + transcribedText,
                            isTranscribing = false,
                        )
                    }
                    // Auto-send if enabled and transcribed text is non-empty and not already streaming
                    if (autoSendAfterStt.value && transcribedText.isNotBlank() && !stateHandle.state.isStreaming) {
                        onTranscriptionComplete()
                    }
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(
                            isTranscribing = false,
                            error = result.message ?: "Transcription failed",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun cancelRecording() {
        voiceRecorder?.cancel()
        voiceRecorder = null
        stateHandle.update { copy(isRecording = false) }
    }

    /**
     * Called when Android's on-device speech recognizer returns a result.
     * Appends the transcribed text to the input field, and auto-sends if enabled.
     */
    fun onDeviceSpeechResult(transcribedText: String) {
        if (transcribedText.isBlank()) return
        val currentInput = stateHandle.state.inputText
        val separator = if (currentInput.isNotBlank() && !currentInput.endsWith(" ")) " " else ""
        stateHandle.update {
            copy(inputText = currentInput + separator + transcribedText)
        }
        // Auto-send if enabled and not already streaming
        if (autoSendAfterStt.value && !stateHandle.state.isStreaming) {
            onTranscriptionComplete()
        }
    }

    fun loadSpeechConfig() {
        stateHandle.scope.launch {
            when (val result = speechRepository.getSpeechConfig()) {
                is Result.Success -> {
                    stateHandle.update { copy(serverSttEnabled = result.data.sttExternal) }
                }
                is Result.Error -> {
                    // If we can't fetch the config, assume server STT is not available
                    stateHandle.update { copy(serverSttEnabled = false) }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun release() {
        voiceRecorder?.cancel()
        voiceRecorder = null
    }
}
