package com.librechat.android.feature.voice.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import timber.log.Timber
import java.io.File

/**
 * Manages audio recording using Android's MediaRecorder.
 */
class VoiceRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isCurrentlyRecording = false
    private var lastRecordingMimeType: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "audio/ogg" else "audio/3gpp"

    val isRecording: Boolean
        get() = isCurrentlyRecording

    val mimeType: String
        get() = lastRecordingMimeType

    fun start() {
        if (isCurrentlyRecording) return

        val extension = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "ogg" else "3gp"
        outputFile = File(context.cacheDir, "voice_recording_${System.currentTimeMillis()}.$extension")

        try {
            try {
                startRecorder(
                    context = context,
                    outputFile = outputFile!!,
                    useOggOpus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                )
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Timber.w(e, "OGG/Opus recording failed, falling back to 3GP")
                    cleanup()
                    outputFile = File(context.cacheDir, "voice_recording_${System.currentTimeMillis()}.3gp")
                    startRecorder(
                        context = context,
                        outputFile = outputFile!!,
                        useOggOpus = false,
                    )
                } else {
                    throw e
                }
            }
            isCurrentlyRecording = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start voice recording")
            cleanup()
            throw e
        }
    }

    fun stop(): ByteArray? {
        if (!isCurrentlyRecording) return null

        return try {
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
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop voice recording")
            cleanup()
            null
        }
    }

    fun cancel() {
        cleanup()
    }

    private fun startRecorder(context: Context, outputFile: File, useOggOpus: Boolean) {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.apply {
            // VOICE_RECOGNITION is tuned for speech/STT; avoids some MIC routing issues on OEM devices.
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            if (useOggOpus) {
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            } else {
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            }
            setAudioSamplingRate(16_000)
            setAudioChannels(1)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        lastRecordingMimeType = if (useOggOpus) "audio/ogg" else "audio/3gpp"
        mediaRecorder = recorder
        this.outputFile = outputFile
    }

    private fun cleanup() {
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
