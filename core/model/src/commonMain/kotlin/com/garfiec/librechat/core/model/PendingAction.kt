package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A run paused for human review — tool approval or an `ask_user_question` prompt.
 *
 * This is the server's *client-safe projection*: `requestFingerprint` and `resumeContext`
 * (which carries resolved model parameters) are stripped before the record leaves the
 * server, so this type must never be sent back as-is. Resuming a paused run submits
 * [actionId] plus the user's decision; the server rehydrates the turn config from its own
 * copy of the record.
 *
 * Surfaced on `GET /api/agents/chat/status/:conversationId`, on the live `on_pending_action`
 * SSE event, and on the resume `sync` frame as `resumeState.pendingAction` — the three paths a
 * client can learn about a pause through (live, reconnect, cold open).
 */
@Serializable
data class PendingAction(
    val actionId: String? = null,
    val streamId: String? = null,
    val conversationId: String? = null,
    val runId: String? = null,
    val responseMessageId: String? = null,
    /** The interrupt itself, discriminated by [PendingActionPayload.type]. */
    val payload: PendingActionPayload? = null,
    val createdAt: Long? = null,
    /** Epoch millis after which the server treats the pause as stale and finalizes the run. */
    val expiresAt: Long? = null,
    val interruptId: String? = null,
    val threadId: String? = null,
) {
    /** True when this pause asks the user to approve/reject one or more tool calls. */
    val isToolApproval: Boolean get() = payload?.type == PendingActionTypes.TOOL_APPROVAL

    /** True when this pause asks the user a clarifying question. */
    val isAskUserQuestion: Boolean get() = payload?.type == PendingActionTypes.ASK_USER_QUESTION

    /**
     * The question id a single free-text answer resolves this batch through, or null.
     *
     * Non-null only for a batch of exactly one answerable question — the one batch shape a lone
     * text field can cover, submitted as `answers = {id: text}` rather than as a bare `answer`,
     * which the batched branch of the resume route rejects outright.
     */
    val soleAskQuestionId: String?
        get() = payload?.questions?.singleOrNull()?.takeIf { it.isAnswerable }?.id

    /**
     * True when one free-text field carries everything this pause needs to resolve.
     *
     * A real multi-question batch is false: the route requires an answer per id and 400s on a body
     * missing any of them, so a single field cannot resolve it no matter what is typed. Only the
     * card, which renders a field per question, can.
     */
    val isSingleAnswerAsk: Boolean
        get() = isAskUserQuestion && (payload?.questions == null || soleAskQuestionId != null)

    /**
     * True when the composer's send can make progress on this pause: a bare-answer ask, or a
     * batch whose every question is answerable — the composer then fills them in order, one send
     * per question, and the full map submits once the last one is in. A batch containing an
     * unanswerable (blank-id) question can only be resolved from the card or expire, so routing
     * composer text at it would consume words nothing can submit.
     */
    val isComposerAnswerableAsk: Boolean
        get() = isAskUserQuestion && payload?.questions.let { questions ->
            questions == null || (questions.isNotEmpty() && questions.all { it.isAnswerable })
        }
}

/** Wire values of the `payload.type` discriminator. */
object PendingActionTypes {
    const val TOOL_APPROVAL = "tool_approval"
    const val ASK_USER_QUESTION = "ask_user_question"
}

/**
 * Flattened union of the two interrupt payloads (`tool_approval` | `ask_user_question`).
 *
 * Modelled as one class with per-variant nullable fields rather than a sealed hierarchy: the
 * discriminator rides *inside* the object as an ordinary `type` field, which kotlinx's
 * polymorphic decoding would consume rather than expose, and an unknown future variant must
 * parse rather than throw (see the `ignoreUnknownKeys` contract in core/model/CLAUDE.md).
 */
@Serializable
data class PendingActionPayload(
    val type: String? = null,
    /** `tool_approval`: one entry per paused tool call. */
    @SerialName("action_requests") val actionRequests: List<ToolApprovalRequest> = emptyList(),
    /** `tool_approval`: per-call policy, joined to [actionRequests] by `tool_call_id`. */
    @SerialName("review_configs") val reviewConfigs: List<ToolReviewConfig> = emptyList(),
    /**
     * `ask_user_question`: the question to put to the user.
     *
     * On a batched pause upstream keeps this populated with the FIRST item as a display
     * fallback, so its presence does not mean the pause is single-question — [questions] does.
     */
    val question: AskUserQuestionRequest? = null,
    /**
     * `ask_user_question`: present when the agent asked several related questions in one call.
     *
     * **This field alone decides which resume body is legal.** The resume route branches on
     * `Array.isArray(payload.questions)`: when it is an array it requires
     * [com.garfiec.librechat.core.model.request.ChatResumeRequest.answers] covering every id and
     * rejects a bare `answer`; when it is absent only `answer` is accepted. An empty list is NOT
     * the same as absence — upstream rejects a zero-length batch as invalid — so this must stay
     * nullable rather than defaulting to `emptyList()`.
     */
    val questions: List<AskUserQuestionItem>? = null,
    /**
     * The ask tool call that raised this interrupt.
     *
     * Present from `@librechat/agents` > 3.3.8. Match on this when it is there; fall back to
     * position only when it is not — positional attribution puts the wrong question on screen
     * when a model emits several ask calls in one turn, and the resume then fails 400
     * `INVALID_TOOL_RESULTS`.
     */
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/**
 * One paused tool execution awaiting review.
 *
 * [arguments] is `string | object` upstream, so it stays a [JsonElement]; unwrap the primitive
 * case before rendering rather than assuming either shape.
 */
@Serializable
data class ToolApprovalRequest(
    val name: String = "",
    val arguments: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String = "",
    val description: String? = null,
)

/**
 * Which decisions the policy permits for one paused call.
 *
 * Joined to [ToolApprovalRequest] by [toolCallId], never by position: one batch can contain the
 * same tool twice (a model fanning out two parallel calls), and by-position mapping would then
 * apply the wrong policy. `action_name` is display-only.
 */
@Serializable
data class ToolReviewConfig(
    @SerialName("action_name") val actionName: String = "",
    @SerialName("tool_call_id") val toolCallId: String = "",
    @SerialName("allowed_decisions") val allowedDecisions: List<String> = emptyList(),
)

/** Wire values of [ToolReviewConfig.allowedDecisions] / [ToolApprovalResolution.decision]. */
object ToolApprovalDecisions {
    const val APPROVE = "approve"
    const val REJECT = "reject"
    const val EDIT = "edit"
    const val RESPOND = "respond"
}

/** A curated answer offered alongside an `ask_user_question` prompt. */
@Serializable
data class AskUserQuestionOption(
    val label: String = "",
    val value: String = "",
)

/** The question itself: free-form prompt with optional curated answers. */
@Serializable
data class AskUserQuestionRequest(
    val question: String = "",
    val description: String? = null,
    val options: List<AskUserQuestionOption> = emptyList(),
    /** When true the user may pick several options; the answer joins their values with ", ". */
    val multiSelect: Boolean = false,
)

/**
 * One independently answerable question inside a batched `ask_user_question` pause.
 *
 * [id] is the join key for the resume body's answers map and the server validates it against
 * `/^[A-Za-z][A-Za-z0-9_-]{0,63}$/`; an item whose id fails that pattern, or repeats an earlier
 * one, invalidates the whole batch server-side. It defaults to the empty string only so a
 * malformed payload decodes rather than throwing mid-stream — an empty id can never be answered,
 * and [isAnswerable] is what keeps one off the form.
 */
@Serializable
data class AskUserQuestionItem(
    val id: String = "",
    val question: String = "",
    /** Optional short heading rendered above the question. Server caps it at 80 characters. */
    val header: String? = null,
    val description: String? = null,
    val options: List<AskUserQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
) {
    /** False for a batch item that arrived without a usable id, which cannot be submitted. */
    val isAnswerable: Boolean get() = id.isNotEmpty()
}

/**
 * Server-side limits on an `ask_user_question` batch, mirrored so the form can respect them.
 *
 * MIRRORED SERVER CONSTANTS — module-private in `packages/api/src/agents/hitl/resume.ts`, so they
 * are exported nowhere and served nowhere. Registered in `scripts/mirrors.json` as
 * `ask-user-question-max-questions` and `ask-user-question-max-answer-length`.
 */
object AskUserQuestionLimits {
    /** `MAX_ASK_QUESTIONS`. A batch outside 1..4 is rejected as invalid. */
    const val MAX_QUESTIONS = 4

    /** `MAX_ASK_ANSWER_LENGTH`. Applies per answer on the batched path and to the bare answer. */
    const val MAX_ANSWER_LENGTH = 16_000
}

/**
 * A steer message the user queued mid-run.
 *
 * Reached three ways, each meaning something different:
 * - `resumeState.pendingSteers` on the sync frame — still queued, still going to be injected;
 * - `pendingSteers` on the `final` frame and on the `POST /api/agents/chat/abort` response —
 *   the run ended without injecting them;
 * - `unrecoveredSteers` on `GET /api/agents/chat/status` — acknowledged steers the server
 *   parked because no subscriber was live to receive them.
 *
 * The last two are **claim-on-read**: the server drops its copy as it hands them over, so a
 * client that parses and ignores them loses the user's words permanently.
 *
 * Upstream also carries a `files` array on a steer. Mobile does not model it: steering here is
 * text-only, and a reclaimed steer becomes a queued follow-up carrying its text alone.
 */
@Serializable
data class PendingSteer(
    val steerId: String? = null,
    val text: String? = null,
    val createdAt: Long? = null,
)
