package com.garfiec.librechat.core.model.steer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wire values of the `code` field a rejected `POST /api/agents/chat/steer` returns.
 *
 * The code — not the HTTP status — is what tells a client how to degrade, because several
 * distinct outcomes share a status. See [SteerFallback].
 */
object SteerRejectionCodes {
    /** 404: the run finished (or never existed) before the steer landed. */
    const val NO_ACTIVE_RUN = "NO_ACTIVE_RUN"

    /** 409: the run is parked on a human-review pause, so nothing can be injected into it. */
    const val RUN_PAUSED = "RUN_PAUSED"

    /** 501: the server's agent SDK cannot inject mid-run. */
    const val STEER_UNSUPPORTED = "STEER_UNSUPPORTED"

    /** 429: too many steers are queued and undrained. */
    const val STEER_QUEUE_FULL = "STEER_QUEUE_FULL"

    /** 413: the text exceeds the server's per-steer character cap. */
    const val STEER_TOO_LONG = "STEER_TOO_LONG"
}

/**
 * Server-side caps the client mirrors so it can refuse locally instead of spending a
 * round-trip on a request the server will reject.
 */
object SteerLimits {
    /**
     * Default per-steer character cap (`STEER_MAX_LENGTH`, overridable server-side). Mirrored
     * as the *default*, not the truth: a server that lowered it answers `STEER_TOO_LONG`, which
     * degrades to the queue like any other rejection.
     */
    const val MAX_TEXT_LENGTH = 16_000

    /** Server-side queue depth per run; exceeding it answers `STEER_QUEUE_FULL`. */
    const val MAX_QUEUE_DEPTH = 10
}

/**
 * What the client should do with the user's text after a steer did not reach the run.
 *
 * Every branch keeps the text: a steer that cannot be injected is never a reason to drop what
 * the user typed. The distinction is only *where* it goes.
 */
enum class SteerFallback {
    /** The run is gone; send the text as an ordinary new turn instead. */
    SEND_NOW,

    /** The run is alive but unreachable (paused / unsupported / queue full); hold it locally. */
    QUEUE,
}

/**
 * Maps a rejection [code] to the client's degradation.
 *
 * `NO_ACTIVE_RUN` is the only code that means the turn is over — everything else (including an
 * unrecognized code, a route that 404s with no body on a pre-0.8.8 server, or a transport
 * failure) leaves the local run believed live, so the safe move is to hold the text in the
 * follow-up queue where the run's own end will drain it.
 */
fun steerFallbackFor(code: String?): SteerFallback = when (code) {
    SteerRejectionCodes.NO_ACTIVE_RUN -> SteerFallback.SEND_NOW
    else -> SteerFallback.QUEUE
}

private val rejectionJson = Json { ignoreUnknownKeys = true }

/**
 * Extracts the `code` from a steer rejection body.
 *
 * Returns null for a body that is absent, not a JSON object, or carries no `code` — an HTML
 * 404 from a server without the route, for instance. Callers must treat null as "unknown
 * rejection" and degrade, never as "succeeded".
 */
fun parseSteerRejectionCode(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val element = runCatching { rejectionJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val obj = element as? JsonObject ?: return null
    // Safe cast rather than `.jsonPrimitive`: that accessor throws on an object/array value,
    // and a malformed error body must not turn a degradable rejection into a crash.
    return (obj["code"] as? JsonPrimitive)?.contentOrNull
}
