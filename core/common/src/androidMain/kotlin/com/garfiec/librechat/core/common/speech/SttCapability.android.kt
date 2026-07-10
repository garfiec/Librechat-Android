package com.garfiec.librechat.core.common.speech

import android.os.Build

/** The in-process [android.speech.SpeechRecognizer] path is only available from API 31+. */
actual fun sttSupportsLiveRecognition(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Browser uses the live recognizer; External records and uploads to the server. */
actual fun sttEngineSelectsRecognizer(): Boolean = true
