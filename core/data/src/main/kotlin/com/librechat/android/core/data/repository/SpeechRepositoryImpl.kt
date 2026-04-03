package com.librechat.android.core.data.repository

import com.librechat.android.core.common.result.Result
import com.librechat.android.core.common.result.safeApiCall
import com.librechat.android.core.model.speech.SpeechConfig
import com.librechat.android.core.model.speech.SpeechToTextResponse
import com.librechat.android.core.model.speech.TextToSpeechRequest
import com.librechat.android.core.model.speech.TtsVoice
import com.librechat.android.core.network.api.SpeechApi

class SpeechRepositoryImpl(
    private val speechApi: SpeechApi,
) : SpeechRepository {

    override suspend fun transcribeAudio(
        audioData: ByteArray,
        mimeType: String,
        language: String?,
    ): Result<SpeechToTextResponse> =
        safeApiCall { speechApi.speechToText(audioData, mimeType, language) }

    override suspend fun synthesizeSpeech(
        text: String,
        voice: String?,
        model: String?,
    ): Result<ByteArray> =
        safeApiCall { speechApi.textToSpeechManual(TextToSpeechRequest(text, voice, model)) }

    override suspend fun getVoices(): Result<List<TtsVoice>> =
        safeApiCall { speechApi.getVoices() }

    override suspend fun getSpeechConfig(): Result<SpeechConfig> =
        safeApiCall { speechApi.getSpeechConfig() }
}
