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
 * 400), and for `ask_user_question` either [answers] or [answer] depending on whether the
 * payload carried a `questions` array. The server picks the branch off the PAYLOAD, not off
 * which field the request populated, so the choice is not the client's to make.
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
    /**
     * `ask_user_question`, **single-question pause only**: the user's reply (16k character cap).
     *
     * Accepted only when the pause's payload carries no `questions` array. Sending it for a
     * batched pause is rejected 400 — the two channels are not interchangeable and never both
     * populated.
     */
    val answer: String? = null,
    /**
     * `ask_user_question`, **batched pause only**: one answer per question id.
     *
     * The server requires the map to cover every id in the payload's `questions` array exactly —
     * a missing id is 400 "Answers are required for every question", an extra one is 400 "Answers
     * contain an unknown question id", and an empty string counts as missing. So this is built
     * from the payload's own ids, never from the form's field order.
     *
     * `moderateText` and the PII filter now scan this map, so a moderated deployment can refuse a
     * resume on the content of an answer — a denial the card has to surface, since the run stays
     * paused and the user needs to know to reword rather than to retry.
     */
    val answers: Map<String, String>? = null,
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
