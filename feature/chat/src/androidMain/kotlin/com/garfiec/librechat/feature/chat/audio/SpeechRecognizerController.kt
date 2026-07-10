package com.garfiec.librechat.feature.chat.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.speech.sttSupportsLiveRecognition

/**
 * Drives an in-process [SpeechRecognizer] for continuous live dictation with partial results.
 * Owns the single-shot→continuous restart loop, the on-device vs network recognizer choice with
 * fallback, and error recovery.
 *
 * All methods and callbacks run on the main thread — [SpeechRecognizer] requires a Looper and its
 * `destroy()` must be called on the thread that created it. The owner ([VoiceInputDelegate]) drives
 * this from the (main-dispatched) ViewModel scope.
 *
 * Continuous behavior: [SpeechRecognizer] is single-shot per `startListening`, so on each final
 * segment (or a recoverable no-speech error) the recognizer restarts until [stop]/[cancel]. A cap on
 * consecutive empty restarts prevents indefinite silence from spinning it forever.
 */
class SpeechRecognizerController(private val appContext: Context) {

    private val log = Logger.withTag("SpeechRecognizer")
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var languageTag: String? = null
    private var usingOnDevice = false
    private var endOfSpeech = false
    private var stopped = false
    private var finished = false
    private var triedNetworkFallback = false
    private var consecutiveEmptyRestarts = 0
    private var recoveryAttempts = 0
    private var startWatchdog: Runnable? = null

    /** Cumulative partial transcript for the current listening session (replaces prior partial). */
    var onPartial: (String) -> Unit = {}

    /** A finalized segment at a silence boundary; commit it and keep listening (continuous). */
    var onSegment: (String) -> Unit = {}

    /** Unrecoverable error; the session is over (recognizer already destroyed). */
    var onError: (String) -> Unit = {}

    /** The session finished cleanly after a [stop] (last result committed or watchdog fired). */
    var onFinished: () -> Unit = {}

    fun start(preferOnDevice: Boolean, endOfSpeech: Boolean, languageTag: String?) {
        this.languageTag = languageTag
        this.endOfSpeech = endOfSpeech
        stopped = false
        finished = false
        triedNetworkFallback = false
        consecutiveEmptyRestarts = 0
        recoveryAttempts = 0

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            fail("Speech recognition is not available on this device")
            return
        }
        // Prefer the on-device recognizer when asked and supported (API 31+, via the shared capability
        // seam). If the on-device model for the chosen language is missing, the recognizer errors with
        // a language-unavailable code and we transparently fall back to the network recognizer (onError).
        usingOnDevice = preferOnDevice && sttSupportsLiveRecognition()
        createAndListen()
    }

    private fun createAndListen() {
        recognizer?.destroy()
        // usingOnDevice is only ever set true behind an API 31+ check in start(), so it already
        // implies createOnDeviceSpeechRecognizer is available.
        val recognizer = if (usingOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        recognizer.setRecognitionListener(listener)
        this.recognizer = recognizer
        recognizer.startListening(buildIntent())
        // Guard against a recognizer that binds but never calls back (wedged on-device service, no
        // language pack): if nothing arrives, fall back to network once, then surface a failure —
        // otherwise the mic stays hot with isRecording=true forever.
        armStartWatchdog()
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            languageTag?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
            }
        }

    /** User asked to stop. Ask for the final result; a watchdog finishes if none arrives. */
    fun stop() {
        if (stopped) return
        stopped = true
        recognizer?.stopListening()
        handler.postDelayed({ if (!finished) finish() }, STOP_WATCHDOG_MS)
    }

    /** Discard the session with no final callback. */
    fun cancel() {
        stopped = true
        finished = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    /** Release from onCleared(); safe to call on the main thread synchronously. */
    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun finish() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        onFinished()
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        onError(message)
    }

    private fun extractText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            disarmStartWatchdog()
            // Deliberately does NOT reset recoveryAttempts. A device whose on-device recognizer
            // alternates onReadyForSpeech → BUSY/DISCONNECTED on every restart would otherwise
            // oscillate the counter 0→1→0 forever, never reach MAX_RECOVERY_ATTEMPTS, never fall
            // back to the network recognizer, and spin restarts with the mic held open. The budget
            // is instead refreshed on a real partial/final (proof the recognizer actually works).
        }
        override fun onBeginningOfSpeech() { disarmStartWatchdog() }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onRmsChanged(rmsdB: Float) { disarmStartWatchdog() }

        override fun onPartialResults(partialResults: Bundle?) {
            // Ignore partials once the session has stopped/finished: after a user-stop the watchdog
            // may already have committed the final and (with auto-send) cleared the composer, and a
            // late queued partial would rebase onto the now-empty field and re-inject stale text.
            // Mirrors the `if (finished) return` guard in onResults()/onError().
            if (finished || stopped) return
            val text = extractText(partialResults)
            if (text.isNotBlank()) {
                // Real recognition proves the recognizer is healthy — refresh the churn-recovery
                // budget so a genuinely long, working session that later drops still gets a full set
                // of retries (see onReadyForSpeech for why the reset lives here, not there).
                recoveryAttempts = 0
                onPartial(text)
            }
        }

        override fun onResults(results: Bundle?) {
            if (finished) return
            val text = extractText(results)
            if (text.isNotBlank()) {
                consecutiveEmptyRestarts = 0
                recoveryAttempts = 0
                onSegment(text)
            }
            when {
                stopped -> finish()
                // End-of-speech mode: the recognizer detected the user finished a phrase — stop here
                // (hands-free) instead of restarting. finish() → onFinished lets the delegate auto-send
                // (gated on the separate auto-send-after-STT preference).
                text.isNotBlank() && endOfSpeech -> finish()
                text.isNotBlank() -> restartListening()
                // Empty final: some OEM recognizers deliver an empty onResults at a silence boundary
                // instead of ERROR_NO_MATCH. Bound it like the ERROR_NO_MATCH path so indefinite
                // silence can't spin restarts forever with the mic held open (isRecording=true).
                ++consecutiveEmptyRestarts >= MAX_EMPTY_SEGMENTS ->
                    fail("Didn't catch that. Tap the mic to try again.")
                else -> restartListening()
            }
        }

        override fun onError(error: Int) {
            if (finished) return
            disarmStartWatchdog()
            log.d { "onError code=$error stopped=$stopped onDevice=$usingOnDevice" }
            when {
                // A stop() races the final; treat a post-stop error as "no more results".
                stopped -> finish()

                // On-device model missing for this language → retry once on the network recognizer.
                usingOnDevice && !triedNetworkFallback && isLanguageError(error) ->
                    fallBackToNetwork(::createAndListen)

                // No speech this turn → keep listening (continuous), but cap consecutive silence.
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveEmptyRestarts++
                    if (consecutiveEmptyRestarts >= MAX_EMPTY_SEGMENTS) {
                        fail("Didn't catch that. Tap the mic to try again.")
                    } else {
                        restartListening()
                    }
                }

                // Startup/connection churn: while the on-device recognizer (Soda) warms up its
                // language pack it emits a rapid mix of ERROR_RECOGNIZER_BUSY /
                // ERROR_SERVER_DISCONNECTED / ERROR_CLIENT for a few hundred ms. Recreate with a
                // backoff (never hammer an already-busy service with an immediate recreate),
                // bounded; then fall back to the network recognizer once, and only surface a
                // failure if that also can't connect. The budget resets on onReadyForSpeech.
                isRecoverableChurn(error) -> when {
                    recoveryAttempts < MAX_RECOVERY_ATTEMPTS -> {
                        recoveryAttempts++
                        restartAfterDelay()
                    }
                    usingOnDevice && !triedNetworkFallback -> fallBackToNetwork(::restartAfterDelay)
                    else -> fail(errorMessage(error))
                }

                else -> fail(errorMessage(error))
            }
        }
    }

    private fun isRecoverableChurn(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
            error == SpeechRecognizer.ERROR_CLIENT

    /** Recreate + restart after a backoff, unless the session was stopped/finished meanwhile. */
    private fun restartAfterDelay() {
        handler.postDelayed({ if (!stopped && !finished) createAndListen() }, RECOVERY_RETRY_DELAY_MS)
    }

    /**
     * Continue the continuous session by REUSING the existing recognizer instance rather than
     * destroying and recreating it. Repeated destroy/create cycles are what stress the on-device
     * service into the BUSY/DISCONNECTED churn, so a plain [SpeechRecognizer.startListening] on the
     * live instance is both lighter and lower-latency. Falls back to a full recreate if the
     * instance is gone or rejects the restart (its binding died).
     */
    private fun restartListening() {
        val r = recognizer ?: run { createAndListen(); return }
        handler.postDelayed({
            if (stopped || finished) return@postDelayed
            try {
                r.startListening(buildIntent())
                // Re-arm the start watchdog on the reuse path too: a reused instance can silently
                // wedge (bound, no callback, no exception) if the on-device service dies mid-session,
                // which would otherwise leave the mic hot with isRecording=true and no recovery.
                armStartWatchdog()
            } catch (e: Exception) {
                log.d { "reuse restart failed (${e.message}); recreating" }
                createAndListen()
            }
        }, RESTART_DELAY_MS)
    }

    /** Arm a timeout that recovers if the freshly-started recognizer never calls back at all. */
    private fun armStartWatchdog() {
        disarmStartWatchdog()
        val watchdog = Runnable {
            startWatchdog = null
            if (stopped || finished) return@Runnable
            log.d { "start watchdog fired; recognizer never called back (onDevice=$usingOnDevice)" }
            when {
                usingOnDevice && !triedNetworkFallback -> fallBackToNetwork(::createAndListen)
                else -> fail("Speech recognition didn't start. Tap the mic to try again.")
            }
        }
        startWatchdog = watchdog
        handler.postDelayed(watchdog, START_WATCHDOG_MS)
    }

    /**
     * Switch from the on-device recognizer to the network recognizer and restart via [restart]. Used
     * by the language-error, churn, and start-watchdog paths so the fallback flag/counter resets live
     * in one place. Resets both the empty-restart and churn-recovery budgets since the network
     * recognizer is a fresh attempt.
     */
    private fun fallBackToNetwork(restart: () -> Unit) {
        triedNetworkFallback = true
        usingOnDevice = false
        consecutiveEmptyRestarts = 0
        recoveryAttempts = 0
        restart()
    }

    /** Any recognizer callback proves it's alive — cancel the start watchdog. */
    private fun disarmStartWatchdog() {
        startWatchdog?.let { handler.removeCallbacks(it) }
        startWatchdog = null
    }

    private fun isLanguageError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
            // Older on-device implementations report a missing model as a generic server error.
            (usingOnDevice && error == SpeechRecognizer.ERROR_SERVER)

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice input"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a network connection"
        SpeechRecognizer.ERROR_AUDIO -> "Couldn't access the microphone"
        else -> "Speech recognition failed"
    }

    private companion object {
        // Generous — on-device model warmup + churn recovery can legitimately take a few seconds; a
        // false positive here would abort a healthy session. Only a truly wedged recognizer trips it.
        const val START_WATCHDOG_MS = 8000L
        const val MAX_RECOVERY_ATTEMPTS = 5
        const val RECOVERY_RETRY_DELAY_MS = 250L
        const val RESTART_DELAY_MS = 100L
    }
}
