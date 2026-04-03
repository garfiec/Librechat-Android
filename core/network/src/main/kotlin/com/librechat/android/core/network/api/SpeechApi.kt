package com.librechat.android.core.network.api

import com.librechat.android.core.model.speech.SpeechConfig
import com.librechat.android.core.model.speech.SpeechToTextResponse
import com.librechat.android.core.model.speech.TextToSpeechRequest
import com.librechat.android.core.model.speech.TtsVoice
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.path

class SpeechApi constructor(
    private val client: HttpClient,
) {
    suspend fun speechToText(
        audioData: ByteArray,
        mimeType: String,
        language: String? = null,
    ): SpeechToTextResponse {
        val extension = mimeTypeToExtension(mimeType)
        return client.submitFormWithBinaryData(
            formData = formData {
                append("audio", audioData, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"audio.$extension\"")
                    append(HttpHeaders.ContentType, mimeType)
                })
                if (!language.isNullOrBlank()) {
                    append("language", language)
                }
            },
        ) {
            url { path("api/files/speech/stt") }
        }.body()
    }

    private fun mimeTypeToExtension(mimeType: String): String = when {
        mimeType.contains("ogg") -> "ogg"
        mimeType.contains("webm") -> "webm"
        mimeType.contains("mp4") || mimeType.contains("m4a") -> "m4a"
        mimeType.contains("wav") -> "wav"
        mimeType.contains("mp3") || mimeType.contains("mpeg") -> "mp3"
        mimeType.contains("flac") -> "flac"
        mimeType.contains("3gpp") || mimeType.contains("3gp") -> "3gp"
        else -> "ogg"
    }

    suspend fun textToSpeech(request: TextToSpeechRequest): ByteArray =
        client.post {
            url { path("api/files/speech/tts") }
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun textToSpeechManual(request: TextToSpeechRequest): ByteArray =
        client.post {
            url { path("api/files/speech/tts/manual") }
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun getVoices(): List<TtsVoice> =
        client.get {
            url { path("api/files/speech/tts/voices") }
        }.body()

    suspend fun getSpeechConfig(): SpeechConfig =
        client.get {
            url { path("api/files/speech/config/get") }
        }.body()
}
