package com.garfiec.librechat.core.model.response

import kotlinx.serialization.Serializable

/**
 * The phase-1 envelope of the two-phase SSE protocol: `POST /api/agents/chat/{endpoint}`.
 *
 * [conversationId] is the only field this client acts on — it is also the stream id, and phase 2
 * is a GET against it. The rest is decode surface, recorded so a newer server's envelope is
 * readable rather than because anything branches on it.
 *
 * **This client speaks generation protocol v1** and advertises nothing, so the server keeps it
 * there. Every v2-only behaviour (structured reconcile frames, `on_steer_updated`, steer
 * replacement fencing) is unreachable until the client opts in on all three transports — body,
 * query and the `x-librechat-generation-protocol` header, where the LOWER of the markers wins.
 * Opting in is deliberately out of scope here.
 */
@Serializable
data class ChatStartResponse(
    val conversationId: String,
    /**
     * `started` on an ordinary send. A newer server can also answer `resumed`, `replaced`,
     * `settled` or `predecessor_mismatch`. Notably `settled` carries **no `streamId`** — the run
     * this request would have started has already finished, so there is nothing to subscribe to.
     */
    val status: String? = null,
    /**
     * The generation epoch, used server-side to fence a stale abort/resume/steer against a newer
     * turn that reused the same conversation-scoped stream id. Omitting it from later requests
     * stays legal but leaves them unfenced.
     */
    val generationCreatedAt: Long? = null,
    /** The protocol the server settled on for this generation. 1 for this client. */
    val generationProtocolVersion: Int? = null,
)
