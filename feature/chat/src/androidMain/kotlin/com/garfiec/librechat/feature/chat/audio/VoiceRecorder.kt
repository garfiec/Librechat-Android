package com.garfiec.librechat.feature.chat.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages audio recording using Android's MediaRecorder.
 * Records to a temporary file in the app's cache directory, returning
 * the raw bytes when stopped. Uses OGG/Opus on API 29+ and 3GP/AMR_NB on older devices.
 *
 * [prepare]/[start] and the final [stop] file read run on [ioDispatcher] so the
 * recorder setup and disk read never block the Main thread.
 */
class VoiceRecorder(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isCurrentlyRecording = false

    val isRecording: Boolean
        get() = isCurrentlyRecording

    val mimeType: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "audio/ogg"
        } else {
            "audio/3gpp"
        }

    suspend fun start() {
        if (isCurrentlyRecording) return

        val extension = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "ogg" else "3gp"
        val voiceRecordingDir = File(context.cacheDir, "voice_recording").apply { mkdirs() }
        outputFile = File(voiceRecordingDir, "voice_recording_${System.currentTimeMillis()}.$extension")

        try {
            val recorder = withContext(ioDispatcher) {
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setOutputFormat(MediaRecorder.OutputFormat.OGG)
                        setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    } else {
                        setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    }
                    setAudioSamplingRate(16_000)
                    setAudioChannels(1)
                    setOutputFile(outputFile?.absolutePath)
                    prepare()
                    start()
                }
                recorder
            }

            mediaRecorder = recorder
            isCurrentlyRecording = true
        } catch (e: CancellationException) {
            cleanup()
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Failed to start voice recording" }
            cleanup()
            throw e
        }
    }

    suspend fun stop(): ByteArray? {
        if (!isCurrentlyRecording) return null

        return try {
            withContext(ioDispatcher) {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                mediaRecorder = null
                isCurrentlyRecording = false

                val file = outputFile
                val bytes = file?.readBytes()
                file?.delete()
                outputFile = null
                bytes
            }
        } catch (e: CancellationException) {
            cleanup()
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Failed to stop voice recording" }
            cleanup()
            null
        }
    }

    suspend fun cancel() {
        cleanup()
    }

    // Runs the blocking MediaRecorder.stop()/release() + file delete off the Main thread.
    // NonCancellable so it still completes when invoked from a cancelled coroutine (the
    // CancellationException catch blocks above) — otherwise the recorder would leak.
    private suspend fun cleanup() {
        withContext(ioDispatcher + NonCancellable) {
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
            } catch (_: Exception) {
                // Recorder may not have been started
            }
            mediaRecorder = null
            isCurrentlyRecording = false
            outputFile?.delete()
            outputFile = null
        }
    }
}
