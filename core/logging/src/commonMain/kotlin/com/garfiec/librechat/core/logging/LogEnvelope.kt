package com.garfiec.librechat.core.logging

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One structured diagnostic record, serialized as a single JSONL line. */
@Serializable
data class LogRecord(
    val ts: String,
    val level: String,
    val tag: String,
    val msg: String,
    val attrs: Map<String, String> = emptyMap(),
    val thread: String,
    val origin: String? = null,
)

/**
 * Kermit's `LogWriter` only carries a plain `String` message — there's no structured channel for
 * attributes. [Diag] therefore encodes `{msg, attrs, origin}` into the message string behind a
 * non-printable sentinel, and [PersistentLogWriter] decodes it back out.
 *
 * Crucially, when there are no attrs and no origin, [encode] returns the message **unchanged** — so
 * a plain `Logger.d("tag") { "msg" }` (the ~196 existing call sites) carries no sentinel and is
 * recorded with empty attrs, byte-identical to before. The decoder treats any message lacking the
 * sentinel, or one whose payload fails to parse, as plain text.
 */
object LogEnvelope {
    // SOH (U+0001) control char, built at runtime to keep a non-printable byte out of source.
    private val SOH: String = Char(1).toString()

    /** Marks a message as carrying a structured payload: SOH + "LCLOG" + SOH + json. */
    val SENTINEL: String = SOH + "LCLOG" + SOH

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Serializable
    private data class Payload(
        val msg: String,
        val attrs: Map<String, String> = emptyMap(),
        val origin: String? = null,
    )

    data class Decoded(val msg: String, val attrs: Map<String, String>, val origin: String?)

    fun encode(msg: String, attrs: Map<String, String>, origin: LogOrigin?): String {
        if (attrs.isEmpty() && origin == null) return msg
        return SENTINEL + json.encodeToString(Payload(msg, attrs, origin?.wire))
    }

    fun decode(message: String): Decoded {
        if (!message.startsWith(SENTINEL)) return Decoded(message, emptyMap(), null)
        return runCatching {
            val payload = json.decodeFromString<Payload>(message.substring(SENTINEL.length))
            Decoded(payload.msg, payload.attrs, payload.origin)
        }.getOrElse { Decoded(message, emptyMap(), null) }
    }
}
