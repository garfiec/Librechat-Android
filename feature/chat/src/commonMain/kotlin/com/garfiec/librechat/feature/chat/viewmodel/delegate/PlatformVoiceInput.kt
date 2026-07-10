package com.garfiec.librechat.feature.chat.viewmodel.delegate

/**
 * Platform-abstracted voice input handling.
 * Android: wraps VoiceInputDelegate (in-process SpeechRecognizer for the Browser engine,
 * MediaRecorder + upload for the External engine).
 * iOS: SFSpeechRecognizer live transcription.
 */
interface PlatformVoiceInput {
    fun startRecording()
    fun stopRecording()
    fun cancelRecording()
    fun onDeviceSpeechResult(transcribedText: String)
    fun loadSpeechConfig()
    fun release()
}
