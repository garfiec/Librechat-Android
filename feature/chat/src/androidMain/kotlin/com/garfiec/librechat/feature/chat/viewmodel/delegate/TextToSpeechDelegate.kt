package com.garfiec.librechat.feature.chat.viewmodel.delegate

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.feature.chat.viewmodel.TtsHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TextToSpeechDelegate(
    private val handle: TtsHandle,
    private val appContext: Context,
    private val speechRepository: SpeechRepository,
    private val settingsDataStore: SettingsDataStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val getMessageText: (String) -> String,
) {

    private var ttsEngine: TextToSpeech? = null
    private var ttsReady = false
    private var serverTtsPlayer: MediaPlayer? = null

    private fun getOrInitTts(onReady: () -> Unit) {
        if (ttsReady && ttsEngine != null) {
            onReady()
            return
        }
        ttsEngine?.shutdown()
        ttsEngine = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        handle.update { voice = voice.copy(currentlyReadingMessageId = null) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handle.update {
                            voice = voice.copy(currentlyReadingMessageId = null)
                            error = "Text-to-speech playback error"
                        }
                    }
                })
                onReady()
            } else {
                handle.update {
                    voice = voice.copy(currentlyReadingMessageId = null)
                    error = "Text-to-speech engine not available on this device"
                }
            }
        }
    }

    fun readAloud(messageId: String) {
        // If already reading this message, stop
        if (handle.state.currentlyReadingMessageId == messageId) {
            stopReading()
            return
        }

        // Stop any current playback first
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
                val voiceName = settingsDataStore.ttsVoiceName.first()
                readAloudViaDevice(messageId, text, rate, pitch, voiceName)
            }
        }
    }

    internal fun readAloudViaDevice(
        messageId: String,
        text: String,
        speechRate: Float,
        pitch: Float,
        voiceName: String,
    ) {
        getOrInitTts {
            val engine = ttsEngine ?: return@getOrInitTts
            engine.setSpeechRate(speechRate)
            engine.setPitch(pitch)
            if (voiceName.isNotBlank()) {
                engine.voices?.find { it.name == voiceName }?.let { voice ->
                    engine.setVoice(voice)
                }
            }
            val params = Bundle()
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, messageId)
        }
    }

    internal suspend fun readAloudViaServer(text: String) {
        when (val result = speechRepository.synthesizeSpeech(text)) {
            is Result.Success -> {
                try {
                    val audioBytes = result.data
                    serverTtsPlayer?.release()
                    serverTtsPlayer = withContext(ioDispatcher) {
                        val ttsDir = File(appContext.cacheDir, "tts").apply { mkdirs() }
                        val tempFile = File.createTempFile("tts_", ".mp3", ttsDir)
                        tempFile.deleteOnExit()
                        tempFile.writeBytes(audioBytes)

                        MediaPlayer().apply {
                            setDataSource(tempFile.absolutePath)
                            // MediaPlayer is created on a Looper-less ioDispatcher thread, so
                            // these callbacks fire on the Main looper. release() + temp-file
                            // delete are blocking, so hand them to ioDispatcher.
                            setOnCompletionListener { mp ->
                                serverTtsPlayer = null
                                handle.update { voice = voice.copy(currentlyReadingMessageId = null) }
                                handle.scope.launch(ioDispatcher) {
                                    mp.release()
                                    tempFile.delete()
                                }
                            }
                            setOnErrorListener { mp, _, _ ->
                                serverTtsPlayer = null
                                handle.update {
                                    voice = voice.copy(currentlyReadingMessageId = null)
                                    error = "Server TTS playback failed"
                                }
                                handle.scope.launch(ioDispatcher) {
                                    mp.release()
                                    tempFile.delete()
                                }
                                true
                            }
                            prepare()
                            start()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handle.update {
                        voice = voice.copy(currentlyReadingMessageId = null)
                        error = "Server TTS playback failed: ${e.message}"
                    }
                }
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

    fun stopReading() {
        ttsEngine?.stop()
        serverTtsPlayer?.let {
            it.stop()
            it.release()
        }
        serverTtsPlayer = null
        handle.update { voice = voice.copy(currentlyReadingMessageId = null) }
    }

    /**
     * Checks the auto-read preference and, if enabled, reads the completed AI
     * response aloud via TTS. Skips auto-read when the user has already started
     * typing a new message (inputText is not blank).
     *
     * Uses a synthetic message ID ("auto_read") because the real message ID
     * may not be available yet (Room observer hasn't emitted the final message).
     */
    fun maybeAutoReadResponse(responseText: String) {
        // Don't auto-read if the user has already started typing
        if (handle.state.inputText.isNotBlank()) return

        handle.scope.launch {
            val autoRead = settingsDataStore.autoReadEnabled.first()
            if (!autoRead) return@launch

            val syntheticId = "auto_read_${System.currentTimeMillis()}"
            handle.update { voice = voice.copy(currentlyReadingMessageId = syntheticId) }

            val source = settingsDataStore.ttsSource.first()
            if (source == "server") {
                readAloudViaServer(responseText)
            } else {
                val rate = settingsDataStore.ttsSpeechRate.first()
                val pitch = settingsDataStore.ttsPitch.first()
                val voiceName = settingsDataStore.ttsVoiceName.first()
                readAloudViaDevice(syntheticId, responseText, rate, pitch, voiceName)
            }
        }
    }

    fun release() {
        ttsEngine?.shutdown()
        ttsEngine = null
        ttsReady = false
        serverTtsPlayer?.release()
        serverTtsPlayer = null
    }
}
