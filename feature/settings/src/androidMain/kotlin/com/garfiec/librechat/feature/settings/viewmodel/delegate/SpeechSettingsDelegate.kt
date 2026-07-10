package com.garfiec.librechat.feature.settings.viewmodel.delegate

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.ServerSttGate
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.core.model.speech.TtsVoice
import com.garfiec.librechat.feature.settings.screen.DeviceVoiceInfo
import com.garfiec.librechat.feature.settings.viewmodel.SettingsStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles TTS voice selection, test playback, device voice loading, and MediaPlayer lifecycle.
 */
class SpeechSettingsDelegate(
    private val stateHandle: SettingsStateHandle,
    private val context: Context,
    private val speechRepository: SpeechRepository,
    private val settingsDataStore: SettingsDataStore,
    private val ioDispatcher: CoroutineDispatcher,
) : SpeechSettingsContract {

    private var currentMediaPlayer: MediaPlayer? = null
    private var deviceTtsEngine: TextToSpeech? = null

    // Loads + latches the server-STT flag with the shared "unknown vs disabled" retry policy. Until
    // it has resolved once (gate.loaded), serverSttEnabled=false is "unknown", not "disabled" — so
    // opening the STT dialog retries rather than permanently hiding the External engine.
    private val serverSttGate = ServerSttGate(speechRepository)

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
        stateHandle.scope.launch {
            // TextToSpeech construction touches disk/IPC; build it off the Main thread.
            val engine = withContext(ioDispatcher) {
                lateinit var tts: TextToSpeech
                tts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        // .voices enumeration is heavy; do it off Main before publishing.
                        stateHandle.scope.launch {
                            val voices = withContext(ioDispatcher) {
                                tts.voices?.map { voice ->
                                    DeviceVoiceInfo(
                                        name = voice.name,
                                        locale = voice.locale.displayName,
                                    )
                                }?.sortedBy { it.name } ?: emptyList()
                            }
                            stateHandle.update { copy(availableDeviceVoices = voices) }
                        }
                    }
                }
                tts
            }
            deviceTtsEngine = engine
        }
    }

    override fun loadSpeechConfig() {
        stateHandle.scope.launch {
            // A null result means a transient failure — leave the gate unlatched so opening the STT
            // dialog retries rather than trusting it as a definitive "server STT disabled".
            val enabled = serverSttGate.refresh() ?: false
            stateHandle.update { copy(serverSttEnabled = enabled) }
        }
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
                    playAudioBytes(result.data)
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
        val tts = deviceTtsEngine ?: return
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        if (!voiceName.isNullOrBlank()) {
            val targetVoice = tts.voices?.find { it.name == voiceName }
            if (targetVoice != null) {
                tts.voice = targetVoice
            }
        }
        stateHandle.update { copy(isTtsPreviewPlaying = true) }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { /* no-op */ }
            override fun onDone(utteranceId: String?) {
                stateHandle.update { copy(isTtsPreviewPlaying = false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                stateHandle.update { copy(isTtsPreviewPlaying = false) }
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_preview")
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
                    playAudioBytes(result.data, isPreview = true)
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
        deviceTtsEngine?.stop()
        currentMediaPlayer?.release()
        currentMediaPlayer = null
        stateHandle.update { copy(isTtsPreviewPlaying = false) }
    }

    // STT detail dialogs

    override fun showSttDetailDialog() {
        // Retry the speech-config fetch if it hasn't succeeded yet, so a transient error at settings
        // launch doesn't permanently hide the External engine for the whole session.
        if (!serverSttGate.loaded) loadSpeechConfig()
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

    /**
     * Plays audio bytes via MediaPlayer. Attaches listeners before calling prepare()
     * and wraps prepare/start in try-catch to release on failure (fixes resource leak).
     */
    private suspend fun playAudioBytes(audioBytes: ByteArray, isPreview: Boolean = false) {
        try {
            // Stop any currently playing audio
            currentMediaPlayer?.release()
            currentMediaPlayer = null

            // File write + MediaPlayer prepare are blocking; run them off the Main thread.
            val mediaPlayer = withContext(ioDispatcher) {
                val voiceTestDir = File(context.cacheDir, "voice_test").apply { mkdirs() }
                val tempFile = File.createTempFile("voice_test", ".mp3", voiceTestDir)
                tempFile.deleteOnExit()
                tempFile.writeBytes(audioBytes)

                val mp = MediaPlayer()
                // Attach listeners BEFORE calling prepare() to avoid resource leak
                mp.setDataSource(tempFile.absolutePath)
                // The MediaPlayer is built on a Looper-less ioDispatcher thread, so these
                // callbacks fire on the Main looper. release() + temp-file delete are blocking,
                // so hand them to ioDispatcher rather than running them on Main.
                mp.setOnCompletionListener { player ->
                    currentMediaPlayer = null
                    if (isPreview) {
                        stateHandle.update { copy(isTtsPreviewPlaying = false) }
                    }
                    stateHandle.scope.launch(ioDispatcher) {
                        player.release()
                        tempFile.delete()
                    }
                }
                mp.setOnErrorListener { player, what, extra ->
                    Logger.e { "MediaPlayer error: what=$what, extra=$extra" }
                    currentMediaPlayer = null
                    stateHandle.update {
                        copy(error = "Audio playback failed", isTtsPreviewPlaying = false)
                    }
                    stateHandle.scope.launch(ioDispatcher) {
                        player.release()
                        tempFile.delete()
                    }
                    true
                }
                try {
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    // Release MediaPlayer if prepare() or start() throws
                    mp.release()
                    tempFile.delete()
                    throw e
                }
                mp
            }

            currentMediaPlayer = mediaPlayer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Failed to play audio" }
            stateHandle.update {
                copy(
                    error = "Audio playback failed: ${e.localizedMessage}",
                    isTtsPreviewPlaying = false,
                )
            }
        }
    }

    /**
     * Releases TTS engine and media player resources. Call from ViewModel.onCleared().
     */
    override fun release() {
        currentMediaPlayer?.release()
        currentMediaPlayer = null
        deviceTtsEngine?.shutdown()
        deviceTtsEngine = null
    }
}
