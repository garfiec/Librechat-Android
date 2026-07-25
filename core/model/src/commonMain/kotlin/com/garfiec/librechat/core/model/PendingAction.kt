package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A run paused for human review — tool approval or an `ask_user_question` prompt.
 *
 * This is the server's *client-safe projection*: `requestFingerprint` and `resumeContext`
 * (which carries resolved model parameters) are stripped before the record leaves the
 * server, so this type must never be sent back as-is. Resuming a paused run submits
 * [actionId] plus the user's decision; the server rehydrates the turn config from its own
 * copy of the record.
 *
 * Surfaced on `GET /api/agents/chat/status/:conversationId` (and, for live clients, the
 * `on_pending_action` SSE event). Parse-layer only today — mobile has no approval UI yet,
 * so a paused run is simply reported as still active.
 */
@Serializable
data class PendingAction(
    val actionId: String? = null,
    val streamId: String? = null,
    val conversationId: String? = null,
    val runId: String? = null,
    val responseMessageId: String? = null,
    /**
     * Discriminated union (`tool_approval` | `ask_user_question`) whose shape depends on the
     * interrupt category. Left opaque until mobile renders approval cards.
     */
    val payload: JsonObject? = null,
    val createdAt: Long? = null,
    /** Epoch millis after which the server treats the pause as stale and finalizes the run. */
    val expiresAt: Long? = null,
    val interruptId: String? = null,
    val threadId: String? = null,
)

/**
 * A steer message the user queued mid-run that never reached an injection boundary.
 *
 * Returned by `POST /api/agents/chat/abort` (as `pendingSteers`) and by
 * `GET /api/agents/chat/status` (as `unrecoveredSteers`, claim-on-read) so a client can
 * restore the user's words as queued follow-ups instead of dropping them. Parse-layer
 * only today — mobile has no steering UI.
 */
@Serializable
data class PendingSteer(
    val steerId: String? = null,
    val text: String? = null,
    val createdAt: Long? = null,
)
