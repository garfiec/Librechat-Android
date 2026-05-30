package com.garfiec.librechat.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import com.garfiec.librechat.core.logging.io.LogSink
import com.garfiec.librechat.core.logging.redact.LogRedactor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Custom Kermit [LogWriter] that captures every log (the ~196 existing direct calls included),
 * decodes any [Diag] envelope, redacts, and appends one JSONL [LogRecord] per line to the bounded
 * on-disk [LogSink].
 *
 * Hot path is non-blocking: [log] only does a [Channel.trySend] (capacity-bounded, oldest-dropped),
 * and a single drain coroutine on a dedicated single-thread dispatcher is the sole file writer — so
 * there are no locks and no rotation races. Failures are swallowed and **never** re-enter logging
 * (that would recurse), so logging can neither crash the app nor stall a caller.
 */
class PersistentLogWriter internal constructor(
    private val sink: LogSink,
    private val redactor: LogRedactor,
    private val threadName: () -> String,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    capacity: Int,
    private val maxThrowableChars: Int,
    private val maxCrashThrowableChars: Int,
) : LogWriter() {

    private val json = Json { encodeDefaults = false }
    private val channel = Channel<String>(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch(dispatcher) {
            for (line in channel) {
                runCatching { sink.append(line) }
            }
        }
    }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = runCatching { buildLine(severity.name, message, tag, throwable) }.getOrNull()
            ?: runCatching { fallbackLine(severity.name, tag) }.getOrNull()
            ?: return
        channel.trySend(line)
    }

    /**
     * Synchronous, best-effort write for the crash path: the process may die before the async drain
     * runs, so a fatal record must hit disk immediately. Skips the channel and rotation.
     */
    fun writeCrashRecord(
        tag: String,
        message: String,
        throwable: Throwable?,
        attrs: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            val merged = LinkedHashMap(attrs)
            throwable?.let { merged["throwable"] = formatThrowable(it, maxCrashThrowableChars) }
            val record = LogRecord(
                ts = Clock.System.now().toString(),
                level = Severity.Error.name,
                tag = tag,
                msg = redactor.redact(message),
                attrs = redactor.redactAttrs(merged),
                thread = threadName(),
                origin = LogOrigin.CLIENT.wire,
            )
            sink.appendBlocking(json.encodeToString(record))
        }
    }

    private fun buildLine(level: String, message: String, tag: String, throwable: Throwable?): String {
        val decoded = LogEnvelope.decode(message)
        val attrs = LinkedHashMap<String, String>(decoded.attrs)
        throwable?.let { attrs["throwable"] = formatThrowable(it, maxThrowableChars) }
        val record = LogRecord(
            ts = Clock.System.now().toString(),
            level = level,
            tag = tag,
            msg = redactor.redact(decoded.msg),
            attrs = redactor.redactAttrs(attrs),
            thread = threadName(),
            origin = decoded.origin,
        )
        return json.encodeToString(record)
    }

    /**
     * Renders a throwable's stack trace, truncated to [maxChars] so a few exceptions can't dominate
     * the bounded buffer. Redaction (which strips embedded response bodies) is applied later by the
     * caller via [LogRedactor.redactAttrs] on the "throwable" value.
     */
    private fun formatThrowable(throwable: Throwable, maxChars: Int): String {
        val full = throwable.stackTraceToString()
        return if (full.length <= maxChars) full else full.take(maxChars) + "…(truncated)"
    }

    private fun fallbackLine(level: String, tag: String): String =
        json.encodeToString(
            LogRecord(
                ts = Clock.System.now().toString(),
                level = level,
                tag = tag,
                msg = "<unencodable log record>",
                thread = threadName(),
            ),
        )
}
