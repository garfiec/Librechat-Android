package com.garfiec.librechat.core.model.steer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wire values of the `code` field a rejected `POST /api/agents/chat/steer` returns.
 *
 * Every rejection degrades the same way — the text goes to the follow-up queue, which drains
 * itself the moment the run is over — so these are diagnostic rather than a branch point. They
 * are parsed and logged so a rejection that turns out to be systematic is visible.
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
