package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/agents/chat/steer` — queues instruction text for injection into the
 * run that is *currently generating* on [conversationId] (streamId === conversationId).
 *
 * Unlike a normal send this carries no agent selection: the server injects into the
 * originating run and re-derives its identity from the job's own metadata, then re-checks the
 * caller against that agent's ACL. A model/endpoint sent here would be ignored.
 *
 * A steer does not become a message; it becomes a `steer` content part inside the reply the
 * run is already producing, delivered back over the SSE stream as `on_steer_applied`.
 *
 * The route also accepts a `files` array (owner-scoped, re-resolved server-side). Mobile
 * deliberately does not send one: a during-run send that carries attachments is routed to the
 * follow-up queue instead, where the existing upload/usage-marking path already handles them.
 */
@Serializable
data class SteerRequest(
    val conversationId: String,
    /** Trimmed instruction text. The server caps it — see [com.garfiec.librechat.core.model.steer.SteerLimits]. */
    val text: String,
)

/**
 * Body of `POST /api/agents/chat/steer/cancel` — withdraws a steer that has not been injected.
 *
 * Losing the race is not an error: the server answers `200 {removed:false}` when the steer
 * already went in (the inline content part is then authoritative) or the run ended.
 */
@Serializable
data class SteerCancelRequest(
    val conversationId: String,
    val steerId: String,
)
