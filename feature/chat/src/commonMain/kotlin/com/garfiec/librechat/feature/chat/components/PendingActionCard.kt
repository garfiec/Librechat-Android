package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.AskUserQuestionItem
import com.garfiec.librechat.core.model.AskUserQuestionLimits
import com.garfiec.librechat.core.model.AskUserQuestionOption
import com.garfiec.librechat.core.model.AskUserQuestionRequest
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.PendingActionPayload
import com.garfiec.librechat.core.model.ToolApprovalDecisions
import com.garfiec.librechat.core.model.ToolApprovalRequest
import com.garfiec.librechat.core.model.request.ToolApprovalResolution
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The in-thread card for a run paused on the user (v0.8.8 HITL): a tool batch awaiting approval,
 * or a clarifying question from the agent.
 *
 * Rendered in place of nothing — the reply's streaming bubble stays above it, because the turn is
 * unfinished and resumes into that same bubble. Submitting posts to `/chat/resume`; the card stays
 * up (disabled) until the server accepts, since a rejected decision has nowhere else to go back to.
 */
@Composable
fun PendingActionCard(
    pendingAction: PendingAction,
    isResolving: Boolean,
    onSubmitToolDecisions: (List<ToolApprovalResolution>) -> Unit,
    onSubmitAnswer: (String) -> Unit,
    onSubmitAnswers: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = pendingAction.payload ?: return
    // The batch is bounded server-side and a batch outside 1..MAX_QUESTIONS is rejected as
    // invalid, so render at most that many rather than growing the card without limit — and drop
    // items with no usable id, which cannot be answered at all.
    val batch = payload.questions
        ?.filter { it.isAnswerable }
        ?.take(AskUserQuestionLimits.MAX_QUESTIONS)
        .orEmpty()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        when {
            // `questions` — not `question` — is what selects the resume channel: upstream keeps
            // `question` populated with the first item as a display fallback even on a batch, so
            // branching on it would render one question and submit a body the route rejects.
            pendingAction.isAskUserQuestion && batch.isNotEmpty() -> AskUserQuestionBatchSection(
                actionId = pendingAction.actionId.orEmpty(),
                questions = batch,
                isResolving = isResolving,
                onSubmitAnswers = onSubmitAnswers,
            )
            pendingAction.isAskUserQuestion -> AskUserQuestionSection(
                // Key the answer editor to the action so a second question in the same turn
                // starts blank instead of inheriting the previous answer.
                actionId = pendingAction.actionId.orEmpty(),
                question = payload.question ?: AskUserQuestionRequest(),
                isResolving = isResolving,
                onSubmitAnswer = onSubmitAnswer,
            )
            pendingAction.isToolApproval -> ToolApprovalSection(
                actionId = pendingAction.actionId.orEmpty(),
                payload = payload,
                isResolving = isResolving,
                onSubmit = onSubmitToolDecisions,
            )
        }
    }
}

/** The shared card body: one padded, evenly-spaced column per section. */
@Composable
private fun PendingActionColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// ── ask_user_question ─────────────────────────────────────────────────────

@Composable
private fun AskUserQuestionSection(
    actionId: String,
    question: AskUserQuestionRequest,
    isResolving: Boolean,
    onSubmitAnswer: (String) -> Unit,
) {
    // Saveable, not plain remember: this card is a LazyColumn item, so scrolling it out of
    // composition drops un-saved state — and the user has to scroll to reach it in the first
    // place. Rotation and folding a foldable do the same. Losing a half-written answer there is
    // silent: the submit button just goes back to disabled.
    var selected by rememberSaveable(actionId, stateSaver = StringSetSaver) {
        mutableStateOf(emptySet())
    }
    var freeText by rememberSaveable(actionId) { mutableStateOf("") }
    val answer = composeAnswer(question.options, selected, freeText)

    PendingActionColumn {
        PendingActionHeader(
            title = stringResource(Res.string.ask_user_question_title),
            subtitle = question.question.takeIf { it.isNotBlank() },
            icon = { Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(20.dp)) },
        )

        question.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (question.options.isNotEmpty()) {
            if (question.multiSelect) {
                Text(
                    text = stringResource(Res.string.ask_user_question_multi_select_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                question.options.forEach { option ->
                    val isSelected = option.value in selected
                    FilterChip(
                        selected = isSelected,
                        enabled = !isResolving,
                        onClick = {
                            selected = when {
                                !question.multiSelect -> if (isSelected) emptySet() else setOf(option.value)
                                isSelected -> selected - option.value
                                else -> selected + option.value
                            }
                        },
                        label = { Text(option.label.ifBlank { option.value }) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = freeText,
            onValueChange = { freeText = it },
            enabled = !isResolving,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    stringResource(
                        if (question.options.isEmpty()) {
                            Res.string.ask_user_question_answer_hint
                        } else {
                            Res.string.ask_user_question_other_hint
                        },
                    ),
                )
            },
            minLines = 2,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isResolving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            TextButton(
                // Skipping still RESUMES the run — a purely client-side dismiss would leave it paused
                // until expiry, i.e. a hung turn. The model is told the user declined instead.
                onClick = { onSubmitAnswer(ASK_USER_DECLINED_ANSWER) },
                enabled = !isResolving,
            ) {
                Text(stringResource(Res.string.ask_user_question_skip))
            }
            Button(
                onClick = { onSubmitAnswer(answer) },
                enabled = !isResolving && answer.isNotBlank(),
            ) {
                Text(stringResource(Res.string.ask_user_question_send))
            }
        }
    }
}

/**
 * A clarification the agent asked as several related questions in one call.
 *
 * The submit is all-or-nothing because the server's is: `resolveAskUserQuestionResume` requires an
 * answers map covering EVERY id in the payload and rejects both a partial map and an unknown id,
 * so there is no "answer the ones you know" path to offer. Send therefore stays disabled until
 * every question has something in it, and Skip resolves the whole batch by sending the declined
 * sentinel for each id — a purely local dismiss would leave the run paused until it expires.
 *
 * Answers are keyed by the payload's own question ids, never by field order.
 */
@Composable
private fun AskUserQuestionBatchSection(
    actionId: String,
    questions: List<AskUserQuestionItem>,
    isResolving: Boolean,
    onSubmitAnswers: (Map<String, String>) -> Unit,
) {
    // Not rememberSaveable: the per-question editors below each save their own state keyed by
    // question id, and this map is derived from them on every recomposition. Saving it too would
    // give the same value two owners that restore independently.
    val answers = remember(actionId) { mutableStateMapOf<String, String>() }

    PendingActionColumn {
        PendingActionHeader(
            title = stringResource(Res.string.ask_user_question_title),
            subtitle = stringResource(Res.string.ask_user_question_batch_subtitle, questions.size),
            icon = { Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(20.dp)) },
        )

        questions.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider()
            AskUserQuestionBatchItem(
                actionId = actionId,
                item = item,
                isResolving = isResolving,
                onAnswerChange = { answer -> answers[item.id] = answer },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isResolving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            TextButton(
                onClick = {
                    onSubmitAnswers(questions.associate { it.id to ASK_USER_DECLINED_ANSWER })
                },
                enabled = !isResolving,
            ) {
                Text(stringResource(Res.string.ask_user_question_skip))
            }
            Button(
                onClick = {
                    onSubmitAnswers(
                        questions.associate { it.id to answers[it.id].orEmpty().trim() },
                    )
                },
                enabled = !isResolving && questions.all { answers[it.id]?.isNotBlank() == true },
            ) {
                Text(stringResource(Res.string.ask_user_question_send))
            }
        }
    }
}

/**
 * One question of a batch: its own chips and free-text box, reporting the composed answer up.
 *
 * State is saveable and keyed by `actionId + id` for the same reason the single-question card's
 * is — the card is a LazyColumn item, so scrolling it away drops anything not saved, and the only
 * symptom of losing a half-written answer is Send quietly going back to disabled.
 */
@Composable
private fun AskUserQuestionBatchItem(
    actionId: String,
    item: AskUserQuestionItem,
    isResolving: Boolean,
    onAnswerChange: (String) -> Unit,
) {
    val stateKey = "$actionId/${item.id}"
    var selected by rememberSaveable(stateKey, stateSaver = StringSetSaver) {
        mutableStateOf(emptySet())
    }
    var freeText by rememberSaveable(stateKey) { mutableStateOf("") }
    val answer = composeAnswer(item.options, selected, freeText)

    // Reported through an effect rather than during composition: writing a parent's snapshot map
    // from a child's composition body is a write-during-read of the same state the parent reads
    // to decide whether Send is enabled.
    //
    // Captured through rememberUpdatedState so the effect restarts only when the ANSWER changes.
    // Referencing the lambda directly would make a new lambda instance re-run the effect, and
    // re-running it would rewrite the same value into the parent's map on every recomposition.
    val currentOnAnswerChange by rememberUpdatedState(onAnswerChange)
    LaunchedEffect(stateKey, answer) { currentOnAnswerChange(answer) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.header?.takeIf { it.isNotBlank() }?.let { header ->
            Text(
                text = header,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = item.question,
            style = MaterialTheme.typography.bodyMedium,
        )
        item.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (item.options.isNotEmpty()) {
            if (item.multiSelect) {
                Text(
                    text = stringResource(Res.string.ask_user_question_multi_select_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.options.forEach { option ->
                    val isSelected = option.value in selected
                    FilterChip(
                        selected = isSelected,
                        enabled = !isResolving,
                        onClick = {
                            selected = when {
                                !item.multiSelect -> if (isSelected) emptySet() else setOf(option.value)
                                isSelected -> selected - option.value
                                else -> selected + option.value
                            }
                        },
                        label = { Text(option.label.ifBlank { option.value }) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = freeText,
            onValueChange = { freeText = it },
            enabled = !isResolving,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    stringResource(
                        if (item.options.isEmpty()) {
                            Res.string.ask_user_question_answer_hint
                        } else {
                            Res.string.ask_user_question_other_hint
                        },
                    ),
                )
            },
            minLines = 2,
        )
    }
}

/**
 * Folds the chip selection and the free-text box into the single string the resume route takes.
 * Multi-select joins the selected option VALUES with ", " (upstream's rule); free text is appended
 * so a user can qualify a chip rather than having to choose between the two inputs.
 */
private fun composeAnswer(
    options: List<AskUserQuestionOption>,
    selected: Set<String>,
    freeText: String,
): String {
    val chosen = options.map { it.value }.filter { it in selected }
    val typed = freeText.trim()
    return (chosen + typed.takeIf { it.isNotEmpty() }.orEmpty().let { if (it.isEmpty()) emptyList() else listOf(it) })
        .joinToString(", ")
}

// ── tool_approval ─────────────────────────────────────────────────────────

/** One call's in-progress decision. */
private data class ToolDecisionDraft(
    val decision: String? = null,
    val editedArguments: String = "",
    val responseText: String = "",
)

/** Flattens the answer's option set for [rememberSaveable]. */
private val StringSetSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * Flattens the per-call decision drafts for [rememberSaveable] as a flat 4-per-entry list —
 * `SnapshotStateMap` and [ToolDecisionDraft] are not themselves saveable types.
 */
private val ToolDecisionDraftMapSaver = listSaver<SnapshotStateMap<String, ToolDecisionDraft>, String>(
    save = { map ->
        map.entries.flatMap { (callId, draft) ->
            listOf(callId, draft.decision ?: "", draft.editedArguments, draft.responseText)
        }
    },
    restore = { flat ->
        mutableStateMapOf<String, ToolDecisionDraft>().apply {
            flat.chunked(FIELDS_PER_DRAFT)
                .filter { it.size == FIELDS_PER_DRAFT }
                .forEach { fields ->
                    // Positions mirror `save` above: callId, decision, editedArguments, responseText.
                    put(
                        fields[0],
                        ToolDecisionDraft(
                            decision = fields[1].takeIf { it.isNotEmpty() },
                            editedArguments = fields[2],
                            responseText = fields[3],
                        ),
                    )
                }
        }
    },
)

private const val FIELDS_PER_DRAFT = 4

@Composable
private fun ToolApprovalSection(
    actionId: String,
    payload: PendingActionPayload,
    isResolving: Boolean,
    onSubmit: (List<ToolApprovalResolution>) -> Unit,
) {
    // Saveable for the same reason as the answer editor above — an eight-call approval batch is
    // eight individual decisions to redo.
    val drafts = rememberSaveable(actionId, saver = ToolDecisionDraftMapSaver) {
        mutableStateMapOf<String, ToolDecisionDraft>()
    }
    // Join by tool_call_id, never by position: one batch can hold the same tool twice (a model
    // fanning out parallel calls), and by-position would then apply the wrong policy.
    val allowedByCallId = remember(payload) {
        payload.reviewConfigs.associate { it.toolCallId to it.allowedDecisions }
    }

    PendingActionColumn {
        PendingActionHeader(
            title = stringResource(Res.string.tool_approval_title),
            subtitle = stringResource(Res.string.tool_approval_subtitle),
            icon = { Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(20.dp)) },
        )

        payload.actionRequests.forEachIndexed { index, request ->
            if (index > 0) HorizontalDivider()
            ToolApprovalRow(
                request = request,
                allowedDecisions = allowedByCallId[request.toolCallId] ?: DEFAULT_ALLOWED_DECISIONS,
                draft = drafts[request.toolCallId] ?: ToolDecisionDraft(),
                isResolving = isResolving,
                onDraftChange = { drafts[request.toolCallId] = it },
            )
        }

        val resolutions = payload.actionRequests.mapNotNull { request ->
            drafts[request.toolCallId]?.toResolution(request.toolCallId)
        }
        // The server 400s a partial batch and 400s an edit/respond with no payload, so the submit
        // button waits until every call is both decided AND complete.
        val canSubmit = resolutions.size == payload.actionRequests.size && payload.actionRequests.isNotEmpty()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isResolving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            // Bulk shortcuts only appear when EVERY call actually permits that decision — a policy
            // can restrict one call in the batch to reject/respond, and offering "Approve all" there
            // would build a batch the server rejects with a 403.
            BulkDecisionButton(
                labelRes = Res.string.tool_approval_reject_all,
                decision = ToolApprovalDecisions.REJECT,
                payload = payload,
                allowedByCallId = allowedByCallId,
                isResolving = isResolving,
                drafts = drafts,
            )
            BulkDecisionButton(
                labelRes = Res.string.tool_approval_approve_all,
                decision = ToolApprovalDecisions.APPROVE,
                payload = payload,
                allowedByCallId = allowedByCallId,
                isResolving = isResolving,
                drafts = drafts,
            )
            Button(
                onClick = { onSubmit(resolutions) },
                enabled = !isResolving && canSubmit,
            ) {
                Text(stringResource(Res.string.tool_approval_submit))
            }
        }
    }
}

@Composable
private fun BulkDecisionButton(
    labelRes: StringResource,
    decision: String,
    payload: PendingActionPayload,
    allowedByCallId: Map<String, List<String>>,
    isResolving: Boolean,
    drafts: SnapshotStateMap<String, ToolDecisionDraft>,
) {
    val applicable = payload.actionRequests.isNotEmpty() && payload.actionRequests.all { request ->
        decision in (allowedByCallId[request.toolCallId] ?: DEFAULT_ALLOWED_DECISIONS)
    }
    if (!applicable) return
    TextButton(
        onClick = {
            payload.actionRequests.forEach { request ->
                drafts[request.toolCallId] = ToolDecisionDraft(decision = decision)
            }
        },
        enabled = !isResolving,
    ) {
        Text(stringResource(labelRes))
    }
}

@Composable
private fun ToolApprovalRow(
    request: ToolApprovalRequest,
    allowedDecisions: List<String>,
    draft: ToolDecisionDraft,
    isResolving: Boolean,
    onDraftChange: (ToolDecisionDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = request.name, style = MaterialTheme.typography.titleSmall)
        request.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val arguments = request.arguments.asDisplayText()
        if (arguments.isNotBlank()) {
            Text(
                text = stringResource(Res.string.tool_approval_arguments),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = arguments,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                // Tool args are frequently one long JSON line; scroll rather than wrap it into a
                // wall that pushes the decision controls off screen.
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            allowedDecisions.forEach { decision ->
                val label = decision.decisionLabel() ?: return@forEach
                FilterChip(
                    selected = draft.decision == decision,
                    enabled = !isResolving,
                    onClick = { onDraftChange(draft.copy(decision = decision)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        AnimatedVisibility(visible = draft.decision == ToolApprovalDecisions.EDIT) {
            OutlinedTextField(
                value = draft.editedArguments,
                onValueChange = { onDraftChange(draft.copy(editedArguments = it)) },
                enabled = !isResolving,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.tool_approval_edited_arguments)) },
                isError = draft.editedArguments.isNotBlank() && draft.editedArguments.parseArgumentsOrNull() == null,
                supportingText = {
                    if (draft.editedArguments.isNotBlank() && draft.editedArguments.parseArgumentsOrNull() == null) {
                        Text(stringResource(Res.string.tool_approval_invalid_json))
                    }
                },
                minLines = 2,
            )
        }

        AnimatedVisibility(visible = draft.decision == ToolApprovalDecisions.RESPOND) {
            OutlinedTextField(
                value = draft.responseText,
                onValueChange = { onDraftChange(draft.copy(responseText = it)) },
                enabled = !isResolving,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.tool_approval_response_text)) },
                minLines = 2,
            )
        }
    }
}

/**
 * Converts a draft to the wire resolution, or null while it is still incomplete — the two
 * payload-bearing decisions carry their payload or nothing (`edit` needs valid JSON arguments,
 * `respond` needs text). Returning null is what keeps the submit button disabled rather than
 * letting the server 400 the batch.
 */
private fun ToolDecisionDraft.toResolution(toolCallId: String): ToolApprovalResolution? {
    val decision = this.decision ?: return null
    return when (decision) {
        ToolApprovalDecisions.EDIT -> {
            val parsed = editedArguments.parseArgumentsOrNull() ?: return null
            ToolApprovalResolution(toolCallId = toolCallId, decision = decision, editedArguments = parsed)
        }
        ToolApprovalDecisions.RESPOND -> {
            val text = responseText.trim().ifEmpty { return null }
            ToolApprovalResolution(toolCallId = toolCallId, decision = decision, responseText = text)
        }
        else -> ToolApprovalResolution(toolCallId = toolCallId, decision = decision)
    }
}

/** Parses edited arguments, requiring a JSON OBJECT — the SDK's `updatedInput` is a map. */
private fun String.parseArgumentsOrNull(): JsonObject? = try {
    Json.parseToJsonElement(this) as? JsonObject
} catch (_: Exception) {
    null
}

/** Renders `string | object` tool arguments without assuming either shape. */
private fun JsonElement?.asDisplayText(): String = when (this) {
    null -> ""
    is JsonPrimitive -> if (isString) content else toString()
    else -> toString()
}

private fun String.decisionLabel() = when (this) {
    ToolApprovalDecisions.APPROVE -> Res.string.tool_approval_approve
    ToolApprovalDecisions.REJECT -> Res.string.tool_approval_reject
    ToolApprovalDecisions.EDIT -> Res.string.tool_approval_edit
    ToolApprovalDecisions.RESPOND -> Res.string.tool_approval_respond
    // A decision kind this build doesn't render is dropped rather than shown unlabeled; the
    // remaining ones still resolve the call.
    else -> null
}

/**
 * Fallback policy for a call the batch's `review_configs` doesn't mention. Approve/reject only:
 * those are the two decisions every policy permits, so a missing config can't produce a chip the
 * server would 403.
 */
private val DEFAULT_ALLOWED_DECISIONS =
    listOf(ToolApprovalDecisions.APPROVE, ToolApprovalDecisions.REJECT)

@Composable
private fun PendingActionHeader(
    title: String,
    subtitle: String?,
    icon: @Composable () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
