package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.FeedbackRating
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.MinimalFeedback
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.ContentGroup
import com.garfiec.librechat.feature.chat.util.IndexedContentPart
import com.garfiec.librechat.feature.chat.util.activityLabelText
import com.garfiec.librechat.feature.chat.util.groupContentParts
import com.garfiec.librechat.feature.chat.util.steerText
import org.jetbrains.compose.resources.stringResource

/** Material 3's disabled content alpha. */
private const val DISABLED_ALPHA = 0.38f

/**
 * False while a reply is streaming, provided by `MessageList`.
 *
 * `ChatViewModel.submitFeedback` refuses mid-stream because its Room write would un-truncate the
 * streaming anchor — but that guard is the last step of a flow that starts several taps earlier.
 * Left live, the thumbs open a sheet, take a reason and up to 1024 characters of comment, and then
 * drop all of it at the sink with nothing shown. The affordance has to know, so the flow is never
 * entered. Disabled rather than hidden: removing the buttons reflows the action row mid-stream.
 */
internal val LocalFeedbackEnabled = compositionLocalOf { true }

/**
 * Shared action buttons row for message bubbles (copy, edit, regenerate, feedback, read aloud, fork).
 * Used by both Android and iOS MessageBubble implementations.
 */
@Composable
internal fun ActionButtons(
    isUser: Boolean,
    onFeedback: ((MinimalFeedback?) -> Unit)?,
    currentFeedback: FeedbackRating?,
    onPickFeedbackTag: (FeedbackRating) -> Unit,
    onCopy: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onReadAloud: (() -> Unit)?,
    isReading: Boolean,
    onFork: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Feedback buttons (AI messages only). Tapping the filled thumb clears; either unfilled
        // thumb opens the tag picker, because the route rejects a rating with no tag.
        if (!isUser && onFeedback != null) {
            val isUp = currentFeedback == FeedbackRating.THUMBS_UP
            val isDown = currentFeedback == FeedbackRating.THUMBS_DOWN
            val feedbackEnabled = LocalFeedbackEnabled.current
            val disabledTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
            // "Thumbs up, disabled" says nothing about why; a disabled control with no reason is
            // the accessibility equivalent of a silent no-op.
            val unavailable = stringResource(Res.string.cd_feedback_unavailable)
            IconButton(
                onClick = { if (isUp) onFeedback(null) else onPickFeedbackTag(FeedbackRating.THUMBS_UP) },
                enabled = feedbackEnabled,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isUp) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = stringResource(Res.string.cd_thumbs_up)
                        .let { if (feedbackEnabled) it else "$it, $unavailable" },
                    modifier = Modifier.size(18.dp),
                    // The tint is set explicitly, so IconButton's own disabled content colour never
                    // applies — without this the button greys out its ripple but not its icon.
                    tint = when {
                        !feedbackEnabled -> disabledTint
                        isUp -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = { if (isDown) onFeedback(null) else onPickFeedbackTag(FeedbackRating.THUMBS_DOWN) },
                enabled = feedbackEnabled,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isDown) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = stringResource(Res.string.cd_thumbs_down)
                        .let { if (feedbackEnabled) it else "$it, $unavailable" },
                    modifier = Modifier.size(18.dp),
                    tint = when {
                        !feedbackEnabled -> disabledTint
                        isDown -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(Res.string.cd_copy_message),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(Res.string.cd_edit_message),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!isUser && onRegenerate != null) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(Res.string.cd_regenerate_response),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (onReadAloud != null) {
            IconButton(onClick = onReadAloud, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isReading) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isReading) {
                        stringResource(Res.string.cd_stop_reading)
                    } else {
                        stringResource(Res.string.cd_read_aloud)
                    },
                    modifier = Modifier.size(18.dp),
                    tint = if (isReading) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (onFork != null) {
            IconButton(onClick = onFork, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = stringResource(Res.string.cd_fork_conversation),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shared message content rendering and action buttons, used by both layout styles on both platforms.
 * Renders: edit mode OR (files + content parts + markdown fallback) + action row with sibling nav.
 */
@Suppress("LongParameterList")
@Composable
internal fun MessageContentAndActions(
    message: Message,
    isUser: Boolean,
    isEditing: Boolean,
    editText: String,
    onEditTextChange: ((String) -> Unit)?,
    onEditSaveAndSubmit: (() -> Unit)?,
    onEditSaveOnly: (() -> Unit)?,
    onEditCancel: (() -> Unit)?,
    baseUrl: String,
    fontSizeMultiplier: Float,
    useKatex: Boolean,
    showImageDescriptions: Boolean,
    searchQuery: String?,
    isSearchMatch: Boolean,
    isCurrentSearchMatch: Boolean,
    searchFocusedOccurrence: Int,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)?,
    showActions: Boolean,
    siblingIndex: Int,
    siblingCount: Int,
    onSiblingNavigation: ((Int) -> Unit)?,
    onEdit: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onFeedback: ((MinimalFeedback?) -> Unit)?,
    onContinue: (() -> Unit)?,
    onReadAloud: (() -> Unit)?,
    onFork: (() -> Unit)?,
    isReading: Boolean,
    currentFeedback: FeedbackRating?,
    // Identity for a mid-run steer, which renders as the user's own turn inside the response.
    userName: String?,
    userAvatarUrl: String?,
) {
    // The picker is hosted here — one site for both layouts on both platforms — rather than in
    // ActionButtons, whose action row auto-hides on a timer and would take an open sheet with it.
    // Saveable, like the tag and comment inside the sheet: a plain `remember` here destroys the
    // sheet on rotation, which takes the child's saved draft with it — the host has to survive
    // for the child's saved state to be reachable. Enum names, not entries: the default saver
    // only handles primitives.
    var pendingFeedbackRating by rememberSaveable(
        message.messageId,
        key = "feedback-rating:${message.messageId}",
    ) { mutableStateOf<String?>(null) }
    val ratingBeingTagged = pendingFeedbackRating?.let { name ->
        FeedbackRating.entries.firstOrNull { it.name == name }
    }
    if (ratingBeingTagged != null && onFeedback != null) {
        FeedbackTagSheet(
            rating = ratingBeingTagged,
            onSubmit = { tag, comment ->
                pendingFeedbackRating = null
                onFeedback(MinimalFeedback(rating = ratingBeingTagged, tag = tag, text = comment))
            },
            onDismiss = { pendingFeedbackRating = null },
        )
    }

    if (isEditing && onEditTextChange != null && onEditSaveAndSubmit != null && onEditSaveOnly != null && onEditCancel != null) {
        // Show the attached files above the edit field so it's clear they're
        // retained on save & submit (the resubmit carries message.files).
        val messageFiles = message.files
        if (!messageFiles.isNullOrEmpty()) {
            MessageFiles(
                files = messageFiles,
                baseUrl = baseUrl,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        InlineEditInput(
            text = editText,
            onTextChange = onEditTextChange,
            onSaveAndSubmit = onEditSaveAndSubmit,
            onSaveOnly = onEditSaveOnly,
            onCancel = onEditCancel,
        )
    } else {
        // Single Column root so this branch emits from one source (and the quote chips,
        // files, content, and office previews stack the same as before).
        //
        // Selection scope is per-message and content-only: every BasicText-backed descendant
        // (prose, code, table cells, think bodies) shares one registrar, while chrome opts out
        // via DisableSelection so "Select all" copies message text, not UI labels. The action
        // row below and the editing branch above stay outside — a text field must never sit
        // inside a SelectionContainer.
        SelectionContainer {
            Column {
                // Verbatim excerpts the user referenced on this turn (v0.8.7), above the user's
                // text. Created on web; mobile displays them (no creation affordance yet).
                // Selectable: a quote is conversation text the user pulled forward, not chrome.
                val quotes = message.quotes
                if (isUser && !quotes.isNullOrEmpty()) {
                    MessageQuotes(
                        quotes = quotes,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                // Render attached files above message text (matches web app behavior)
                val messageFiles = message.files
                if (!messageFiles.isNullOrEmpty()) {
                    DisableSelection {
                        MessageFiles(
                            files = messageFiles,
                            baseUrl = baseUrl,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }

                val contentParts = message.content
                if (!contentParts.isNullOrEmpty()) {
                    // Occurrence indices are message-wide (SearchMatchEnumeration): rebase them
                    // per part so each part resolves the focused occurrence within itself. The focused
                    // message contains the query by definition, so the fast-path bail never triggers —
                    // compute each part's starting offset once (not on every recomposition, which would
                    // re-parse every part's markdown each animateScrollBy frame).
                    val partOffsets = remember(contentParts, searchQuery, isCurrentSearchMatch) {
                        val offsets = IntArray(contentParts.size)
                        if (isCurrentSearchMatch && !searchQuery.isNullOrBlank()) {
                            var acc = 0
                            contentParts.forEachIndexed { i, part ->
                                offsets[i] = acc
                                acc += countPartOccurrences(part, searchQuery)
                            }
                        }
                        offsets
                    }
                    // Segment + group the parts ONCE, not while emitting them: a group's header has to
                    // be written before its members, which a Column cannot express without knowing the
                    // group's extent up front. Pure, so the boundaries are unit-testable.
                    val segments = remember(contentParts) { groupContentParts(contentParts) }

                    // A collapsed group would swallow the match the user just navigated to, so the
                    // one holding it opens. Resolved once per focus rather than per group per frame.
                    val focusedGroupKey = remember(
                        segments, partOffsets, searchQuery, searchFocusedOccurrence, isCurrentSearchMatch,
                    ) {
                        if (!isCurrentSearchMatch || searchQuery.isNullOrBlank() || searchFocusedOccurrence < 0) {
                            null
                        } else {
                            segments.asSequence()
                                .flatMap { it.groups.asSequence() }
                                .filterIsInstance<ContentGroup.Activity>()
                                .firstOrNull { group ->
                                    group.entries.any { entry ->
                                        val start = partOffsets[entry.index]
                                        val count = countPartOccurrences(entry.part, searchQuery)
                                        searchFocusedOccurrence in start until (start + count)
                                    }
                                }?.key
                        }
                    }

                    @Composable
                    fun PartContent(entry: IndexedContentPart) {
                        val part = entry.part
                        val focusedInPart =
                            if (isCurrentSearchMatch) searchFocusedOccurrence - partOffsets[entry.index] else -1
                        when (part.type) {
                            // Handled here rather than in the shared dispatcher because both need the
                            // viewer's identity, which a single-part renderer has no business knowing.
                            ContentType.STEER -> part.steerText()?.let { steered ->
                                SteerContentPart(
                                    text = steered,
                                    userName = userName,
                                    userAvatarUrl = userAvatarUrl,
                                    fontSizeMultiplier = fontSizeMultiplier,
                                    useKatex = useKatex,
                                )
                            }
                            ContentType.ACTIVITY_LABEL -> OrphanActivityLabel(part.activityLabelText())
                            else -> ContentPartRenderer(
                                part = part,
                                baseUrl = baseUrl,
                                fontSizeMultiplier = fontSizeMultiplier,
                                useKatex = useKatex,
                                attachments = message.attachments.orEmpty(),
                                showImageDescriptions = showImageDescriptions,
                                searchQuery = if (isSearchMatch) searchQuery else null,
                                searchFocusedOccurrence = focusedInPart,
                                onFocusedOccurrencePosition = if (isCurrentSearchMatch) onFocusedOccurrencePosition else null,
                                stateKey = "${message.messageId}:${entry.index}",
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }

                    segments.forEach { segment ->
                        key(segment.key) {
                            // A traversal group so the attribution header is read immediately before
                            // its own content instead of segments interleaving. It is not implicit:
                            // a plain Column does not set one.
                            Column(modifier = Modifier.semantics { isTraversalGroup = true }) {
                                segment.author?.let { author ->
                                    SegmentAuthorHeader(
                                        author = author,
                                        messageSender = message.sender,
                                        messageIconUrl = message.iconURL,
                                        messageEndpoint = message.endpoint,
                                    )
                                }
                                segment.groups.forEach { group ->
                                    // Keyed, so per-part state (an expanded thinking block) follows its
                                    // part when grouping shifts it under a wrapper instead of staying
                                    // with whatever now occupies that position.
                                    key(group.key) {
                                        when (group) {
                                            is ContentGroup.Single -> PartContent(group.entry)
                                            is ContentGroup.Activity -> ActivityGroup(
                                                group = group,
                                                stateKey = "${message.messageId}:${group.key}",
                                                autoExpand = group.key == focusedGroupKey,
                                                autoExpandKey = LocalSearchFocusNonce.current,
                                            ) {
                                                group.entries.forEach { entry ->
                                                    key(entry.index) { PartContent(entry) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (message.text.isNotBlank()) {
                    MarkdownContent(
                        text = message.text,
                        fontSizeMultiplier = fontSizeMultiplier,
                        useKatex = useKatex,
                        searchQuery = if (isSearchMatch) searchQuery else null,
                        searchFocusedOccurrence = if (isCurrentSearchMatch) searchFocusedOccurrence else -1,
                        onFocusedOccurrencePosition = if (isCurrentSearchMatch) onFocusedOccurrencePosition else null,
                    )
                }

                // Deferred office-doc preview attachments (v0.8.6) on a persisted message
                // render as their own artifact card. Non-office attachments are unaffected.
                DisableSelection {
                    OfficePreviewAttachments(
                        attachments = message.attachments.orEmpty(),
                        isDarkTheme = isSurfaceDark(),
                    )
                }
            }
        }
    }

    // Action row: sibling nav + message actions (tap-to-reveal)
    val hasActions = siblingCount > 1 || onEdit != null || onRegenerate != null ||
        onCopy != null || onFeedback != null || onContinue != null ||
        onReadAloud != null || onFork != null
    if (hasActions) {
        AnimatedVisibility(
            visible = showActions,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("message_actions"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Sibling navigator (left side)
                    if (siblingCount > 1 && onSiblingNavigation != null) {
                        SiblingNavigator(
                            siblingIndex = siblingIndex,
                            siblingCount = siblingCount,
                            onNavigate = onSiblingNavigation,
                        )
                    } else {
                        Spacer(modifier = Modifier.width(0.dp))
                    }

                    // Action buttons (right side)
                    ActionButtons(
                        isUser = isUser,
                        onFeedback = onFeedback,
                        currentFeedback = currentFeedback,
                        onPickFeedbackTag = { pendingFeedbackRating = it.name },
                        onCopy = onCopy,
                        onEdit = onEdit,
                        onRegenerate = onRegenerate,
                        onReadAloud = onReadAloud,
                        isReading = isReading,
                        onFork = onFork,
                    )
                }
            }
        }
    }
}
