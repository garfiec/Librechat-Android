package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable

/**
 * Ack for an accepted `POST /api/agents/chat/steer` (HTTP 202).
 *
 * Acceptance means *queued*, not *applied*: the run injects the steer at its next tool-batch
 * boundary and only then emits `on_steer_applied`. [steerId] is the handle for both — cancelling
 * before injection, and matching the applied event to the chip that represents it.
 */
@Serializable
data class SteerResponse(
    /** `"queued"` on success. */
    val status: String? = null,
    val steerId: String? = null,
    /** Depth in the server-side queue after enqueue (1 = next to inject). */
    val position: Int? = null,
    val conversationId: String? = null,
)

/**
 * Ack for `POST /api/agents/chat/steer/cancel`.
 *
 * [removed] false is a 200, not an error: the cancel lost its race (the steer was already
 * injected, or the run ended), and the client should defer to the events it will receive
 * rather than treat the text as still pending.
 */
@Serializable
data class SteerCancelResponse(
    val removed: Boolean = false,
)
