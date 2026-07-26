package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.PendingSteer
import kotlinx.serialization.Serializable

@Serializable
data class ChatStatusResponse(
    /**
     * True while the server considers the run resumable. Note this is **not** the same as
     * "tokens are still arriving": a run paused for human review (`status == "requires_action"`
     * with a live [pendingAction]) also reports active, so the client resumes/subscribes
     * rather than treating the turn as finished.
     */
    val active: Boolean = false,
    val conversationId: String? = null,
    /** Job status — `"running"` | `"requires_action"` | terminal states. Absent when no job exists. */
    val status: String? = null,
    /**
     * The live human-review prompt when the run is paused, as a client-safe projection.
     * Null when the run is streaming normally, when no job exists, or when the pause has
     * gone stale.
     */
    val pendingAction: PendingAction? = null,
    /**
     * Acknowledged steers the terminal parked because no subscriber was live to receive them.
     * Claim-on-read: the server clears them once returned, so a caller that ignores this list
     * drops them permanently. Only populated when the run is *not* active.
     *
     * **Always empty on a value returned by `ChatRepository.checkStreamStatus`** — that call
     * hands the list to its `claimSteers` parameter and empties it here, so the claim cannot be
     * skipped. This field carries data only on the raw wire decode.
     */
    val unrecoveredSteers: List<PendingSteer> = emptyList(),
)
