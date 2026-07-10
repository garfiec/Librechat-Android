package com.garfiec.librechat.feature.settings.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.core.model.speech.TtsVoice
import com.garfiec.librechat.feature.settings.screen.DeviceVoiceInfo
import com.garfiec.librechat.feature.settings.viewmodel.SettingsStateHandle
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.launch
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.darwin.NSObject

private const val DEFAULT_SPEECH_RATE = 0.5f
private const val MIN_SPEECH_RATE = 0.0f
private const val MAX_SPEECH_RATE = 1.0f

class IosSpeechSettingsDelegate(
    private val stateHandle: SettingsStateHandle,
    private val speechRepository: SpeechRepository,
    private val settingsDataStore: SettingsDataStore,
) : SpeechSettingsContract {

    private val synthesizer = AVSpeechSynthesizer()

    private val synthesizerDelegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance,
        ) {
            stateHandle.update { copy(isTtsPreviewPlaying = false) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didCancelSpeechUtterance: AVSpeechUtterance,
        ) {
            stateHandle.update { copy(isTtsPreviewPlaying = false) }
        }
    }

    init {
        synthesizer.delegate = synthesizerDelegate
    }

    override fun loadVoices() {
        stateHandle.scope.launch {
            when (val result = speechRepository.getVoices()) {
                is Result.Success -> {
                    stateHandle.update { copy(availableVoices = result.data) }
                }
                is Result.Error -> {
                    Logger.d(result.exception) { "Failed to load voices: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    override fun loadDeviceVoices() {
        val voices = AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .map { voice ->
                DeviceVoiceInfo(
                    name = voice.name,
                    locale = voice.language,
                )
            }
            .sortedBy { it.name }
        stateHandle.update { copy(availableDeviceVoices = voices) }
    }

    override fun loadSpeechConfig() {
        // iOS has no server-STT (External) recording path yet — IosVoiceInput always uses
        // SFSpeechRecognizer — so intentionally leave serverSttEnabled=false. This keeps the
        // External engine hidden in the shared STT dialog rather than offering an option that would
        // silently do nothing when selected. (Follow-up: implement iOS External via AVAudioRecorder
        // + speechRepository.transcribeAudio, then fetch getSpeechConfig() here like Android does.)
    }

    override fun setAutoSendAfterStt(enabled: Boolean) {
        stateHandle.scope.launch {
            settingsDataStore.setAutoSendAfterStt(enabled)
        }
    }

    override fun setAutoReadEnabled(enabled: Boolean) {
        stateHandle.scope.launch {
            settingsDataStore.setAutoReadEnabled(enabled)
        }
    }

    override fun selectVoice(voice: TtsVoice) {
        stateHandle.scope.launch {
            settingsDataStore.setSelectedVoiceId(voice.id)
        }
    }

    override fun testVoice() {
        val voice = stateHandle.state.selectedVoice
        stateHandle.scope.launch {
            val result = speechRepository.synthesizeSpeech(
                text = "This is a test of the selected voice.",
                voice = voice?.id,
            )
            when (result) {
                is Result.Success -> {
                    // Server returns audio bytes; fall back to device TTS for playback
                    previewDeviceTts("This is a test of the selected voice.", 1.0f, 1.0f, null)
                }
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Voice test failed") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    override fun previewDeviceTts(text: String, rate: Float, pitch: Float, voiceName: String?) {
        stopTtsPreview()
        stateHandle.update { copy(isTtsPreviewPlaying = true) }

        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        utterance.rate = (rate * DEFAULT_SPEECH_RATE).coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        utterance.pitchMultiplier = pitch.coerceIn(0.5f, 2.0f)

        if (!voiceName.isNullOrBlank()) {
            val targetVoice = AVSpeechSynthesisVoice.speechVoices()
                .filterIsInstance<AVSpeechSynthesisVoice>()
                .find { it.name == voiceName }
            if (targetVoice != null) {
                utterance.voice = targetVoice
            }
        } else {
            utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(null)
        }

        synthesizer.speakUtterance(utterance)
    }

    override fun previewServerTts(text: String, voice: String?, model: String?) {
        stopTtsPreview()
        stateHandle.update { copy(isTtsPreviewPlaying = true) }
        stateHandle.scope.launch {
            val result = speechRepository.synthesizeSpeech(
                text = text,
                voice = voice,
                model = model,
            )
            when (result) {
                is Result.Success -> {
                    // Server returns audio bytes; fall back to device TTS for playback
                    previewDeviceTts(text, 1.0f, 1.0f, null)
                }
                is Result.Error -> {
                    stateHandle.update {
                        copy(
                            isTtsPreviewPlaying = false,
                            error = result.message ?: "Voice preview failed",
                        )
                    }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    override fun stopTtsPreview() {
        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        stateHandle.update { copy(isTtsPreviewPlaying = false) }
    }

    override fun showSttDetailDialog() {
        stateHandle.update { copy(showSttDetailDialog = true) }
    }

    override fun dismissSttDetailDialog() {
        stateHandle.update { copy(showSttDetailDialog = false) }
    }

    override fun saveSttSettings(engine: String, language: String, onDevice: Boolean, endOfSpeech: Boolean) {
        stateHandle.update {
            copy(
                sttEngine = engine,
                sttLanguage = language,
                sttOnDevice = onDevice,
                sttEndOfSpeech = endOfSpeech,
                showSttDetailDialog = false,
            )
        }
        stateHandle.scope.launch {
            settingsDataStore.setSttEngine(engine)
            settingsDataStore.setSttLanguage(language)
            settingsDataStore.setSttOnDevice(onDevice)
            settingsDataStore.setSttEndOfSpeech(endOfSpeech)
        }
    }

    override fun showTtsDetailDialog() {
        stateHandle.update { copy(showTtsDetailDialog = true) }
    }

    override fun dismissTtsDetailDialog() {
        stateHandle.update { copy(showTtsDetailDialog = false) }
    }

    override fun saveTtsSettings(
        engine: String,
        voice: String,
        rate: Float,
        pitch: Float,
        deviceVoiceName: String,
        caching: Boolean,
        source: String,
    ) {
        stateHandle.update {
            copy(
                ttsEngine = engine,
                ttsVoice = voice,
                ttsCaching = caching,
                ttsSource = source,
                showTtsDetailDialog = false,
            )
        }
        stateHandle.scope.launch {
            settingsDataStore.setTtsSource(source)
            settingsDataStore.setTtsSpeechRate(rate)
            settingsDataStore.setTtsPitch(pitch)
            settingsDataStore.setTtsVoiceName(deviceVoiceName)
            settingsDataStore.setTtsEngine(engine)
            settingsDataStore.setTtsVoice(voice)
            settingsDataStore.setTtsCaching(caching)
        }
    }

    override fun release() {
        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
        synthesizer.delegate = null
    }
}
