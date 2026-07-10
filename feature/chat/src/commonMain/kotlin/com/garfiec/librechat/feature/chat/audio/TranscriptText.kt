package com.garfiec.librechat.feature.chat.audio

/**
 * Appends dictated [addition] onto [base], inserting a single space separator only when [base] is
 * non-blank and doesn't already end in whitespace. Returns [base] unchanged for a blank [addition].
 *
 * Shared across platforms (Android [VoiceInputDelegate], iOS `IosVoiceInput`) so the composer's
 * live-transcript merge behaves identically instead of each platform inlining a slightly different
 * separator rule.
 */
internal fun appendToBase(base: String, addition: String): String {
    if (addition.isBlank()) return base
    val separator = if (base.isNotBlank() && !base.endsWith(" ")) " " else ""
    return base + separator + addition
}

/**
 * Tracks composer text across a live-dictation session so the transcript appends after any pre-typed
 * text, a user edit mid-dictation isn't clobbered by the next cumulative transcript, and a cancel
 * restores the original text unless the user edited since.
 *
 * Shared by the Android [VoiceInputDelegate] (browser/on-device path) and iOS `IosVoiceInput` so the
 * merge / rebase-on-edit / revert-on-cancel rules can't drift between platforms — each platform only
 * owns its recognizer transport and feeds transcripts through here. Not thread-safe: callers must
 * confine access to a single thread (the Main-dispatched ViewModel scope on both platforms).
 */
internal class DictationBuffer {
    /** Composer text before dictation began — restored verbatim if the session is cancelled. */
    private var originalText: String = ""

    /** Committed base the live transcript appends onto; rebased to the user's text if they edit. */
    private var baseText: String = ""

    /** The last value written to the composer — used to detect the user typing mid-dictation. */
    private var lastApplied: String = ""

    /**
     * The transcript last appended to [baseText] this segment. Recognizer partials are *cumulative*
     * (each replaces the prior one) on both platforms, so a mid-dictation user edit must re-derive the
     * base by stripping this suffix — otherwise re-appending the cumulative transcript duplicates the
     * words already shown (e.g. "hello world" + edit + next cumulative → "hello world! hello world…").
     */
    private var lastTranscript: String = ""

    /** Whether this session produced any (non-blank) transcript. Gates auto-send. */
    var producedText: Boolean = false
        private set

    /** Snapshot the composer as the base future transcripts append onto. */
    fun begin(currentText: String) {
        originalText = currentText
        baseText = currentText
        lastApplied = currentText
        lastTranscript = ""
        producedText = false
    }

    /**
     * Merge a (partial or cumulative) [transcript] onto the base and return the composer text to show.
     * If the user typed/deleted since our last write, their edit survives: the base is re-derived by
     * stripping the previously-applied cumulative [transcript] suffix (so it isn't duplicated), falling
     * back to their text verbatim when they edited inside the live-transcript region itself.
     */
    fun merge(currentText: String, transcript: String): String {
        if (currentText != lastApplied) baseText = stripTranscriptSuffix(currentText, lastTranscript)
        if (transcript.isNotBlank()) producedText = true
        val merged = appendToBase(baseText, transcript)
        lastApplied = merged
        lastTranscript = transcript
        return merged
    }

    /**
     * Commit a finalized [transcript] segment as the new base (continuous dictation, where each
     * segment is independent), returning the composer text to show.
     */
    fun commit(currentText: String, transcript: String): String {
        val merged = merge(currentText, transcript)
        baseText = merged
        lastTranscript = ""
        return merged
    }

    /** The composer text to show after a cancel: the original unless the user edited since our last write. */
    fun revert(currentText: String): String =
        if (currentText == lastApplied) originalText else currentText

    /**
     * Whether an ended live-dictation session should auto-send: the user enabled it, this session
     * actually [producedText], the composer isn't blank, and we're not already streaming. Shared by
     * the Android browser path and iOS so the predicate can't drift between platforms.
     */
    fun shouldAutoSend(currentText: String, autoSendEnabled: Boolean, isStreaming: Boolean): Boolean =
        shouldAutoSendTranscript(producedText && currentText.isNotBlank(), autoSendEnabled, isStreaming)
}

/**
 * The single auto-send-after-STT gate: send only when the user enabled it, dictation actually
 * produced content, and we're not already streaming. Every STT path routes through this so the rule
 * can't drift — [DictationBuffer.shouldAutoSend] feeds it `producedText && composer-non-blank`; the
 * External (record→upload) and legacy Intent-overlay paths feed it their transcript-non-blank.
 */
internal fun shouldAutoSendTranscript(
    hasContent: Boolean,
    autoSendEnabled: Boolean,
    isStreaming: Boolean,
): Boolean = autoSendEnabled && hasContent && !isStreaming

/**
 * Strip a trailing cumulative [transcript] (plus the single separator space [appendToBase] may have
 * inserted) from [text] to recover the base it was appended onto. Returns [text] unchanged when the
 * suffix isn't present (blank transcript, or the user edited inside the transcript region).
 */
internal fun stripTranscriptSuffix(text: String, transcript: String): String {
    if (transcript.isEmpty() || !text.endsWith(transcript)) return text
    val base = text.dropLast(transcript.length)
    return if (base.endsWith(" ")) base.dropLast(1) else base
}
