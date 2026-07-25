package com.garfiec.librechat.core.model.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Body of `POST /api/agents/chat/resume` — resolves a run paused for human review.
 *
 * The agent-selection fields are NOT decoration. The resume route re-runs the same
 * `buildEndpointOption` middleware a normal send goes through, and then compares the request
 * against the pause's pinned fingerprint (endpoint, endpointType, agent_id, model, spec,
 * promptPrefix, ephemeralAgent). A mismatch is rejected 403 "Cannot resume with a different
 * agent configuration", so these must be the SAME values the paused turn was sent with, not
 * whatever the picker shows now.
 *
 * Exactly one decision channel is populated, selected by the pause's payload type:
 * [decisions] for `tool_approval` (one entry per paused `tool_call_id` — a partial batch is
 * 400) and [answer] for `ask_user_question`.
 *
 * The reply is only an ack ([com.garfiec.librechat.core.model.response.ChatResumeResponse]);
 * the continuation streams over the SSE connection already open for this conversation.
 */
@Serializable
data class ChatResumeRequest(
    val conversationId: String,
    /** Identifies the paused action; a stale/mismatched id is rejected 409. */
    val actionId: String,
    val endpoint: String,
    val endpointType: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val model: String? = null,
    val spec: String? = null,
    val promptPrefix: String? = null,
    val ephemeralAgent: EphemeralAgent? = null,
    val isTemporary: Boolean? = null,
    /** `tool_approval` only: one resolution per paused tool call. */
    val decisions: List<ToolApprovalResolution>? = null,
    /** `ask_user_question` only: the user's reply (server caps it at 16k characters). */
    val answer: String? = null,
)

/**
 * One tool call's decision, in the wire format the resume route adapts to the agent SDK.
 *
 * Constraints the server enforces: `edit` requires [editedArguments], `respond` requires
 * [responseText], and the chosen [decision] must be in that call's
 * [com.garfiec.librechat.core.model.ToolReviewConfig.allowedDecisions] (403 otherwise).
 */
@Serializable
data class ToolApprovalResolution(
    @SerialName("tool_call_id") val toolCallId: String,
    /** One of [com.garfiec.librechat.core.model.ToolApprovalDecisions]. */
    val decision: String,
    val editedArguments: JsonObject? = null,
    val responseText: String? = null,
    val reason: String? = null,
)
