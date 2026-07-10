package com.garfiec.librechat.feature.chat.viewmodel.delegate

import android.content.Context
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.speech.mapSttLanguageToLocale
import com.garfiec.librechat.core.data.repository.ServerSttGate
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.core.model.speech.SttEngine
import com.garfiec.librechat.feature.chat.audio.DictationBuffer
import com.garfiec.librechat.feature.chat.audio.SpeechRecognizerController
import com.garfiec.librechat.feature.chat.audio.VoiceRecorder
import com.garfiec.librechat.feature.chat.audio.appendToBase
import com.garfiec.librechat.feature.chat.audio.shouldAutoSendTranscript
import com.garfiec.librechat.feature.chat.viewmodel.VoiceHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceInputDelegate(
    private val handle: VoiceHandle,
    private val appContext: Context,
    private val speechRepository: SpeechRepository,
    private val autoSendAfterStt: StateFlow<Boolean>,
    private val sttEngine: StateFlow<String>,
    private val sttLanguage: StateFlow<String>,
    private val sttOnDevice: StateFlow<Boolean>,
    private val sttEndOfSpeech: StateFlow<Boolean>,
    private val ioDispatcher: CoroutineDispatcher,
    private val onTranscriptionComplete: () -> Unit,
) {

    // ── External (server Whisper) session state ──
    private var voiceRecorder: VoiceRecorder? = null
    // Tracks the in-flight VoiceRecorder.start(). stop()/cancel() join it before touching the
    // recorder so a tap-stop (or second tap) during the MediaRecorder warm-up can't race the
    // not-yet-finished start() and orphan a recording that keeps the mic hot.
    private var startJob: Job? = null
    // The in-flight stop→upload→transcribe coroutine. Held so cancelRecording() can abort a slow or
    // hung server upload — otherwise the mic sat unresponsive (the isTranscribing start-guard blocks a
    // retry) with no way to cancel until the network call resolved on its own.
    private var transcribeJob: Job? = null

    // Loads + latches the server-STT flag with the shared "unknown vs disabled" retry policy. Until
    // it has resolved once (gate.loaded), serverSttEnabled=false is "unknown", not "disabled" — so
    // the External path awaits a fetch before deciding rather than falsely reporting STT off.
    private val serverSttGate = ServerSttGate(speechRepository)

    // Guards the async config-fetch window in startExternalRecording so a double mic-tap (isRecording
    // isn't set until the fetch resolves) can't spawn two recorders.
    private var externalStartPending = false

    // ── Browser (in-process SpeechRecognizer) session state ──
    private var speechController: SpeechRecognizerController? = null

    /** Composer merge/rebase/revert bookkeeping for the browser session (shared with iOS). */
    private val browserBuffer = DictationBuffer()

    fun startRecording() {
        // Reject a start while any session is live OR winding down. isRecording is only true during an
        // active session; the stop→final windows (browser stopListening, External Whisper upload) run
        // with isRecording=false + isTranscribing=true and the controller/recorder ref still set until
        // finalize, and externalStartPending covers the async speech-config fetch before an External
        // recorder is assigned. Guarding on isRecording alone let a second mic tap in any of these
        // windows spawn a parallel session that orphaned the first recognizer and left the mic hot.
        if (handle.state.isRecording ||
            handle.state.isTranscribing ||
            speechController != null ||
            voiceRecorder != null ||
            externalStartPending
        ) {
            return
        }
        when (SttEngine.fromStored(sttEngine.value)) {
            SttEngine.EXTERNAL -> startExternalRecording()
            SttEngine.BROWSER -> startBrowserRecording()
        }
    }

    fun stopRecording() {
        // Route by the active session, not by re-reading the pref — a pref change mid-recording
        // must not stop the wrong path.
        val controller = speechController
        val recorder = voiceRecorder
        when {
            controller != null -> {
                handle.update { voice = voice.copy(isRecording = false, isTranscribing = true) }
                controller.stop()
            }
            recorder != null -> stopExternalRecording(recorder)
            // Stop tapped while an External start is still resolving its config fetch: clear the flag
            // so the pending coroutine aborts instead of beginning a recording the user cancelled.
            externalStartPending -> externalStartPending = false
        }
    }

    fun cancelRecording() {
        // Abort any pending External start too (stopRecording clears this for the same reason); without
        // it a cancel during the async speech-config fetch would still open a recorder the user aborted.
        externalStartPending = false
        val controller = speechController
        val recorder = voiceRecorder
        when {
            controller != null -> {
                speechController = null
                controller.cancel()
                // Drop the in-progress dictation if the user hasn't edited the field since our last write.
                handle.update {
                    voice = voice.copy(isRecording = false, isTranscribing = false)
                    composer = composer.copy(inputText = browserBuffer.revert(composer.inputText))
                }
            }
            recorder != null -> {
                voiceRecorder = null
                handle.update { voice = voice.copy(isRecording = false) }
                // Join start() first so cancel() can't race the in-flight warm-up; cancel() then runs
                // MediaRecorder.stop()/release() off the Main thread.
                handle.scope.launch {
                    startJob?.join()
                    recorder.cancel()
                }
            }
            // Cancel tapped while the External upload is transcribing (or its recorder is still
            // warming up): cancel the job — its finally releases the recorder + warm-up under
            // NonCancellable so the mic can't stay hot — and drop the pending transcript.
            transcribeJob != null -> {
                transcribeJob?.cancel()
                transcribeJob = null
                handle.update { voice = voice.copy(isTranscribing = false) }
            }
        }
    }

    // ── Browser engine: in-process SpeechRecognizer with live partials ──

    private fun startBrowserRecording() {
        browserBuffer.begin(handle.state.inputText)

        val controller = SpeechRecognizerController(appContext).apply {
            onPartial = { partial -> applyBrowserPartial(partial) }
            onSegment = { segment -> commitBrowserSegment(segment) }
            onError = { message -> onBrowserError(message) }
            onFinished = { finalizeBrowserSession() }
        }
        speechController = controller
        // Clear any error left by a prior failed session so a stale message can't re-surface over a
        // successful dictation.
        handle.update {
            voice = voice.copy(isRecording = true)
            error = null
        }
        controller.start(
            preferOnDevice = sttOnDevice.value,
            endOfSpeech = sttEndOfSpeech.value,
            languageTag = mapSttLanguageToLocale(sttLanguage.value),
        )
    }

    private fun applyBrowserPartial(partial: String) {
        val text = browserBuffer.merge(handle.state.inputText, partial)
        // Partials fire ~10×/s and often re-deliver the same text during a pause; skip the state copy
        // + uiState re-emit when nothing actually changed.
        if (text != handle.state.inputText) handle.update { composer = composer.copy(inputText = text) }
    }

    private fun commitBrowserSegment(segment: String) {
        val committed = browserBuffer.commit(handle.state.inputText, segment)
        handle.update { composer = composer.copy(inputText = committed) }
    }

    private fun onBrowserError(message: String) {
        speechController = null
        handle.update {
            voice = voice.copy(isRecording = false, isTranscribing = false)
            error = message
        }
    }

    private fun finalizeBrowserSession() {
        speechController = null
        handle.update { voice = voice.copy(isRecording = false, isTranscribing = false) }
        // Auto-send only on user-stop (this callback), never per silence-boundary segment, and only
        // when dictation actually produced text so a pre-typed field isn't sent by an empty session.
        if (browserBuffer.shouldAutoSend(
                handle.state.inputText,
                autoSendAfterStt.value,
                handle.state.isStreaming,
            )
        ) {
            onTranscriptionComplete()
        }
    }

    // ── External engine: record + upload to server Whisper ──

    private fun startExternalRecording() {
        // serverSttEnabled is populated by an async loadSpeechConfig() launched at VM init. If that
        // hasn't resolved yet (fast mic-tap on a fresh screen, or an earlier transient fetch error),
        // fetch it now before deciding — otherwise the default `false` would falsely report STT off.
        if (serverSttGate.loaded) {
            beginExternalRecording()
        } else {
            externalStartPending = true
            handle.scope.launch {
                try {
                    fetchSpeechConfig()
                    // stopRecording() during the fetch clears externalStartPending to cancel this
                    // pending start — honor it rather than beginning a recording the user aborted.
                    if (externalStartPending) beginExternalRecording()
                } finally {
                    externalStartPending = false
                }
            }
        }
    }

    private fun beginExternalRecording() {
        if (!handle.state.serverSttEnabled) {
            // Distinguish "server reachable, STT genuinely off" from "couldn't reach the server to
            // check": gate.loaded is only set once a fetch succeeds, so a false here with the latch
            // still down means the fetch failed (transient) — don't tell the user STT is disabled.
            val message = if (serverSttGate.loaded) {
                "Server speech-to-text is not enabled. Ask your server admin to enable STT, or " +
                    "switch the engine to Browser in Speech settings."
            } else {
                "Couldn't reach the server to check speech settings. Check your connection and try " +
                    "again, or switch the engine to Browser in Speech settings."
            }
            handle.setError(message)
            return
        }
        // Set state + assign the recorder synchronously on Main so the isRecording guard and
        // stopRecording()/cancelRecording() see a consistent recorder during start()'s warm-up.
        val recorder = VoiceRecorder(appContext, ioDispatcher)
        voiceRecorder = recorder
        handle.update {
            voice = voice.copy(isRecording = true)
            error = null
        }
        startJob = handle.scope.launch {
            try {
                recorder.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (voiceRecorder === recorder) {
                    voiceRecorder = null
                    handle.update {
                        voice = voice.copy(isRecording = false)
                        error = "Could not start recording: ${e.message}"
                    }
                }
            }
        }
    }

    private fun stopExternalRecording(recorder: VoiceRecorder) {
        val mimeType = recorder.mimeType
        voiceRecorder = null
        handle.update { voice = voice.copy(isRecording = false, isTranscribing = true) }

        // Capture the warm-up job for THIS recorder. The isTranscribing guard blocks a new session
        // until this coroutine (or cancelRecording) clears the flag, so startJob can't be reassigned
        // out from under us before the coroutine's first line runs.
        val warmup = startJob
        val job = handle.scope.launch {
            try {
                // Ensure start() finished before we stop the recorder.
                warmup?.join()
                val audioData = recorder.stop()
                if (audioData == null || audioData.isEmpty()) {
                    handle.update {
                        voice = voice.copy(isTranscribing = false)
                        error = "Recording was empty"
                    }
                    return@launch
                }

                when (val result = speechRepository.transcribeAudio(audioData, mimeType)) {
                    is Result.Success -> {
                        val transcribedText = result.data.text
                        val merged = appendToBase(handle.state.inputText, transcribedText)
                        handle.update {
                            composer = composer.copy(inputText = merged)
                            voice = voice.copy(isTranscribing = false)
                        }
                        // Auto-send via the shared gate so this path can't drift from Browser/iOS.
                        if (shouldAutoSendTranscript(
                                transcribedText.isNotBlank(),
                                autoSendAfterStt.value,
                                handle.state.isStreaming,
                            )
                        ) {
                            onTranscriptionComplete()
                        }
                    }
                    is Result.Error -> {
                        handle.update {
                            voice = voice.copy(isTranscribing = false)
                            error = result.message ?: "Transcription failed"
                        }
                    }
                    // safeApiCall never emits Loading, but clear the flag defensively so a future
                    // change can't strand isTranscribing=true and permanently lock out the mic.
                    is Result.Loading -> handle.update { voice = voice.copy(isTranscribing = false) }
                }
            } finally {
                // On cancellation (cancelRecording during the warm-up or the upload) recorder.stop()
                // may never have run: cancel the still-warming recorder so start()'s own
                // CancellationException cleanup releases the mic, then release directly (idempotent —
                // a no-op after a normal stop()). NonCancellable so it still runs from a cancelled job.
                withContext(NonCancellable) {
                    warmup?.cancelAndJoin()
                    recorder.cancel()
                }
            }
        }
        transcribeJob = job
        // Null the field only when THIS job completes and it's still the current one — a later session
        // may have replaced it, and cancelRecording nulls it synchronously.
        job.invokeOnCompletion { if (transcribeJob === job) transcribeJob = null }
    }

    /**
     * Called when the legacy Intent-overlay recognizer (API 26–30 fallback) returns a result.
     * Appends the transcribed text to the input field, and auto-sends if enabled.
     */
    fun onDeviceSpeechResult(transcribedText: String) {
        if (transcribedText.isBlank()) return
        val merged = appendToBase(handle.state.inputText, transcribedText)
        handle.update {
            composer = composer.copy(inputText = merged)
            error = null
        }
        // Auto-send via the shared gate (transcribedText is non-blank — guarded above).
        if (shouldAutoSendTranscript(
                transcribedText.isNotBlank(),
                autoSendAfterStt.value,
                handle.state.isStreaming,
            )
        ) {
            onTranscriptionComplete()
        }
    }

    fun loadSpeechConfig() {
        handle.scope.launch { fetchSpeechConfig() }
    }

    private suspend fun fetchSpeechConfig() {
        // A null result means the fetch couldn't determine the flag (transient error) — leave the gate
        // unlatched so a later External tap retries rather than trusting this as a definitive "off".
        val enabled = serverSttGate.refresh() ?: false
        handle.update { voice = voice.copy(serverSttEnabled = enabled) }
    }

    fun release() {
        // SpeechRecognizer.destroy() must run on the thread that created it (Main). onCleared() is
        // Main, so destroy synchronously here — do NOT push it onto the detached IO scope used for
        // MediaRecorder cleanup below.
        speechController?.destroy()
        speechController = null

        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        // Called from onCleared(), at which point handle.scope is already cancelled — a
        // launch on it would never run and the recorder/mic would leak. Run the cleanup on a
        // detached IO scope so the blocking MediaRecorder.stop()/release() still happens off the
        // Main thread and is guaranteed to complete (cancel() uses NonCancellable internally).
        CoroutineScope(ioDispatcher).launch { recorder.cancel() }
    }
}
