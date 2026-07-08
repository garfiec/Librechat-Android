package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.feature.chat.viewmodel.VoiceHandle
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus

/**
 * iOS implementation of [PlatformVoiceInput] using AVAudioEngine + SFSpeechRecognizer
 * for real-time on-device speech recognition.
 */
class IosVoiceInput(
    private val handle: VoiceHandle,
    private val onTranscriptionComplete: () -> Unit,
) : PlatformVoiceInput {

    private val log = Logger.withTag("IosVoiceInput")

    private var audioEngine: AVAudioEngine? = null
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest? = null
    private var recognitionTask: SFSpeechRecognitionTask? = null
    private var speechRecognizer: SFSpeechRecognizer? = null

    @OptIn(ExperimentalForeignApi::class)
    override fun startRecording() {
        log.d { "startRecording() called, isRecording=${handle.state.isRecording}" }
        if (handle.state.isRecording) return

        // Check authorization
        val authStatus = SFSpeechRecognizer.authorizationStatus()
        log.d { "SFSpeechRecognizer authStatus=$authStatus" }
        when (authStatus) {
            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined -> {
                log.d { "Requesting speech recognition authorization..." }
                SFSpeechRecognizer.requestAuthorization { status ->
                    log.d { "Authorization callback: status=$status" }
                    if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                        handle.scope.launch { beginRecording() }
                    } else {
                        log.w { "Speech recognition permission denied" }
                        handle.setError("Speech recognition permission denied")
                    }
                }
                return
            }
            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized -> {
                log.d { "Already authorized, beginning recording" }
                beginRecording()
            }
            else -> {
                log.w { "Speech recognition not available, authStatus=$authStatus" }
                handle.setError("Speech recognition not available. Check Settings > Privacy > Speech Recognition.")
                return
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun beginRecording() {
        try {
            val locale = NSLocale.currentLocale
            log.d { "beginRecording() locale=${locale.languageCode}" }
            val recognizer = SFSpeechRecognizer(locale = locale)
            val available = recognizer.isAvailable()
            log.d { "SFSpeechRecognizer available=$available" }
            if (!available) {
                val msg = "Speech recognition not available on this device (locale: ${locale.languageCode}). This feature requires a real device."
                log.w { msg }
                handle.setError(msg)
                return
            }
            speechRecognizer = recognizer

            // Configure audio session
            log.d { "Configuring audio session..." }
            val audioSession = AVAudioSession.sharedInstance()
            audioSession.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeDefault,
                options = 0u,
                error = null,
            )
            audioSession.setActive(true, error = null)
            log.d { "Audio session configured" }

            val engine = AVAudioEngine()
            val request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true

            recognitionRequest = request
            audioEngine = engine

            log.d { "Creating recognition task..." }
            val task = recognizer.recognitionTaskWithRequest(request) { result, error ->
                if (result != null) {
                    val text = result.bestTranscription.formattedString
                    log.d { "Recognition result: '$text', isFinal=${result.isFinal()}" }
                    handle.update { composer = composer.copy(inputText = text) }

                    if (result.isFinal()) {
                        log.d { "Final result received, stopping" }
                        cleanupAudio()
                        handle.update { voice = voice.copy(isRecording = false) }
                    }
                }
                if (error != null) {
                    log.e { "Recognition error: ${error.localizedDescription}, isRecording=${handle.state.isRecording}" }
                    if (handle.state.isRecording) {
                        cleanupAudio()
                        val message = "Speech recognition error: ${error.localizedDescription}"
                        handle.update {
                            voice = voice.copy(isRecording = false)
                            this.error = message
                        }
                    }
                }
            }
            recognitionTask = task

            // Install audio tap
            val inputNode = engine.inputNode
            val recordingFormat = inputNode.outputFormatForBus(0u)
            log.d { "Installing audio tap, format=$recordingFormat" }
            inputNode.installTapOnBus(
                bus = 0u,
                bufferSize = 1024u,
                format = recordingFormat,
            ) { buffer, _ ->
                if (buffer != null) {
                    request.appendAudioPCMBuffer(buffer)
                }
            }

            engine.prepare()
            engine.startAndReturnError(null)
            log.d { "Audio engine started, setting isRecording=true" }

            handle.update { voice = voice.copy(isRecording = true) }
        } catch (e: Exception) {
            log.e(e) { "Failed to start recording: ${e.message}" }
            cleanupAudio()
            handle.setError("Could not start recording: ${e.message}")
        }
    }

    override fun stopRecording() {
        if (!handle.state.isRecording) return

        // End the recognition request so we get the final result
        recognitionRequest?.endAudio()
        cleanupAudio()
        handle.update { voice = voice.copy(isRecording = false) }
    }

    override fun cancelRecording() {
        recognitionTask?.cancel()
        cleanupAudio()
        handle.update {
            voice = voice.copy(isRecording = false)
            composer = composer.copy(inputText = "")
        }
    }

    override fun onDeviceSpeechResult(transcribedText: String) {
        if (transcribedText.isBlank()) return
        val currentInput = handle.state.inputText
        val separator = if (currentInput.isNotBlank() && !currentInput.endsWith(" ")) " " else ""
        handle.update {
            composer = composer.copy(inputText = currentInput + separator + transcribedText)
        }
    }

    override fun loadSpeechConfig() {
        // iOS uses on-device speech recognition only; no server STT config needed
    }

    override fun release() {
        recognitionTask?.cancel()
        cleanupAudio()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun cleanupAudio() {
        audioEngine?.let { engine ->
            if (engine.isRunning()) {
                engine.stop()
                engine.inputNode.removeTapOnBus(0u)
            }
        }
        audioEngine = null
        recognitionRequest = null
        recognitionTask = null
        speechRecognizer = null

        // Deactivate audio session
        try {
            AVAudioSession.sharedInstance().setActive(
                active = false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = null,
            )
        } catch (_: Exception) {
            // Best effort
        }
    }
}
