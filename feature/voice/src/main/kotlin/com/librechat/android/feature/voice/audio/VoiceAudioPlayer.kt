package com.librechat.android.feature.voice.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

class VoiceAudioPlayer(
    private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null

    fun play(
        audioBytes: ByteArray,
        onCompleted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        try {
            val file = File.createTempFile("voice_tts_", ".mp3", context.cacheDir)
            file.deleteOnExit()
            file.writeBytes(audioBytes)
            tempFile = file
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    mainHandler.post { onCompleted() }
                }
                setOnErrorListener { _, _, _ ->
                    stop()
                    mainHandler.post { onError("TTS playback failed") }
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            stop()
            mainHandler.post { onError(e.message ?: "TTS playback failed") }
        }
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun stop() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        tempFile?.delete()
        tempFile = null
    }
}
