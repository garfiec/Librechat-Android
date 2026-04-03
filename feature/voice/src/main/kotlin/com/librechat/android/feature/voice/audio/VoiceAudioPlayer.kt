package com.librechat.android.feature.voice.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Audio player that supports queued, gapless playback of multiple audio chunks.
 * Call [enqueue] to add chunks; the first chunk starts playing immediately.
 * Subsequent chunks play back-to-back as soon as the previous one finishes.
 * Call [markEndOfStream] once all chunks have been enqueued so the player
 * knows when to fire [onAllCompleted].
 */
class VoiceAudioPlayer(
    private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var currentFile: File? = null

    private val queue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var endOfStream = false
    private var allCompletedCallback: (() -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    fun enqueue(
        audioBytes: ByteArray,
        onAllCompleted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        allCompletedCallback = onAllCompleted
        errorCallback = onError
        queue.add(audioBytes)
        if (mediaPlayer == null) {
            playNext()
        }
    }

    fun markEndOfStream() {
        endOfStream = true
        if (mediaPlayer == null && queue.isEmpty()) {
            fireAllCompleted()
        }
    }

    /** Legacy single-shot playback (calls enqueue + markEndOfStream). */
    fun play(
        audioBytes: ByteArray,
        onCompleted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        enqueue(audioBytes, onCompleted, onError)
        markEndOfStream()
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true || queue.isNotEmpty()

    fun stop() {
        queue.clear()
        endOfStream = false
        allCompletedCallback = null
        errorCallback = null
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        currentFile?.delete()
        currentFile = null
    }

    private fun playNext() {
        val bytes = queue.poll()
        if (bytes == null) {
            if (endOfStream) fireAllCompleted()
            return
        }
        try {
            val file = File.createTempFile("voice_tts_", ".mp3", context.cacheDir)
            file.deleteOnExit()
            file.writeBytes(bytes)
            currentFile?.delete()
            currentFile = file
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    playNext()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    mediaPlayer = null
                    val cb = errorCallback
                    stop()
                    mainHandler.post { cb?.invoke("TTS playback failed") }
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            val cb = errorCallback
            stop()
            mainHandler.post { cb?.invoke(e.message ?: "TTS playback failed") }
        }
    }

    private fun fireAllCompleted() {
        val cb = allCompletedCallback
        allCompletedCallback = null
        errorCallback = null
        mainHandler.post { cb?.invoke() }
    }
}
