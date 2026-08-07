package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.DuringRunAction
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.TokenUsage
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_add_to_queue
import com.garfiec.librechat.feature.chat.resources.cd_answer_question
import com.garfiec.librechat.feature.chat.resources.cd_cancel_edit
import com.garfiec.librechat.feature.chat.resources.cd_cancel_pending_send
import com.garfiec.librechat.feature.chat.resources.cd_send_message
import com.garfiec.librechat.feature.chat.resources.cd_start_voice_recording
import com.garfiec.librechat.feature.chat.resources.cd_steer_message
import com.garfiec.librechat.feature.chat.resources.cd_stop_generation
import com.garfiec.librechat.feature.chat.resources.cd_update_queued_message
import com.garfiec.librechat.feature.chat.resources.editing_queued_message
import com.garfiec.librechat.feature.chat.resources.hint_message
import com.garfiec.librechat.feature.chat.resources.hint_message_model
import com.garfiec.librechat.feature.chat.resources.recording
import com.garfiec.librechat.feature.chat.viewmodel.ChatInputGates
import com.garfiec.librechat.feature.chat.viewmodel.DuringRunSendTarget
import com.garfiec.librechat.feature.chat.viewmodel.PendingSteerChip
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import org.jetbrains.compose.resources.stringResource

@Immutable
data class ChatInputState(
    val inputText: String,
    val isStreaming: Boolean,
    val isRecording: Boolean,
    val isTranscribing: Boolean,
    val enabledTools: Set<String>,
    /** Tool keys (already mapped + gated) to surface as inline quick-toggle chips on the
     *  input bar (v0.8.7 `defaultPinnedTools`). Selected state reads from [enabledTools]. */
    val pinnedToolKeys: List<String> = emptyList(),
    val mcpServers: List<McpServerDisplayData>,
    val selectedMcpServerNames: Set<String>,
    val selectedModelDisplay: String?,
    val isCodeInterpreterAvailable: Boolean,
    val attachedFiles: List<AttachedFile>,
    /** Ephemeral-tools gate drives local chip display; remaining gates are threaded to the sheet. */
    val gates: ChatInputGates = ChatInputGates(),
    /** Whether queueing a follow-up mid-stream is allowed (existing conversation only). When
     *  false, the send button stays plain Stop while streaming. */
    val canQueue: Boolean = false,
    /** Whether a mid-stream send could instead be steered into the running reply (v0.8.8).
     *  False leaves the composer with queueing alone and hides the during-run picker. */
    val canSteer: Boolean = false,
    /** Which route the during-run picker marks as the standing default — already degraded to
     *  [DuringRunAction.QUEUE] by the ViewModel when steering is unavailable. This is the
     *  *preference*; it is NOT what the send button does. See [duringRunSendTarget]. */
    val duringRunAction: DuringRunAction = DuringRunAction.QUEUE,
    /** What a mid-stream tap on the send button actually does, resolved by the ViewModel. The
     *  two differ whenever a live `ask_user_question` pause overrides the preference, and the
     *  button must name THIS — reading the preference instead is what once labelled an answer
     *  as "add to queue". */
    val duringRunSendTarget: DuringRunSendTarget = DuringRunSendTarget.QUEUE,
    /** Steers accepted by the running turn but not yet injected; rendered above the queue. */
    val pendingSteers: List<PendingSteerChip> = emptyList(),
    /** True while the composer is editing a queued item (queued-edit mode): the send button
     *  becomes "Update" and an editing banner shows above the input. */
    val isEditingQueued: Boolean = false,
    /** True while a tapped send is parked waiting for its attachment(s) to finish uploading: the
     *  send button becomes a spinner the user can tap to cancel the deferred send. */
    val isAwaitingUploadSend: Boolean = false,
    /** True while just-picked files are still being resolved or are staged awaiting a routing
     *  choice ([ChatUiState.arePicksUnsettled]). Every send path refuses in that window, so the
     *  send/update control is disabled rather than left looking live over a tap that does nothing. */
    val arePicksUnsettled: Boolean = false,
    /** Latest context-window usage snapshot; drives the context bar above the composer. */
    val contextUsage: ContextUsage? = null,
    /** Latest per-call token usage, for the breakdown sheet's Input/Output rows. */
    val tokenUsage: TokenUsage? = null,
    /** Server/version gate for the context gauge (`interface.contextUsage` AND backend ≥ 0.8.7). */
    val contextUsageEnabled: Boolean = false,
    /** User preference (Settings → Chat) for where the context gauge is surfaced. The composer
     *  only renders it when this is [ContextBarPlacement.ABOVE_INPUT]. */
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
    /** Prompt groups available to the `/` picker. Filtering happens here, off [inputText]. */
    val promptSuggestions: List<PromptMentionDisplayData> = emptyList(),
)

/**
 * Shared container for the chat input area. Renders the gradient background,
 * attachment chips row, and a slot-based input row with a shared send/stop button.
 *
 * Platform wrappers fill in [leadingButtons] (e.g. "+" button, paste button)
 * and [textFieldContent] (platform-specific text field with mic placement).
 */
@Composable
fun CommonChatInputCore(
    state: ChatInputState,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveFile: (AttachedFile) -> Unit,
    modifier: Modifier = Modifier,
    /** Toggle a pinned tool from its inline chip (v0.8.7 `defaultPinnedTools`). */
    onToggleTool: (String) -> Unit = {},
    /** Queue a follow-up while streaming. Null (or [ChatInputState.canQueue] false) keeps the
     *  button as plain Stop mid-stream (e.g. on a brand-new conversation). Also the during-run
     *  picker's explicit "add to queue" — never the default-action route. */
    onQueue: (() -> Unit)? = null,
    /** The send button's mid-stream action. Routes to steer or queue per the user's preference
     *  and what the run can take; the composer deliberately does not make that call itself. */
    onDuringRunSend: () -> Unit = {},
    /** The during-run picker's explicit "steer this one" (v0.8.8). */
    onSteer: () -> Unit = {},
    /** Withdraw a steer the run has not injected yet. */
    onCancelSteer: (steerId: String) -> Unit = {},
    /** Persist a new default for what a mid-stream send does. */
    onSetDuringRunAction: (DuringRunAction) -> Unit = {},
    /** Number of queued messages held by a Stop/error pause. >0 shows the "Send queued" banner
     *  above the input; 0 hides it (queue empty or draining normally). */
    queuedPausedCount: Int = 0,
    onSendQueuedMessages: () -> Unit = {},
    /** Commit / cancel the in-progress queued edit (see [ChatInputState.isEditingQueued]). */
    onCommitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    /** Cancel a send parked behind an in-flight upload (see [ChatInputState.isAwaitingUploadSend]). */
    onCancelPendingSend: () -> Unit = {},
    /** Queued follow-ups (ghost rows), pinned just above the composer. Hosted here — rather than
     *  in the scrolling message list — so the list's auto-scroll-to-bottom can't make the ghosts
     *  bounce as the reply streams. Empty list renders nothing. */
    queuedMessages: List<QueuedMessage> = emptyList(),
    onEditQueuedMessage: (localId: String) -> Unit = {},
    onCancelQueuedMessage: (localId: String) -> Unit = {},
    onReorderQueuedMessages: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    fontSizeMultiplier: Float = 1f,
    /** Selection from the `/` prompt picker. */
    onSelectPrompt: (PromptMentionDisplayData) -> Unit = {},
    leadingButtons: @Composable RowScope.() -> Unit = {},
    trailingSpacer: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable BoxScope.() -> Unit = {},
    textFieldContent: @Composable RowScope.() -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        surfaceColor.copy(alpha = 0.7f),
                        surfaceColor.copy(alpha = 0.95f),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Ghost queue, pinned directly above the composer. No scroll wrapper: the input bar is
            // bottom-anchored so the queue grows upward (the text field stays put), and a nested
            // vertical scroll here would fight the long-press drag-reorder. Self-hides when empty.
            // Steers first, then the queue: the order they will reach the model. A steer lands in
            // the reply being written right now; a queued message becomes a later turn.
            PendingSteerChipsSection(
                pendingSteers = state.pendingSteers,
                onCancel = onCancelSteer,
                fontSizeMultiplier = fontSizeMultiplier,
            )

            QueuedMessagesSection(
                queuedMessages = queuedMessages,
                onEdit = onEditQueuedMessage,
                onCancel = onCancelQueuedMessage,
                onReorder = onReorderQueuedMessages,
                fontSizeMultiplier = fontSizeMultiplier,
            )

            // Edit-mode banner takes priority over the paused-queue banner.
            if (state.isEditingQueued) {
                EditingQueuedBanner(
                    onCancel = onCancelEdit,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            } else if (queuedPausedCount > 0 && !state.isStreaming) {
                // Gated on !isStreaming: resume() lifts the pause and POPS the head, but
                // doSendWithSpec early-returns while a run is live, so the message would be
                // removed from the queue and never sent. A HITL pause is the long-lived
                // isStreaming window where the composer stays fully interactive.
                SendQueuedBanner(
                    count = queuedPausedCount,
                    onClick = onSendQueuedMessages,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            AttachmentChipsRow(
                attachedFiles = state.attachedFiles,
                enabledTools = state.enabledTools,
                onRemoveFile = onRemoveFile,
                mcpServers = state.mcpServers,
                selectedMcpServerNames = state.selectedMcpServerNames,
                showEphemeralTools = state.gates.showEphemeralTools,
            )

            // Server-pinned tools (v0.8.7 defaultPinnedTools) as inline quick-toggle chips.
            if (state.pinnedToolKeys.isNotEmpty()) {
                PinnedToolsRow(
                    pinnedToolKeys = state.pinnedToolKeys,
                    enabledTools = state.enabledTools,
                    onToggleTool = onToggleTool,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Context-usage bar, between the chips and the composer row. Gated on the placement
            // preference (ABOVE_INPUT here), the server/version support flag, and a snapshot with
            // real usage. Other placements render in the "+" sheet / overflow menu instead.
            val contextUsage = state.contextUsage
            if (state.contextBarPlacement == ContextBarPlacement.ABOVE_INPUT &&
                state.contextUsageEnabled &&
                contextUsage != null &&
                contextUsage.usedTokens > 0
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContextUsageGauge(usage = contextUsage, tokenUsage = state.tokenUsage)
                }
            }

            // Prompt picker, above the input and below the chips/gauge.
            val slashQuery = remember(state.inputText) { parseSlashQuery(state.inputText) }
            val promptSuggestions = remember(slashQuery, state.promptSuggestions) {
                slashQuery?.let { filterMatchingSlashCommands(it, state.promptSuggestions) }.orEmpty()
            }
            PromptSuggestionList(
                suggestions = promptSuggestions,
                onSelect = onSelectPrompt,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingButtons()
                textFieldContent()
                trailingSpacer()
                val hasComposerContent = composerCanSend(
                    inputText = state.inputText,
                    hasAttachments = state.attachedFiles.isNotEmpty(),
                    arePicksUnsettled = state.arePicksUnsettled,
                )
                // Mid-stream + typed content + queueing allowed → morph Stop into a during-run send.
                val duringRunSend = state.canQueue && hasComposerContent && onQueue != null &&
                    !state.isEditingQueued
                // The picker only earns its space when both routes are open; with steering
                // unavailable the send button alone says everything there is to say. `canSteer`
                // is false while a run is paused for review, which is also what keeps the picker
                // from offering a steer/queue choice over a send that will answer the pause.
                if (duringRunSend && state.canSteer) {
                    DuringRunSendMenu(
                        defaultAction = state.duringRunAction,
                        onSteerOnce = onSteer,
                        onQueueOnce = onQueue ?: {},
                        onSetDefault = onSetDuringRunAction,
                    )
                }
                SendStopButton(
                    isStreaming = state.isStreaming,
                    canSend = hasComposerContent,
                    canQueue = duringRunSend,
                    duringRunTarget = state.duringRunSendTarget,
                    onSend = onSend,
                    onStop = onStop,
                    onQueue = onDuringRunSend,
                    // In queued-edit mode the button commits the edit instead of send/stop/queue.
                    isEditingQueued = state.isEditingQueued,
                    onUpdate = onCommitEdit,
                    // A send waiting on an in-flight upload: spinner, tap to cancel.
                    isAwaitingUploadSend = state.isAwaitingUploadSend,
                    onCancelPendingSend = onCancelPendingSend,
                )
            }
        }
        bottomContent()
    }
}

/** Visual mode of the trailing composer button. */
internal enum class SendButtonMode { SEND, STOP, QUEUE, STEER, ANSWER, UPDATE, AWAITING }

/**
 * Whether the composer holds something the trailing control can act on. Drives both that control's
 * enabled state and whether a mid-stream tap morphs Stop into a during-run send.
 *
 * Files that have been picked but not settled deliberately do NOT count, even though the user can
 * see them coming: every send path refuses while a pick is in flight or staged for the routing
 * sheet, so counting them would leave Send, Steer, Queue and Update looking live over a tap that
 * silently does nothing for as long as the provider takes to resolve.
 *
 * Pulled out of the composable for the same reason as [sendButtonModeFor]: this module has no
 * Compose test harness, and an affordance that disagrees with the action behind it is exactly the
 * defect no behavioural test catches.
 */
internal fun composerCanSend(
    inputText: String,
    hasAttachments: Boolean,
    arePicksUnsettled: Boolean,
): Boolean = !arePicksUnsettled && (inputText.isNotBlank() || hasAttachments)

/**
 * Which face the composer's trailing button shows. Pulled out of the composable so the mapping
 * from state to affordance is testable without a Compose harness — this module has none, and the
 * one defect this rule has produced was a *label* that disagreed with the action behind it, which
 * no behavioural test could ever catch.
 */
@Suppress("LongParameterList")
internal fun sendButtonModeFor(
    isStreaming: Boolean,
    canQueue: Boolean,
    duringRunTarget: DuringRunSendTarget,
    isEditingQueued: Boolean,
    isAwaitingUploadSend: Boolean,
): SendButtonMode = when {
    isEditingQueued -> SendButtonMode.UPDATE
    // A send parked behind an in-flight upload only takes over the button when NOT streaming —
    // during a stream the Stop control must stay reachable, so a queued-message send parked
    // behind its upload keeps Stop (the parked enqueue still fires once the upload settles).
    isAwaitingUploadSend && !isStreaming -> SendButtonMode.AWAITING
    isAwaitingUploadSend -> SendButtonMode.STOP
    !isStreaming -> SendButtonMode.SEND
    canQueue -> when (duringRunTarget) {
        DuringRunSendTarget.ANSWER_PAUSE -> SendButtonMode.ANSWER
        DuringRunSendTarget.STEER -> SendButtonMode.STEER
        DuringRunSendTarget.QUEUE -> SendButtonMode.QUEUE
    }

    else -> SendButtonMode.STOP
}

/**
 * Animated send / stop / steer / add-to-queue / answer / update button shared between platforms.
 *
 * A send parked behind an in-flight upload ([isAwaitingUploadSend]) shows a cancellable **spinner**
 * — but only when not streaming, so a mid-stream Stop is never hidden behind it. Otherwise, in
 * queued-edit mode ([isEditingQueued]) it is **Update** (commit the edit); while streaming it is
 * **Stop** by default but morphs into whatever [duringRunTarget] names when the composer has
 * content and queueing is allowed ([canQueue]) — the "clear the box to reveal Stop" rule; and when
 * not streaming it is the usual **Send** (enabled on [canSend]).
 *
 * The during-run mode is derived from [duringRunTarget] and nothing else — never from the user's
 * steer/queue *preference*, which a live `ask_user_question` pause overrides: the button would
 * announce "add to queue" over a tap that answers the question. Behaviour right, label lying, and
 * nothing fails — an affordance read from a different source than the action it triggers drifts
 * silently.
 */
@Composable
fun SendStopButton(
    isStreaming: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    canQueue: Boolean = false,
    onQueue: () -> Unit = {},
    /** What the during-run button names. [onQueue] carries whichever action this names — the
     *  caller resolves it, so the button stays one control. */
    duringRunTarget: DuringRunSendTarget = DuringRunSendTarget.QUEUE,
    isEditingQueued: Boolean = false,
    onUpdate: () -> Unit = {},
    isAwaitingUploadSend: Boolean = false,
    onCancelPendingSend: () -> Unit = {},
) {
    val mode = sendButtonModeFor(
        isStreaming = isStreaming,
        canQueue = canQueue,
        duringRunTarget = duringRunTarget,
        isEditingQueued = isEditingQueued,
        isAwaitingUploadSend = isAwaitingUploadSend,
    )
    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
        },
        label = "send_stop_toggle",
        modifier = modifier,
    ) { buttonMode ->
        when (buttonMode) {
            SendButtonMode.UPDATE -> IconButton(
                onClick = onUpdate,
                modifier = Modifier.size(56.dp),
                enabled = canSend,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    contentColor = if (canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(Res.string.cd_update_queued_message),
                )
            }

            SendButtonMode.AWAITING -> IconButton(
                onClick = onCancelPendingSend,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                // Spinner (upload still in flight) with a small ✕ so it reads as "tap to cancel".
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.cd_cancel_pending_send),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            SendButtonMode.STOP -> IconButton(
                onClick = onStop,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(Res.string.cd_stop_generation),
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                        ),
                )
            }

            SendButtonMode.QUEUE -> IconButton(
                onClick = onQueue,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = stringResource(Res.string.cd_add_to_queue),
                )
            }

            SendButtonMode.STEER -> IconButton(
                onClick = onQueue,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = stringResource(Res.string.cd_steer_message),
                )
            }

            // Deliberately looks like Send, because that is what it does: the run is parked
            // waiting for this text, so the tap delivers it rather than deferring it. Only the
            // click target differs (onQueue → sendDuringRun → resolve the pause).
            SendButtonMode.ANSWER -> IconButton(
                onClick = onQueue,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(Res.string.cd_answer_question),
                )
            }

            SendButtonMode.SEND -> IconButton(
                onClick = onSend,
                modifier = Modifier.size(56.dp),
                enabled = canSend,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    contentColor = if (canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(Res.string.cd_send_message),
                )
            }
        }
    }
}

/**
 * Banner shown above the composer while editing a queued item, with a cancel affordance that
 * discards the edit and restores the previous draft.
 */
@Composable
private fun EditingQueuedBanner(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.editing_queued_message),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.cd_cancel_edit),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Voice mic visual indicator: shows transcription spinner, recording pulse, or idle mic icon.
 * Used inside both platform mic buttons (which differ in placement).
 */
@Composable
fun VoiceMicIndicator(
    isRecording: Boolean,
    isTranscribing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isTranscribing) {
        CircularProgressIndicator(
            modifier = modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    } else if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition(
            label = "recording_pulse",
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        )
        Box(
            modifier = modifier
                .size(20.dp)
                .alpha(pulseAlpha)
                .background(
                    color = MaterialTheme.colorScheme.error,
                    shape = CircleShape,
                ),
        )
    } else {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = stringResource(Res.string.cd_start_voice_recording),
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shared placeholder text for the chat input field.
 */
@Composable
fun ChatInputPlaceholder(
    isRecording: Boolean,
    selectedModelDisplay: String?,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (isRecording) {
            stringResource(Res.string.recording)
        } else if (!selectedModelDisplay.isNullOrBlank()) {
            stringResource(Res.string.hint_message_model, selectedModelDisplay)
        } else {
            stringResource(Res.string.hint_message)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
