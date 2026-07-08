package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.feature.chat.viewmodel.TtsHandle
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

private const val DEFAULT_SPEECH_RATE = 0.5f
private const val MIN_SPEECH_RATE = 0.0f
private const val MAX_SPEECH_RATE = 1.0f

class IosTts(
    private val handle: TtsHandle,
    private val speechRepository: SpeechRepository,
    private val settingsDataStore: SettingsDataStore,
    private val getMessageText: (String) -> String,
) : PlatformTts {

    private val synthesizer = AVSpeechSynthesizer()

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance,
        ) {
            handle.update { voice = voice.copy(currentlyReadingMessageId = null) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didCancelSpeechUtterance: AVSpeechUtterance,
        ) {
            handle.update { voice = voice.copy(currentlyReadingMessageId = null) }
        }
    }

    init {
        synthesizer.delegate = delegate
    }

    override fun readAloud(messageId: String) {
        if (handle.state.currentlyReadingMessageId == messageId) {
            stopReading()
            return
        }

        stopReading()

        val text = getMessageText(messageId)
        if (text.isBlank()) return

        handle.update { voice = voice.copy(currentlyReadingMessageId = messageId) }

        handle.scope.launch {
            val source = settingsDataStore.ttsSource.first()
            if (source == "server") {
                readAloudViaServer(text)
            } else {
                val rate = settingsDataStore.ttsSpeechRate.first()
                val pitch = settingsDataStore.ttsPitch.first()
                readAloudViaDevice(text, rate, pitch)
            }
        }
    }

    private fun readAloudViaDevice(text: String, speechRate: Float, pitch: Float) {
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        // AVSpeechUtterance rate: 0.0-1.0, default ~0.5
        // Android TTS rate: 0.1-4.0, default 1.0. Scale proportionally.
        utterance.rate = (speechRate * DEFAULT_SPEECH_RATE).coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        // AVSpeechUtterance pitch: 0.5-2.0, default 1.0 (same as Android)
        utterance.pitchMultiplier = pitch.coerceIn(0.5f, 2.0f)
        utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(null)
        synthesizer.speakUtterance(utterance)
    }

    private suspend fun readAloudViaServer(text: String) {
        when (val result = speechRepository.synthesizeSpeech(text)) {
            is Result.Success -> {
                // Server TTS returns audio bytes; fall back to device TTS
                // since AVAudioPlayer needs additional platform plumbing
                val rate = settingsDataStore.ttsSpeechRate.first()
                val pitch = settingsDataStore.ttsPitch.first()
                readAloudViaDevice(text, rate, pitch)
            }
            is Result.Error -> {
                handle.update {
                    voice = voice.copy(currentlyReadingMessageId = null)
                    error = result.message ?: "Server TTS request failed"
                }
            }
            is Result.Loading -> { /* no-op */ }
        }
    }

    override fun stopReading() {
        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        handle.update { voice = voice.copy(currentlyReadingMessageId = null) }
    }

    override fun maybeAutoReadResponse(responseText: String) {
        if (handle.state.inputText.isNotBlank()) return

        handle.scope.launch {
            val autoRead = settingsDataStore.autoReadEnabled.first()
            if (!autoRead) return@launch

            val syntheticId = "auto_read_${NSDate().timeIntervalSince1970().toLong()}"
            handle.update { voice = voice.copy(currentlyReadingMessageId = syntheticId) }

            val source = settingsDataStore.ttsSource.first()
            if (source == "server") {
                readAloudViaServer(responseText)
            } else {
                val rate = settingsDataStore.ttsSpeechRate.first()
                val pitch = settingsDataStore.ttsPitch.first()
                readAloudViaDevice(responseText, rate, pitch)
            }
        }
    }

    override fun release() {
        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        synthesizer.delegate = null
    }
}
