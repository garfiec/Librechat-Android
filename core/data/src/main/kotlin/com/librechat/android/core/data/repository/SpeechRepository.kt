package com.librechat.android.core.data.repository

import com.librechat.android.core.common.result.Result
import com.librechat.android.core.model.speech.SpeechConfig
import com.librechat.android.core.model.speech.SpeechToTextResponse
import com.librechat.android.core.model.speech.TtsVoice

interface SpeechRepository {
    suspend fun transcribeAudio(
        audioData: ByteArray,
        mimeType: String,
        language: String? = null,
    ): Result<SpeechToTextResponse>
    suspend fun synthesizeSpeech(text: String, voice: String? = null, model: String? = null): Result<ByteArray>
    suspend fun getVoices(): Result<List<TtsVoice>>
    suspend fun getSpeechConfig(): Result<SpeechConfig>
}
