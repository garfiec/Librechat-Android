package com.garfiec.librechat.core.logging

import co.touchlab.kermit.Logger

/** Fault attribution for failure records, so triage doesn't require guessing where a fault arose. */
enum class LogOrigin(val wire: String) {
    /** App-side: serialization/parse error, null/empty local state, navigation bug, pre-byte timeout. */
    CLIENT("client"),

    /** Server-side: non-2xx status, server error envelope, malformed/incomplete SSE, version mismatch. */
    SERVER("server"),

    /** Transport-side: connectivity loss, DNS/TLS, socket errors before any HTTP response. */
    NETWORK("network"),
}

/**
 * Thin structured-logging facade over Kermit. Lets call sites attach key-value [attrs] and an
 * [origin] without string-concatenating. Routes through Kermit so existing console/NSLog writers
 * and the [PersistentLogWriter] sink both see it.
 *
 * The message lambda is only invoked if Kermit decides to log at that severity (laziness preserved),
 * and when [attrs] is empty and [origin] is null the emitted string is byte-identical to a plain
 * `Logger.x` call — so this is purely additive over the existing ~196 direct call sites.
 *
 * Redaction is applied centrally at the sink ([com.garfiec.librechat.core.logging.redact.LogRedactor]),
 * not here — callers never have to remember to scrub. Still: never put raw tokens or message/
 * conversation **content** in [attrs]; pass identifiers (which get hashed) or low-cardinality facts.
 */
object Diag {
    fun v(
        tag: String,
        origin: LogOrigin? = null,
        throwable: Throwable? = null,
        attrs: Map<String, String> = emptyMap(),
        message: () -> String,
    ) = Logger.withTag(tag).v(throwable) { LogEnvelope.encode(message(), attrs, origin) }

    fun d(
        tag: String,
        origin: LogOrigin? = null,
        throwable: Throwable? = null,
        attrs: Map<String, String> = emptyMap(),
        message: () -> String,
    ) = Logger.withTag(tag).d(throwable) { LogEnvelope.encode(message(), attrs, origin) }

    fun i(
        tag: String,
        origin: LogOrigin? = null,
        throwable: Throwable? = null,
        attrs: Map<String, String> = emptyMap(),
        message: () -> String,
    ) = Logger.withTag(tag).i(throwable) { LogEnvelope.encode(message(), attrs, origin) }

    fun w(
        tag: String,
        origin: LogOrigin? = null,
        throwable: Throwable? = null,
        attrs: Map<String, String> = emptyMap(),
        message: () -> String,
    ) = Logger.withTag(tag).w(throwable) { LogEnvelope.encode(message(), attrs, origin) }

    fun e(
        tag: String,
        origin: LogOrigin? = null,
        throwable: Throwable? = null,
        attrs: Map<String, String> = emptyMap(),
        message: () -> String,
    ) = Logger.withTag(tag).e(throwable) { LogEnvelope.encode(message(), attrs, origin) }
}
