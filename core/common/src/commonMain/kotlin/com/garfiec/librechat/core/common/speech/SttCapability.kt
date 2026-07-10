package com.garfiec.librechat.core.common.speech

/**
 * Whether this platform runs the in-process live recognizer that honors the on-device and
 * end-of-speech ("stop when I pause") preferences. Android: only on API 31+ (older OS falls back to
 * the one-shot full-screen Intent overlay, which reads neither). iOS: always (SFSpeechRecognizer).
 *
 * Single source of truth: both feature/settings (toggle visibility) and feature/chat (mic routing)
 * consume this so the API-level boundary can't drift between the two modules, which can't depend on
 * each other.
 */
expect fun sttSupportsLiveRecognition(): Boolean

/**
 * Whether the STT *engine* choice (Browser vs External) actually selects the recognizer/transport on
 * this platform. Android: true (Browser = live recognizer, External = record→upload). iOS: false —
 * there is no iOS External transport yet, so [com.garfiec.librechat.core.model.speech.SttEngine]
 * External falls through to the same SFSpeechRecognizer path as Browser, and the on-device/
 * end-of-speech prefs apply regardless of the selected engine.
 */
expect fun sttEngineSelectsRecognizer(): Boolean
