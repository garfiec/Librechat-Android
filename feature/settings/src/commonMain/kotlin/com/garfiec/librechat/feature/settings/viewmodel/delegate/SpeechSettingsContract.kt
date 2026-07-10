package com.garfiec.librechat.feature.settings.viewmodel.delegate

import com.garfiec.librechat.core.model.speech.TtsVoice

interface SpeechSettingsContract {
    fun loadVoices()
    fun loadDeviceVoices()
    fun loadSpeechConfig()
    fun setAutoSendAfterStt(enabled: Boolean)
    fun setAutoReadEnabled(enabled: Boolean)
    fun selectVoice(voice: TtsVoice)
    fun testVoice()
    fun previewDeviceTts(text: String, rate: Float, pitch: Float, voiceName: String?)
    fun previewServerTts(text: String, voice: String?, model: String?)
    fun stopTtsPreview()
    fun showSttDetailDialog()
    fun dismissSttDetailDialog()
    fun saveSttSettings(engine: String, language: String, onDevice: Boolean, endOfSpeech: Boolean)
    fun showTtsDetailDialog()
    fun dismissTtsDetailDialog()
    fun saveTtsSettings(
        engine: String,
        voice: String,
        rate: Float,
        pitch: Float,
        deviceVoiceName: String,
        caching: Boolean,
        source: String,
    )
    fun release()
}
