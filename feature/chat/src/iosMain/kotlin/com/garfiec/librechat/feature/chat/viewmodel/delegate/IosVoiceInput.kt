package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.speech.mapSttLanguageToLocale
import com.garfiec.librechat.feature.chat.audio.CLOUD_STOP_WATCHDOG_MS
import com.garfiec.librechat.feature.chat.audio.DictationBuffer
import com.garfiec.librechat.feature.chat.audio.MAX_EMPTY_SEGMENTS
import com.garfiec.librechat.feature.chat.audio.STOP_WATCHDOG_MS
import com.garfiec.librechat.feature.chat.audio.appendToBase
import com.garfiec.librechat.feature.chat.viewmodel.VoiceHandle
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.localeIdentifier
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus

/**
 * iOS implementation of [PlatformVoiceInput] using AVAudioEngine + SFSpeechRecognizer
 * for real-time on-device speech recognition.
 */
class IosVoiceInput(
    private val handle: VoiceHandle,
    private val autoSendAfterStt: StateFlow<Boolean>,
    private val sttOnDevice: StateFlow<Boolean>,
    private val sttEndOfSpeech: StateFlow<Boolean>,
    private val sttLanguage: StateFlow<String>,
    private val onTranscriptionComplete: () -> Unit,
) : PlatformVoiceInput {

    private val log = Logger.withTag("IosVoiceInput")

    private var audioEngine: AVAudioEngine? = null
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest? = null
    private var recognitionTask: SFSpeechRecognitionTask? = null
    private var speechRecognizer: SFSpeechRecognizer? = null

    /** Composer merge/rebase/revert bookkeeping for the current session (shared with Android). */
    private val buffer = DictationBuffer()

    /**
     * Latched once the session is torn down (user stop, cancel, natural final, or error). Guards the
     * recognition callback so a result already queued before teardown can't re-inject stale text into
     * a cancelled/cleared/sent composer. Only touched on the Main-dispatched [VoiceHandle.scope]
     * (the callback hops there — see [startRecognitionTask]) so the check-then-set can't race.
     */
    private var finished = false

    /**
     * Monotonic id for the current recognition task. A continuous restart / cloud fallback cancels the
     * live task and starts a new one; [SFSpeechRecognitionTask.cancel] delivers its cancellation on an
     * arbitrary queue *after* the new task is already running (with [finished] back to false), so the
     * `finished` guard alone can't tell that stale callback apart from the new task's. Each task
     * captures its token and the callback ignores anything whose token no longer matches — otherwise a
     * cancelled task's error would spuriously trigger a cloud fallback or abort live dictation.
     */
    private var sessionToken = 0

    /** True once the user tapped stop — distinguishes a user-finished session (auto-sends) from a
     * recognizer-initiated final at a silence pause (commits only, never auto-sends). */
    private var stopRequested = false

    /** Whether the *current* recognition task forced on-device recognition (drives the cloud fallback
     * on a runtime failure). Distinct from [triedCloudFallback], which records that the *session* has
     * given up on-device for good. */
    private var requestedOnDevice = false

    /**
     * Latched once this session fell back to Apple's cloud after an on-device runtime failure. Kept
     * for the rest of the session (across continuous restarts) so we don't re-attempt — and re-fail —
     * on-device on every segment; reset only on a fresh session ([beginRecording]).
     */
    private var triedCloudFallback = false

    /**
     * Consecutive recognizer-initiated finals that carried no transcript. Bounds the continuous
     * restart loop so a recognizer that finals on silence can't spin the mic open forever.
     */
    private var consecutiveEmptyFinals = 0

    /**
     * The stop→final watchdog coroutine. Held so it can be cancelled when the session finalizes early
     * (final arrived, error, cancel) or a new session starts — otherwise a stale watchdog from a prior
     * session could force-finalize and auto-send a *later* session started within [STOP_WATCHDOG_MS].
     */
    private var stopWatchdogJob: Job? = null

    /**
     * End-of-speech silence timer (only armed in end-of-speech mode). Rescheduled on every transcript
     * change; if it elapses with no new speech, the session finalizes (and auto-sends when enabled) —
     * SFSpeechRecognizer gives no reliable silence callback for buffer requests, so we debounce here.
     */
    private var silenceJob: Job? = null

    /**
     * Latched between requesting speech-recognition authorization and its async callback. On the very
     * first use the status is NotDetermined and [startRecording] returns while the permission prompt is
     * up — with isRecording/isTranscribing false and no task yet, so the normal guard can't tell a
     * second mic tap apart from a fresh start. Without this latch that second tap would kick off a
     * second beginRecording() once auth resolves, installing two taps on the input node. Mirrors the
     * Android [VoiceInputDelegate] externalStartPending guard for its analogous async window.
     */
    private var authorizationInFlight = false

    @OptIn(ExperimentalForeignApi::class)
    override fun startRecording() {
        log.d { "startRecording() called, isRecording=${handle.state.isRecording}" }
        // Reject a start while a session is live OR winding down. isRecording is only true mid-session,
        // but after stopRecording() the task stays alive with isTranscribing=true until the final or
        // watchdog finalizes it; starting again in that window orphaned the first engine (mic stayed
        // hot) and cross-wired its callbacks. Mirrors the Android VoiceInputDelegate guard.
        if (handle.state.isRecording ||
            handle.state.isTranscribing ||
            recognitionTask != null ||
            authorizationInFlight
        ) {
            return
        }

        // External (server Whisper) has no iOS transport yet — SFSpeechRecognizer only does on-device/
        // cloud Apple recognition. Rather than dead-ending the mic with an error (which regressed
        // users who had External/Whisper selected), fall through to Browser recognition. iOS treats
        // External as Browser (documented divergence; follow-up: iOS External via transcribeAudio).

        // Check authorization
        val authStatus = SFSpeechRecognizer.authorizationStatus()
        log.d { "SFSpeechRecognizer authStatus=$authStatus" }
        when (authStatus) {
            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined -> {
                log.d { "Requesting speech recognition authorization..." }
                // Latch so a second mic tap during the permission prompt can't start a second session.
                authorizationInFlight = true
                SFSpeechRecognizer.requestAuthorization { status ->
                    log.d { "Authorization callback: status=$status" }
                    // requestAuthorization delivers on an arbitrary queue — hop to the Main-dispatched
                    // scope for BOTH outcomes so every state write in this class stays main-confined.
                    handle.scope.launch {
                        authorizationInFlight = false
                        // A cancel/release during the prompt set finished — don't start a session (or
                        // surface a denial error) after the user already backed out.
                        if (finished) return@launch
                        if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                            beginRecording()
                        } else {
                            log.w { "Speech recognition permission denied" }
                            handle.setError("Speech recognition permission denied")
                        }
                    }
                }
                return
            }
            SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized -> {
                log.d { "Already authorized, beginning recording" }
                beginRecording()
            }
            else -> {
                log.w { "Speech recognition not available, authStatus=$authStatus" }
                handle.setError("Speech recognition not available. Check Settings > Privacy > Speech Recognition.")
                return
            }
        }
    }

    /**
     * Configure the audio session + recognizer and start a fresh dictation session. The audio engine,
     * input tap, and recognizer are set up ONCE here and REUSED across continuous restarts / cloud
     * fallbacks (which only swap the recognition request+task via [restartRecognition]) — tearing the
     * whole audio stack down and rebuilding it per silence-boundary segment added latency and audible
     * gaps.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun beginRecording() {
        try {
            // Reset all session-scoped state for the fresh session.
            buffer.begin(handle.state.inputText)
            triedCloudFallback = false
            consecutiveEmptyFinals = 0
            stopRequested = false
            finished = false
            stopWatchdogJob?.cancel()
            stopWatchdogJob = null
            silenceJob?.cancel()
            silenceJob = null

            // Honor the shared STT language setting (parity with Android's mapSttLanguageToLocale).
            // SFSpeechRecognizer(locale:) is a FAILABLE initializer: an unsupported identifier yields
            // nil and crashes on first use, so resolve to a locale the framework actually supports.
            // bestSupportedLocale tolerates script subtags / region-rich device identifiers
            // (e.g. a device locale of "zh_Hans_CN" resolves to the supported "zh-CN") instead of
            // demanding an exact string match, and returns null only when nothing matches at all.
            val requestedTag = mapSttLanguageToLocale(sttLanguage.value)
            val locale: NSLocale? = bestSupportedLocale(requestedTag ?: NSLocale.currentLocale.localeIdentifier)
            if (locale == null) {
                failStart("Speech recognition isn't available for this language on this device.")
                return
            }
            log.d { "beginRecording() locale=${locale.languageCode}" }
            val recognizer = SFSpeechRecognizer(locale = locale)
            val available = recognizer.isAvailable()
            log.d { "SFSpeechRecognizer available=$available" }
            if (!available) {
                failStart(
                    "Speech recognition not available on this device (locale: ${locale.languageCode}). " +
                        "This feature requires a real device.",
                )
                return
            }
            speechRecognizer = recognizer

            // Configure audio session
            log.d { "Configuring audio session..." }
            val audioSession = AVAudioSession.sharedInstance()
            audioSession.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeDefault,
                options = 0u,
                error = null,
            )
            audioSession.setActive(true, error = null)
            log.d { "Audio session configured" }

            val engine = AVAudioEngine()
            audioEngine = engine

            // Install the audio tap ONCE; it feeds whichever request is current, so a continuous
            // restart that swaps recognitionRequest doesn't need to reinstall it.
            val inputNode = engine.inputNode
            val recordingFormat = inputNode.outputFormatForBus(0u)
            log.d { "Installing audio tap, format=$recordingFormat" }
            // installTapOnBus throws an Obj-C NSException (which Kotlin's try/catch can't intercept — it
            // would terminate the app) when the input format is invalid. AVAudioEngine asserts
            // IsFormatSampleRateAndChannelCountValid — BOTH a non-zero sample rate AND channel count —
            // and either is 0 when the mic is held by another app / a call, or the audio route changed
            // mid-setup. Guard both fields and fail gracefully instead.
            if (recordingFormat.sampleRate <= 0.0 || recordingFormat.channelCount == 0u) {
                failStart("Could not start recording: the microphone is unavailable (another app may be using it).")
                return
            }
            inputNode.installTapOnBus(
                bus = 0u,
                bufferSize = 1024u,
                format = recordingFormat,
            ) { pcmBuffer, _ ->
                if (pcmBuffer != null) {
                    recognitionRequest?.appendAudioPCMBuffer(pcmBuffer)
                }
            }

            // Start the first recognition task before the engine so no leading audio is dropped.
            startRecognitionTask(useOnDevice = shouldUseOnDevice())

            engine.prepare()
            // startAndReturnError returns false (without throwing) when the engine can't start — e.g.
            // the audio session is held by an active call. Dropping that result left isRecording=true
            // with no audio, no error, and (unlike Android) no start-watchdog to recover.
            if (!engine.startAndReturnError(null)) {
                failStart("Could not start recording: the audio engine failed to start.")
                return
            }
            log.d { "Audio engine started, setting isRecording=true" }
            // The recognition callback now hops to Main (this same thread), so it can't have torn the
            // session down during this synchronous setup — no need to re-check `finished` here. Clear
            // any error left by a prior failed session so a stale message can't re-surface.
            handle.update {
                voice = voice.copy(isRecording = true)
                error = null
            }
        } catch (e: Exception) {
            log.e(e) { "Failed to start recording: ${e.message}" }
            failStart("Could not start recording: ${e.message}")
        }
    }

    /**
     * Whether the next recognition task should force on-device recognition: the user asked, the
     * session hasn't already fallen back to the cloud, and the recognizer + locale support it.
     */
    private fun shouldUseOnDevice(): Boolean =
        !triedCloudFallback && sttOnDevice.value && (speechRecognizer?.supportsOnDeviceRecognition() ?: false)

    /**
     * Create a recognition request+task on the (already-running) engine and wire its callback. Bumps
     * [sessionToken] so a still-in-flight callback from a just-cancelled task is ignored (see the
     * field doc). [useOnDevice] sets `requiresOnDeviceRecognition` and is recorded in
     * [requestedOnDevice] for the runtime cloud fallback.
     */
    private fun startRecognitionTask(useOnDevice: Boolean) {
        val recognizer = speechRecognizer ?: return
        sessionToken += 1
        val token = sessionToken
        val request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if (useOnDevice) request.requiresOnDeviceRecognition = true
        requestedOnDevice = useOnDevice
        recognitionRequest = request

        log.d { "Creating recognition task (token=$token, onDevice=$useOnDevice)..." }
        recognitionTask = recognizer.recognitionTaskWithRequest(request) { result, recognitionError ->
            // SFSpeechRecognizer delivers on an arbitrary queue; hop to the Main-dispatched scope so
            // the session guards (finished/token), the transcript buffer, and the watchdogs are all
            // single-threaded (parity with the Android controller's main-thread contract).
            handle.scope.launch {
                // Ignore anything from a superseded task (continuous restart / cloud fallback) or after
                // teardown — a late queued result must not re-inject stale text or drive a restart.
                if (token != sessionToken || finished) return@launch
                if (result != null) {
                    val transcript = result.bestTranscription.formattedString
                    log.d { "Recognition result: '$transcript', isFinal=${result.isFinal()}" }
                    if (result.isFinal() && !stopRequested) {
                        if (sttEndOfSpeech.value) {
                            // End-of-speech mode: the recognizer finished a phrase — commit and end the
                            // session (hands-free) instead of restarting. Auto-send fires here when
                            // enabled (gated inside finalizeSession → maybeAutoSend).
                            val merged = buffer.merge(handle.state.inputText, transcript)
                            if (merged != handle.state.inputText) {
                                handle.update { composer = composer.copy(inputText = merged) }
                            }
                            finalizeSession(autoSend = true)
                            return@launch
                        }
                        // Continuous mode: recognizer-initiated final (silence pause / ~1-min duration
                        // cap) with no user stop — commit this segment and restart the recognition task
                        // on the live engine so dictation stays continuous. Bound consecutive empty
                        // finals so silence can't spin forever.
                        val committed = buffer.commit(handle.state.inputText, transcript)
                        if (committed != handle.state.inputText) {
                            handle.update { composer = composer.copy(inputText = committed) }
                        }
                        if (transcript.isBlank() && ++consecutiveEmptyFinals >= MAX_EMPTY_SEGMENTS) {
                            log.d { "empty-final cap reached; ending session" }
                            finalizeSession(autoSend = false)
                        } else {
                            if (transcript.isNotBlank()) consecutiveEmptyFinals = 0
                            restartRecognition(useOnDevice = shouldUseOnDevice())
                        }
                        return@launch
                    }
                    // Live partials (~10×/s, often unchanged during a pause) and the user-stop final:
                    // merge and only re-emit the state when the text changed.
                    val merged = buffer.merge(handle.state.inputText, transcript)
                    if (merged != handle.state.inputText) {
                        handle.update { composer = composer.copy(inputText = merged) }
                        // In end-of-speech mode, (re)start the silence debounce on each new word so a
                        // pause after speech ends the session even without a recognizer final.
                        if (sttEndOfSpeech.value && buffer.producedText) armSilenceTimer()
                    }
                    if (result.isFinal()) {
                        log.d { "Final result after user stop, finalizing" }
                        finalizeSession(autoSend = true)
                    }
                }
                if (recognitionError != null && !finished) {
                    val wasStop = stopRequested
                    if (!wasStop && requestedOnDevice) {
                        // On-device recognition failed at runtime (e.g. the language model isn't
                        // installed for this locale). Retry on Apple's cloud recognizer before
                        // surfacing an error — mirrors the Android controller's network fallback. The
                        // latch keeps subsequent continuous restarts on the cloud too.
                        triedCloudFallback = true
                        log.w { "on-device recognition failed; retrying on cloud: ${recognitionError.localizedDescription}" }
                        restartRecognition(useOnDevice = false)
                        return@launch
                    }
                    log.e { "Recognition error: ${recognitionError.localizedDescription}" }
                    // Route through the single teardown path so the watchdog/cleanup/state logic can't
                    // drift. A cancelled/expected error after a user stop+endAudio isn't worth surfacing
                    // (and still auto-sends what was captured); a mid-session error surfaces.
                    finalizeSession(
                        autoSend = wasStop,
                        errorMessage = if (wasStop) {
                            null
                        } else {
                            "Speech recognition error: ${recognitionError.localizedDescription}"
                        },
                    )
                }
            }
        }
    }

    /**
     * Continuous-restart / cloud-fallback: cancel just the current recognition task+request and start
     * a new one on the still-running audio engine (the tap keeps feeding [recognitionRequest]). The
     * cancelled task may still deliver a queued callback, but its token no longer matches so it's
     * ignored (see [sessionToken]).
     */
    private fun restartRecognition(useOnDevice: Boolean) {
        // Cancel any end-of-speech silence timer armed against the OLD task: it would otherwise keep
        // counting down across the restart/cloud-fallback and finalize (and auto-send) the session
        // mid-utterance before the new task's first partial re-arms it.
        silenceJob?.cancel()
        silenceJob = null
        recognitionTask?.cancel()
        recognitionTask = null
        recognitionRequest = null
        startRecognitionTask(useOnDevice)
    }

    override fun stopRecording() {
        if (!handle.state.isRecording || stopRequested) return
        stopRequested = true
        // A user stop supersedes the end-of-speech silence debounce.
        silenceJob?.cancel()
        silenceJob = null
        // Flush buffered audio so words spoken just before the tap make the final transcript, then
        // stop feeding the recognizer but KEEP the task alive so its isFinal callback commits the
        // complete text and fires auto-send. A watchdog force-finalizes (committing the last partial
        // already shown in the composer) if no final arrives.
        recognitionRequest?.endAudio()
        stopAudioEngine()
        handle.update { voice = voice.copy(isRecording = false, isTranscribing = true) }
        // The cloud recognizer's final lags well past the on-device timeout; wait longer for it before
        // force-finalizing so we don't commit/auto-send the rougher last partial instead of the final.
        val watchdogMs = if (requestedOnDevice) STOP_WATCHDOG_MS else CLOUD_STOP_WATCHDOG_MS
        stopWatchdogJob = handle.scope.launch {
            delay(watchdogMs)
            finalizeSession(autoSend = true)
        }
    }

    /** Terminal failure while starting a session: tear down and surface [message]. */
    private fun failStart(message: String) {
        finalizeSession(autoSend = false, errorMessage = message)
    }

    /**
     * Tear down once and (optionally) fire auto-send. Idempotent via [finished]. [errorMessage], when
     * non-null, replaces the composer error; null preserves whatever's there. Only invoked from the
     * Main-dispatched scope (recognition callback / watchdog / stop / cancel / release / failStart).
     */
    private fun finalizeSession(autoSend: Boolean, errorMessage: String? = null) {
        if (finished) return
        finished = true
        stopWatchdogJob?.cancel()
        stopWatchdogJob = null
        silenceJob?.cancel()
        silenceJob = null
        cleanupAudio()
        handle.update {
            voice = voice.copy(isRecording = false, isTranscribing = false)
            error = errorMessage ?: error
        }
        if (autoSend) maybeAutoSend()
    }

    /**
     * Fire auto-send-after-STT. Reachable only from [finalizeSession] (guarded by [finished]) on the
     * Main thread, so it runs at most once per session without a separate latch.
     */
    private fun maybeAutoSend() {
        if (buffer.shouldAutoSend(
                handle.state.inputText,
                autoSendAfterStt.value,
                handle.state.isStreaming,
            )
        ) {
            onTranscriptionComplete()
        }
    }

    override fun cancelRecording() {
        finished = true
        // Also drop a pending permission prompt so its grant callback can't start a session after
        // this cancel (the callback re-checks finished, but clearing the latch keeps startRecording
        // usable again immediately once permission is resolved).
        authorizationInFlight = false
        stopWatchdogJob?.cancel()
        stopWatchdogJob = null
        silenceJob?.cancel()
        silenceJob = null
        cleanupAudio()
        // Drop the dictated suffix and restore the pre-dictation text — but only if the user hasn't
        // edited the field since our last write; if they have, keep their edit (see DictationBuffer).
        handle.update {
            voice = voice.copy(isRecording = false, isTranscribing = false)
            composer = composer.copy(inputText = buffer.revert(composer.inputText))
        }
    }

    override fun onDeviceSpeechResult(transcribedText: String) {
        if (transcribedText.isBlank()) return
        val merged = appendToBase(handle.state.inputText, transcribedText)
        handle.update { composer = composer.copy(inputText = merged) }
    }

    override fun loadSpeechConfig() {
        // iOS uses on-device speech recognition only; no server STT config needed
    }

    override fun release() {
        finished = true
        authorizationInFlight = false
        stopWatchdogJob?.cancel()
        stopWatchdogJob = null
        silenceJob?.cancel()
        silenceJob = null
        cleanupAudio()
    }

    /** Stop feeding audio to the recognizer without releasing the task/request (keeps a pending final alive). */
    @OptIn(ExperimentalForeignApi::class)
    private fun stopAudioEngine() {
        audioEngine?.let { engine ->
            if (engine.isRunning()) {
                engine.stop()
                engine.inputNode.removeTapOnBus(0u)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun cleanupAudio() {
        // Cancel before nulling so an abandoned task can't keep delivering callbacks. Harmless on the
        // natural teardown paths where the task is already complete. This is the SINGLE full-teardown
        // cancel site — finalize/cancel/release all funnel here, so no teardown path can forget it.
        // (Continuous restart uses restartRecognition, which cancels only the task, not the engine.)
        recognitionTask?.cancel()
        stopAudioEngine()
        audioEngine = null
        recognitionRequest = null
        recognitionTask = null
        speechRecognizer = null

        // Deactivate audio session
        try {
            AVAudioSession.sharedInstance().setActive(
                active = false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = null,
            )
        } catch (_: Exception) {
            // Best effort
        }
    }

    /**
     * (Re)start the end-of-speech silence debounce. Called on each new transcript word in
     * end-of-speech mode; if [END_OF_SPEECH_SILENCE_MS] elapses with no further speech, finalize the
     * session (auto-send fires when enabled). Runs on the Main-dispatched scope like every other guard.
     */
    private fun armSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = handle.scope.launch {
            delay(END_OF_SPEECH_SILENCE_MS)
            if (!finished) {
                log.d { "end-of-speech silence elapsed; finalizing" }
                finalizeSession(autoSend = true)
            }
        }
    }

    /**
     * Resolve [tag] (a requested BCP-47 tag, or a device `localeIdentifier`) to the SFSpeechRecognizer
     * supported locale that best matches, or null when none does. Tolerant of case, `_`-vs-`-`,
     * `@keyword` extensions, and script subtags: tries an exact normalized match, then language+region
     * dropping any script (so a device `zh-Hans-CN` resolves to the supported `zh-CN`), then
     * language-only (so `zh-Hans` resolves to the first supported `zh-…`). Returns the supported
     * NSLocale itself, so the failable `SFSpeechRecognizer(locale:)` is only ever handed a locale it
     * accepts — a verbatim string compare would false-negative on script/region-rich device locales
     * and wrongly abort dictation that the framework actually supports.
     */
    private fun bestSupportedLocale(tag: String): NSLocale? {
        val supported = SFSpeechRecognizer.supportedLocales().mapNotNull { it as? NSLocale }
        val target = normalizeLocaleTag(tag)
        supported.firstOrNull { normalizeLocaleTag(it.localeIdentifier) == target }?.let { return it }

        val targetLang = languageSubtag(target)
        val targetRegion = regionSubtag(target)
        if (targetRegion != null) {
            supported.firstOrNull {
                val n = normalizeLocaleTag(it.localeIdentifier)
                languageSubtag(n) == targetLang && regionSubtag(n) == targetRegion
            }?.let { return it }
        }
        return supported.firstOrNull { languageSubtag(normalizeLocaleTag(it.localeIdentifier)) == targetLang }
    }

    /** Normalize a locale tag: drop any `@keyword` extension, unify separators, lowercase. */
    private fun normalizeLocaleTag(tag: String): String =
        tag.substringBefore('@').replace('_', '-').lowercase()

    /** Language subtag of a normalized tag ("zh-hans-cn" → "zh"). */
    private fun languageSubtag(normalized: String): String = normalized.substringBefore('-')

    /** Region subtag of a normalized tag: the 2-alpha or 3-digit subtag ("zh-hans-cn" → "cn"), else null. */
    private fun regionSubtag(normalized: String): String? =
        normalized.split('-').drop(1).lastOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }

    private companion object {
        // End-of-speech silence debounce: how long after the last recognized word to treat dictation
        // as finished. Long enough to ride out natural mid-sentence pauses, short enough for hands-free.
        const val END_OF_SPEECH_SILENCE_MS = 1800L
    }
}
