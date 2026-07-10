package com.garfiec.librechat.core.common.speech

/** iOS always uses the SFSpeechRecognizer live path, which honors both toggles. */
actual fun sttSupportsLiveRecognition(): Boolean = true

/**
 * iOS has no External (server-upload) transport yet, so the engine choice doesn't select the
 * recognizer — External falls through to the same SFSpeechRecognizer path as Browser, and the
 * on-device / end-of-speech prefs apply regardless of the selected engine.
 */
actual fun sttEngineSelectsRecognizer(): Boolean = false
