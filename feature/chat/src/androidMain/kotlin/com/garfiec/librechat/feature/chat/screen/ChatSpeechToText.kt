package com.garfiec.librechat.feature.chat.screen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds the "start recording" action for the chat composer, routing between the
 * server speech-to-text path (record audio + upload for transcription) and the
 * device speech recognizer based on the user's STT engine preference. This keeps
 * the Android permission + intent plumbing out of [ChatScreen].
 *
 * Returns a lambda to invoke when the user taps the mic; it is rebuilt on each
 * recomposition so it always sees the latest [serverSttEnabled].
 */
@Composable
internal fun rememberChatStartRecording(
    viewModel: ChatViewModel,
    sttEngine: String,
    sttLanguage: String,
    serverSttEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
): () -> Unit {
    val context = LocalContext.current

    // Device speech recognizer launcher (used when server STT is not available)
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val transcribed = matches?.firstOrNull()
            if (!transcribed.isNullOrBlank()) {
                viewModel.onDeviceSpeechResult(transcribed)
            }
        }
    }

    // Runtime permission request for RECORD_AUDIO (server STT path)
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Microphone permission is required for voice input")
            }
        }
    }

    return {
        val useServerStt = sttEngine.equals("whisper", ignoreCase = true) ||
            (sttEngine.isBlank() || sttEngine.equals("default", ignoreCase = true)) &&
            serverSttEnabled

        if (useServerStt) {
            if (!serverSttEnabled && sttEngine.equals("whisper", ignoreCase = true)) {
                // User selected Whisper but server STT is not configured
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        "Server speech-to-text is not enabled. " +
                            "Ask your server admin to enable STT, or switch to Device or Google engine.",
                    )
                }
            } else {
                // Server STT path: record audio and upload for transcription
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    viewModel.startRecording()
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        } else {
            // Device speech recognizer path (Device, Google, or Default without server)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
                val languageLocale = mapSttLanguageToLocale(sttLanguage)
                if (languageLocale != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageLocale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageLocale)
                }
            }
            val enginePackage = mapSttEngineToPackage(sttEngine)
            if (enginePackage != null) {
                intent.setPackage(enginePackage)
            }
            try {
                speechRecognizerLauncher.launch(intent)
            } catch (_: Exception) {
                val engineLabel = if (sttEngine.equals("google", ignoreCase = true)) {
                    "Google speech recognition is not available. Is the Google app installed?"
                } else {
                    "Speech recognition is not available on this device"
                }
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(engineLabel)
                }
            }
        }
    }
}

/**
 * Maps the user-facing STT engine name (from settings) to an Android package name
 * that can be set on the device speech recognition intent. Returns null for "Default",
 * empty/unknown values, or "Whisper" (which uses server-side STT, not a device package).
 *
 * Note: "Whisper" is handled separately in [rememberChatStartRecording] above --
 * it routes through the server STT path (record audio + upload) rather than the device
 * speech recognizer. This function is only called for the device recognizer path.
 */
private fun mapSttEngineToPackage(engine: String): String? = when (engine.lowercase()) {
    "google" -> "com.google.android.googlequicksearchbox"
    "whisper" -> null // Server-side engine; never reaches device recognizer path
    "device" -> null // Explicit on-device; uses system default speech recognizer
    "default", "" -> null
    else -> null
}

/**
 * Maps the user-facing STT language name (from settings) to a BCP-47 locale tag
 * for [RecognizerIntent.EXTRA_LANGUAGE]. Returns null for "Auto-detect" or
 * empty values, which lets the recognizer use the device default.
 */
private fun mapSttLanguageToLocale(language: String): String? = when (language.lowercase()) {
    "english" -> "en-US"
    "spanish" -> "es-ES"
    "french" -> "fr-FR"
    "german" -> "de-DE"
    "japanese" -> "ja-JP"
    "chinese" -> "zh-CN"
    "auto-detect", "" -> null
    else -> null
}
