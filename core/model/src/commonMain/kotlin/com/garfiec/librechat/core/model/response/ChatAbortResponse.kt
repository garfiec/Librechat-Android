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
     * follow-ups. Parse-layer only — mobile has no steering UI, so these are currently
     * observed and dropped.
     */
    val pendingSteers: List<PendingSteer> = emptyList(),
)
