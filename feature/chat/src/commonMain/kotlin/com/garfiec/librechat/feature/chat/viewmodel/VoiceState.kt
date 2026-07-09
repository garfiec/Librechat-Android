package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable

/**
 * Speech-to-text and text-to-speech UI state. Written by the platform voice-input delegates
 * (VoiceInputDelegate / IosVoiceInput) and TTS delegates (TextToSpeechDelegate / IosTts).
 */
@Immutable
data class VoiceState(
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    /** Whether the server has STT configured. When false, use device speech recognition. */
    val serverSttEnabled: Boolean = false,
    val currentlyReadingMessageId: String? = null,
)
