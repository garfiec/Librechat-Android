package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.PendingSteer
import kotlinx.serialization.Serializable

@Serializable
data class ChatAbortResponse(
    val success: Boolean = true,
    /** Stream id of the job the server actually aborted. */
    val aborted: String? = null,
    /**
     * Steers the user queued that never reached an injection boundary before the abort.
     * The server hands them back exactly once so the client can restore them as queued
     * follow-ups; it clears its own copy when it writes this, so an ack that carries them and
     * is ignored loses the user's words. Claimed and re-homed by `SteeringDelegate.reclaim`.
     */
    val pendingSteers: List<PendingSteer> = emptyList(),
)
