package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.speech.SpeechConfig
import com.garfiec.librechat.core.model.speech.SpeechToTextResponse
import com.garfiec.librechat.core.model.speech.TextToSpeechRequest
import com.garfiec.librechat.core.model.speech.TtsVoice
import com.garfiec.librechat.core.network.api.SpeechApi

class SpeechRepositoryImpl(
    private val speechApi: SpeechApi,
) : SpeechRepository {

    override suspend fun transcribeAudio(
        audioData: ByteArray,
        mimeType: String,
    ): Result<SpeechToTextResponse> =
        safeApiCall { speechApi.speechToText(audioData, mimeType) }

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

    override suspend fun isServerSttEnabled(): Result<Boolean> =
        when (val result = getSpeechConfig()) {
            is Result.Success -> Result.Success(result.data.sttExternal)
            is Result.Error -> result
            is Result.Loading -> Result.Loading
        }
}
