package com.garfiec.librechat.feature.chat.viewmodel.delegate

import android.content.Context
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.feature.chat.audio.VoiceRecorder
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VoiceInputDelegate(
    private val stateHandle: ChatStateHandle,
    private val appContext: Context,
    private val speechRepository: SpeechRepository,
    private val autoSendAfterStt: StateFlow<Boolean>,
    private val ioDispatcher: CoroutineDispatcher,
    private val onTranscriptionComplete: () -> Unit,
) {

    private var voiceRecorder: VoiceRecorder? = null
    // Tracks the in-flight VoiceRecorder.start(). stop()/cancel() join it before touching the
    // recorder so a tap-stop (or second tap) during the MediaRecorder warm-up can't race the
    // not-yet-finished start() and orphan a recording that keeps the mic hot.
    private var startJob: Job? = null

    fun startRecording() {
        if (stateHandle.state.isRecording) return
        // Set state + assign the recorder synchronously on Main so the isRecording guard above
        // and stopRecording()/cancelRecording() see a consistent recorder during start()'s warm-up.
        val recorder = VoiceRecorder(appContext, ioDispatcher)
        voiceRecorder = recorder
        stateHandle.update { copy(isRecording = true) }
        startJob = stateHandle.scope.launch {
            try {
                recorder.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (voiceRecorder === recorder) {
                    voiceRecorder = null
                    stateHandle.update {
                        copy(isRecording = false, error = "Could not start recording: ${e.message}")
                    }
                }
            }
        }
    }

    fun stopRecording() {
        val recorder = voiceRecorder ?: return
        val mimeType = recorder.mimeType
        voiceRecorder = null
        stateHandle.update { copy(isRecording = false, isTranscribing = true) }

        stateHandle.scope.launch {
            // Ensure start() finished before we stop the recorder.
            startJob?.join()
            val audioData = recorder.stop()
            if (audioData == null || audioData.isEmpty()) {
                stateHandle.update { copy(isTranscribing = false, error = "Recording was empty") }
                return@launch
            }

            when (val result = speechRepository.transcribeAudio(audioData, mimeType)) {
                is Result.Success -> {
                    val transcribedText = result.data.text
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
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        stateHandle.update { copy(isRecording = false) }
        // Join start() first so cancel() can't race the in-flight warm-up; cancel() then runs
        // MediaRecorder.stop()/release() off the Main thread.
        stateHandle.scope.launch {
            startJob?.join()
            recorder.cancel()
        }
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
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        // Called from onCleared(), at which point stateHandle.scope is already cancelled — a
        // launch on it would never run and the recorder/mic would leak. Run the cleanup on a
        // detached IO scope so the blocking MediaRecorder.stop()/release() still happens off the
        // Main thread and is guaranteed to complete (cancel() uses NonCancellable internally).
        CoroutineScope(ioDispatcher).launch { recorder.cancel() }
    }
}
