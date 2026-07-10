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
import com.garfiec.librechat.core.common.speech.mapSttLanguageToLocale
import com.garfiec.librechat.core.common.speech.sttSupportsLiveRecognition
import com.garfiec.librechat.core.model.speech.SttEngine
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds the "start recording" action for the chat composer.
 *
 * Routing here is only about *which mechanism* to use, decided by OS capability:
 * - API 31+ (or the External engine): defer to [ChatViewModel.startRecording]; the voice-input
 *   delegate owns the Browser-vs-External and on-device-vs-network choice (it already collects the
 *   `sttEngine`/`sttOnDevice` preferences).
 * - API 26–30 with the Browser engine: `createOnDeviceSpeechRecognizer` isn't available, so fall
 *   back to today's full-screen Intent-overlay recognizer.
 *
 * Rebuilt on each recomposition so it always sees the latest [sttEngine]/[sttLanguage].
 */
@Composable
internal fun rememberChatStartRecording(
    viewModel: ChatViewModel,
    sttEngine: String,
    sttLanguage: String,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
): () -> Unit {
    val context = LocalContext.current

    // Legacy full-screen Intent-overlay recognizer (API 26–30 Browser fallback).
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

    // Runtime permission request for RECORD_AUDIO (in-process recognizer + External upload paths).
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
        val engine = SttEngine.fromStored(sttEngine)
        // The in-process live recognizer needs API 31+ (shared capability seam, same predicate the
        // settings toggles gate on). Below that, Browser falls back to the full-screen Intent overlay.
        val useIntentOverlay = engine == SttEngine.BROWSER && !sttSupportsLiveRecognition()

        if (useIntentOverlay) {
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
            try {
                speechRecognizerLauncher.launch(intent)
            } catch (_: Exception) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Speech recognition is not available on this device")
                }
            }
        } else {
            // API 31+ Browser (in-process recognizer) or External (record + upload); the delegate
            // routes. Both need RECORD_AUDIO.
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
    }
}
