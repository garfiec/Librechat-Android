package com.garfiec.librechat.feature.chat.audio

/**
 * How long to wait after the user stops dictation for the recognizer to deliver its final result
 * before force-finalizing (committing whatever partial is already shown in the composer).
 *
 * Shared by the Android [SpeechRecognizerController] and iOS `IosVoiceInput` so both platforms apply
 * one stop→final policy instead of each redeclaring the constant and keeping it in sync by comment.
 */
internal const val STOP_WATCHDOG_MS = 1200L

/**
 * Stop→final watchdog for the (slower) cloud recognizer. Apple/Google cloud finals routinely take
 * ~1.5–3s after the user stops, so the short on-device [STOP_WATCHDOG_MS] would pre-empt the
 * corrected final and commit/auto-send a rougher partial; the cloud path waits longer before
 * force-finalizing. Shared so both platforms apply the same on-device-vs-cloud grace policy.
 */
internal const val CLOUD_STOP_WATCHDOG_MS = 4000L

/**
 * How many consecutive recognizer-initiated finals that heard nothing to allow before ending a
 * continuous-dictation session, so unbroken silence can't spin the mic open forever.
 *
 * Shared by the Android [SpeechRecognizerController] and iOS `IosVoiceInput` so both platforms apply
 * one continuous-restart policy instead of each redeclaring the constant and keeping it in sync by
 * comment.
 */
internal const val MAX_EMPTY_SEGMENTS = 4
