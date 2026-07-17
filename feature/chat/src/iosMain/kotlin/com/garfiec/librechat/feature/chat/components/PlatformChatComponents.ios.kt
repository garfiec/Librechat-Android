package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.core.ui.components.AvatarImage
import com.garfiec.librechat.core.ui.components.endpointIconPainter
import com.garfiec.librechat.core.ui.components.isMonochromeEndpointIcon
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_close
import com.garfiec.librechat.feature.chat.resources.cd_expand_table
import com.garfiec.librechat.feature.chat.resources.dialog_table
import com.garfiec.librechat.feature.chat.resources.sender_assistant
import com.garfiec.librechat.feature.chat.resources.sender_you
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private const val ACTION_AUTO_HIDE_MILLIS = 30_000L

// ─── MessageBubble ───────────────────────────────────────────────────

@Composable
actual fun MessageBubble(
    message: Message,
    modifier: Modifier,
    siblingIndex: Int,
    siblingCount: Int,
    onSiblingNavigation: ((Int) -> Unit)?,
    onEdit: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onFeedback: ((String?) -> Unit)?,
    onContinue: (() -> Unit)?,
    onReadAloud: (() -> Unit)?,
    onFork: (() -> Unit)?,
    baseUrl: String,
    fontSizeMultiplier: Float,
    isReading: Boolean,
    currentFeedback: String?,
    isEditing: Boolean,
    editText: String,
    onEditTextChange: ((String) -> Unit)?,
    onEditSaveAndSubmit: (() -> Unit)?,
    onEditSaveOnly: (() -> Unit)?,
    onEditCancel: (() -> Unit)?,
    userAvatarUrl: String?,
    userName: String?,
    selectedEndpoint: String?,
    showImageDescriptions: Boolean,
    showActionsInitially: Boolean,
    searchQuery: String?,
    isSearchMatch: Boolean,
    isCurrentSearchMatch: Boolean,
    searchFocusedOccurrence: Int,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)?,
    useKatex: Boolean,
    chatLayoutStyle: String,
    showAvatars: Boolean,
    showBubbles: Boolean,
) {
    val resolvedEndpoint = message.endpoint ?: selectedEndpoint
    val tintEndpointIcon = isMonochromeEndpointIcon(resolvedEndpoint)

    if (chatLayoutStyle == ChatLayoutConstants.TWO_SIDED) {
        TwoSidedBubble(
            message, modifier, siblingIndex, siblingCount, onSiblingNavigation, onEdit, onRegenerate, onCopy, onFeedback,
            onContinue, onReadAloud, onFork, baseUrl, fontSizeMultiplier, useKatex, isReading, currentFeedback,
            isEditing, editText, onEditTextChange, onEditSaveAndSubmit, onEditSaveOnly, onEditCancel,
            userAvatarUrl, userName, resolvedEndpoint, tintEndpointIcon, showImageDescriptions, showActionsInitially,
            searchQuery, isSearchMatch, isCurrentSearchMatch, searchFocusedOccurrence, onFocusedOccurrencePosition,
            showAvatars, showBubbles,
        )
    } else {
        ThreadBubble(
            message, modifier, siblingIndex, siblingCount, onSiblingNavigation, onEdit, onRegenerate, onCopy, onFeedback,
            onContinue, onReadAloud, onFork, baseUrl, fontSizeMultiplier, useKatex, isReading, currentFeedback,
            isEditing, editText, onEditTextChange, onEditSaveAndSubmit, onEditSaveOnly, onEditCancel,
            userAvatarUrl, userName, resolvedEndpoint, tintEndpointIcon, showImageDescriptions, showActionsInitially,
            searchQuery, isSearchMatch, isCurrentSearchMatch, searchFocusedOccurrence, onFocusedOccurrencePosition,
            showAvatars, showBubbles,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ThreadBubble(
    message: Message, modifier: Modifier, siblingIndex: Int, siblingCount: Int,
    onSiblingNavigation: ((Int) -> Unit)?, onEdit: (() -> Unit)?, onRegenerate: (() -> Unit)?,
    onCopy: (() -> Unit)?, onFeedback: ((String?) -> Unit)?, onContinue: (() -> Unit)?,
    onReadAloud: (() -> Unit)?, onFork: (() -> Unit)?, baseUrl: String, fontSizeMultiplier: Float,
    useKatex: Boolean, isReading: Boolean, currentFeedback: String?, isEditing: Boolean, editText: String,
    onEditTextChange: ((String) -> Unit)?, onEditSaveAndSubmit: (() -> Unit)?, onEditSaveOnly: (() -> Unit)?,
    onEditCancel: (() -> Unit)?, userAvatarUrl: String?, userName: String?,
    resolvedEndpoint: String?, tintEndpointIcon: Boolean,
    showImageDescriptions: Boolean, showActionsInitially: Boolean, searchQuery: String?,
    isSearchMatch: Boolean, isCurrentSearchMatch: Boolean, searchFocusedOccurrence: Int,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)?, showAvatars: Boolean, showBubbles: Boolean,
) {
    val isUser = message.isCreatedByUser
    var showActions by remember(message.messageId) { mutableStateOf(showActionsInitially) }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    if (showFeedbackDialog && onFeedback != null) {
        FeedbackCommentDialog(
            onSubmit = { showFeedbackDialog = false; onFeedback("thumbsDown") },
            onDismiss = { showFeedbackDialog = false },
        )
    }

    LaunchedEffect(showActions) { if (showActions) { delay(ACTION_AUTO_HIDE_MILLIS); showActions = false } }

    val searchBg = when {
        isCurrentSearchMatch -> SearchHighlightOrange.copy(alpha = 0.18f)
        isSearchMatch -> SearchHighlightYellow.copy(alpha = 0.12f)
        else -> null
    }

    Column(
        modifier = modifier.fillMaxWidth()
            .then(if (searchBg != null) Modifier.background(searchBg, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(interactionSource = null, indication = null) { showActions = !showActions }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showAvatars) {
                AvatarImage(
                    imageUrl = if (isUser) userAvatarUrl else message.iconURL,
                    fallbackText = if (isUser) {
                        userName ?: stringResource(Res.string.sender_you)
                    } else {
                        message.sender ?: stringResource(Res.string.sender_assistant)
                    },
                    fallbackIconPainter = if (!isUser && message.iconURL == null) endpointIconPainter(resolvedEndpoint) else null,
                    showPersonIcon = isUser && userAvatarUrl == null,
                    tintIcon = if (!isUser && message.iconURL == null) tintEndpointIcon else false,
                    size = 28.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = if (isUser) {
                    userName ?: stringResource(Res.string.sender_you)
                } else {
                    message.sender ?: stringResource(Res.string.sender_assistant)
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            message.createdAt?.let { ts -> Spacer(Modifier.width(8.dp)); MessageTimestamp(isoTimestamp = ts) }
        }

        Spacer(Modifier.height(4.dp))

        val contentPad = if (showAvatars) 36.dp else 0.dp
        val bubbleBg = if (showBubbles) {
            if (isUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        } else {
            null
        }

        Column(
            modifier = Modifier.padding(start = contentPad).then(
                if (bubbleBg != null) Modifier.background(bubbleBg, BubbleShape).padding(12.dp) else Modifier,
            ),
        ) {
            MessageContentAndActions(
                message, isUser, isEditing, editText, onEditTextChange, onEditSaveAndSubmit, onEditSaveOnly, onEditCancel,
                baseUrl, fontSizeMultiplier, useKatex, showImageDescriptions, searchQuery, isSearchMatch,
                isCurrentSearchMatch, searchFocusedOccurrence, onFocusedOccurrencePosition, showActions,
                siblingIndex, siblingCount, onSiblingNavigation, onEdit, onRegenerate, onCopy, onFeedback,
                onContinue, onReadAloud, onFork, isReading, currentFeedback, { showFeedbackDialog = true },
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun TwoSidedBubble(
    message: Message, modifier: Modifier, siblingIndex: Int, siblingCount: Int,
    onSiblingNavigation: ((Int) -> Unit)?, onEdit: (() -> Unit)?, onRegenerate: (() -> Unit)?,
    onCopy: (() -> Unit)?, onFeedback: ((String?) -> Unit)?, onContinue: (() -> Unit)?,
    onReadAloud: (() -> Unit)?, onFork: (() -> Unit)?, baseUrl: String, fontSizeMultiplier: Float,
    useKatex: Boolean, isReading: Boolean, currentFeedback: String?, isEditing: Boolean, editText: String,
    onEditTextChange: ((String) -> Unit)?, onEditSaveAndSubmit: (() -> Unit)?, onEditSaveOnly: (() -> Unit)?,
    onEditCancel: (() -> Unit)?, userAvatarUrl: String?, userName: String?,
    resolvedEndpoint: String?, tintEndpointIcon: Boolean,
    showImageDescriptions: Boolean, showActionsInitially: Boolean, searchQuery: String?,
    isSearchMatch: Boolean, isCurrentSearchMatch: Boolean, searchFocusedOccurrence: Int,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)?, showAvatars: Boolean, showBubbles: Boolean,
) {
    val isUser = message.isCreatedByUser
    var showActions by remember(message.messageId) { mutableStateOf(showActionsInitially) }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    if (showFeedbackDialog && onFeedback != null) {
        FeedbackCommentDialog(
            onSubmit = { showFeedbackDialog = false; onFeedback("thumbsDown") },
            onDismiss = { showFeedbackDialog = false },
        )
    }

    LaunchedEffect(showActions) { if (showActions) { delay(ACTION_AUTO_HIDE_MILLIS); showActions = false } }

    val searchBg = when {
        isCurrentSearchMatch -> SearchHighlightOrange.copy(alpha = 0.18f)
        isSearchMatch -> SearchHighlightYellow.copy(alpha = 0.12f)
        else -> null
    }
    val bubbleBg = if (showBubbles) {
        if (isUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    } else {
        null
    }
    val effectiveBg = if (searchBg != null && bubbleBg != null) {
        searchBg.compositeOver(bubbleBg)
    } else {
        searchBg ?: bubbleBg
    }

    Row(
        modifier = modifier.fillMaxWidth()
            .clickable(interactionSource = null, indication = null) { showActions = !showActions }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser && showAvatars) {
            AvatarImage(
                imageUrl = message.iconURL,
                fallbackText = message.sender ?: stringResource(Res.string.sender_assistant),
                fallbackIconPainter = if (message.iconURL == null) endpointIconPainter(resolvedEndpoint) else null,
                tintIcon = if (message.iconURL == null) tintEndpointIcon else false,
                size = 28.dp,
            )
            Spacer(Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier.weight(1f).then(
                if (effectiveBg != null) {
                    Modifier.background(effectiveBg, BubbleShape).padding(12.dp)
                } else {
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                },
            ),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isUser) {
                        userName ?: stringResource(Res.string.sender_you)
                    } else {
                        message.sender ?: stringResource(Res.string.sender_assistant)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (showBubbles) {
                        if (isUser) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                message.createdAt?.let { ts -> Spacer(Modifier.width(6.dp)); MessageTimestamp(isoTimestamp = ts) }
            }
            Spacer(Modifier.height(4.dp))

            MessageContentAndActions(
                message, isUser, isEditing, editText, onEditTextChange, onEditSaveAndSubmit, onEditSaveOnly, onEditCancel,
                baseUrl, fontSizeMultiplier, useKatex, showImageDescriptions, searchQuery, isSearchMatch,
                isCurrentSearchMatch, searchFocusedOccurrence, onFocusedOccurrencePosition, showActions,
                siblingIndex, siblingCount, onSiblingNavigation, onEdit, onRegenerate, onCopy, onFeedback,
                onContinue, onReadAloud, onFork, isReading, currentFeedback, { showFeedbackDialog = true },
            )
        }

        if (isUser && showAvatars) {
            Spacer(Modifier.width(6.dp))
            AvatarImage(
                imageUrl = userAvatarUrl,
                fallbackText = userName ?: stringResource(Res.string.sender_you),
                showPersonIcon = userAvatarUrl == null,
                size = 28.dp,
            )
        }
    }
}

// ─── ContentPartRenderer ─────────────────────────────────────────────

@Composable
actual fun ContentPartRenderer(
    part: MessageContentPart, modifier: Modifier, baseUrl: String, fontSizeMultiplier: Float,
    useKatex: Boolean, attachments: List<Attachment>,
    showImageDescriptions: Boolean, searchQuery: String?, searchFocusedOccurrence: Int,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)?,
) {
    ContentPartDispatcher(
        part = part,
        modifier = modifier,
        baseUrl = baseUrl,
        fontSizeMultiplier = fontSizeMultiplier,
        useKatex = useKatex,
        attachments = attachments,
        showImageDescriptions = showImageDescriptions,
        searchQuery = searchQuery,
        searchFocusedOccurrence = searchFocusedOccurrence,
        onFocusedOccurrencePosition = onFocusedOccurrencePosition,
    )
}

// ─── MarkdownContent ─────────────────────────────────────────────────

@Composable
actual fun MarkdownContent(
    text: String, modifier: Modifier, fontSizeMultiplier: Float, useKatex: Boolean,
    searchQuery: String?, searchFocusedOccurrence: Int,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)?,
    immediate: Boolean,
    streaming: Boolean,
) {
    val segments = rememberMarkdownSegments(text, streaming)

    val colors = markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh, dividerColor = MaterialTheme.colorScheme.outlineVariant,
    )
    val bl = MaterialTheme.typography.bodyLarge
    val bm = MaterialTheme.typography.bodyMedium
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineLarge.scale(fontSizeMultiplier),
        h2 = MaterialTheme.typography.headlineMedium.scale(fontSizeMultiplier),
        h3 = MaterialTheme.typography.headlineSmall.scale(fontSizeMultiplier),
        h4 = MaterialTheme.typography.titleLarge.scale(fontSizeMultiplier),
        h5 = MaterialTheme.typography.titleMedium.scale(fontSizeMultiplier),
        h6 = MaterialTheme.typography.titleSmall.scale(fontSizeMultiplier),
        text = bl.scale(fontSizeMultiplier), paragraph = bl.scale(fontSizeMultiplier),
        quote = bl.copy(fontStyle = FontStyle.Italic).scale(fontSizeMultiplier),
        code = bm.copy(fontFamily = FontFamily.Monospace).scale(fontSizeMultiplier),
        inlineCode = bl.copy(fontFamily = FontFamily.Monospace).scale(fontSizeMultiplier),
        ordered = bl.scale(fontSizeMultiplier), bullet = bl.scale(fontSizeMultiplier), list = bl.scale(fontSizeMultiplier),
    )

    val isSearchActive = !searchQuery.isNullOrBlank()

    Column(modifier = modifier.fillMaxWidth()) {
        // Per-segment occurrence base offsets, advanced via countSegmentOccurrences to stay
        // identical to SearchMatchEnumeration's numbering (mirrors androidMain). Computed once
        // per (segments, query), not every recomposition.
        val segmentOffsets = remember(segments, searchQuery) {
            IntArray(segments.size).also { offsets ->
                if (!searchQuery.isNullOrBlank()) {
                    var acc = 0
                    segments.forEachIndexed { i, segment ->
                        offsets[i] = acc
                        acc += countSegmentOccurrences(segment, searchQuery)
                    }
                }
            }
        }

        segments.forEachIndexed { index, segment ->
            val focusedInSegment = if (isSearchActive) searchFocusedOccurrence - segmentOffsets[index] else -1

            when (segment) {
                is MarkdownSegment.CodeBlock -> {
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    if (segment.language?.lowercase() == "mermaid") {
                        MermaidDiagram(
                            code = segment.code,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        CodeBlock(
                            code = segment.code,
                            language = segment.language,
                            modifier = Modifier.padding(vertical = 4.dp),
                            searchQuery = if (isSearchActive) searchQuery else null,
                            searchFocusedOccurrence = focusedInSegment,
                            onFocusedMatchPosition = onFocusedOccurrencePosition,
                        )
                    }
                    if (index < segments.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                }
                is MarkdownSegment.LatexBlock -> {
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    LatexBlock(
                        latex = segment.latex,
                        modifier = Modifier.padding(vertical = 4.dp),
                        useKatex = useKatex,
                    )
                    if (index < segments.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                }
                is MarkdownSegment.InlineLatexText -> {
                    Column {
                        // Rebase again per Text run within the segment.
                        var inlineOffset = 0
                        segment.segments.forEach { inlineSegment ->
                            when (inlineSegment) {
                                is InlineSegment.Text -> {
                                    if (inlineSegment.text.isNotBlank()) {
                                        if (isSearchActive) {
                                            val runOccurrences = countOccurrences(inlineSegment.text, searchQuery)
                                            val focusedInRun = focusedInSegment - inlineOffset
                                            HighlightedTextSegment(
                                                content = inlineSegment.text,
                                                searchQuery = searchQuery,
                                                focusedOccurrence = focusedInRun,
                                                fontSizeMultiplier = fontSizeMultiplier,
                                                onFocusedMatchPosition = onFocusedOccurrencePosition,
                                            )
                                            inlineOffset += runOccurrences
                                        } else {
                                            key(fontSizeMultiplier) {
                                                CachedMarkdown(
                                                    content = inlineSegment.text,
                                                    colors = colors,
                                                    typography = typography,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    immediate = immediate,
                                                    streaming = streaming,
                                                )
                                            }
                                        }
                                    }
                                }
                                is InlineSegment.Latex -> {
                                    LatexInline(
                                        latex = inlineSegment.latex,
                                        useKatex = useKatex,
                                    )
                                }
                            }
                        }
                    }
                }
                is MarkdownSegment.Table -> {
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    IosMarkdownTableWithFullscreen(
                        headers = segment.headers,
                        alignments = segment.alignments,
                        rows = segment.rows,
                        fontSizeMultiplier = fontSizeMultiplier,
                        modifier = Modifier.padding(vertical = 4.dp),
                        searchQuery = if (isSearchActive) searchQuery else null,
                        searchFocusedOccurrence = focusedInSegment,
                        onFocusedMatchPosition = onFocusedOccurrencePosition,
                    )
                    if (index < segments.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                }
                is MarkdownSegment.TextBlock -> {
                    if (isSearchActive) {
                        HighlightedTextSegment(
                            content = segment.text,
                            searchQuery = searchQuery,
                            focusedOccurrence = focusedInSegment,
                            fontSizeMultiplier = fontSizeMultiplier,
                            onFocusedMatchPosition = onFocusedOccurrencePosition,
                        )
                    } else {
                        key(fontSizeMultiplier, index) {
                            CachedMarkdown(
                                content = segment.text,
                                colors = colors,
                                typography = typography,
                                modifier = Modifier.fillMaxWidth(),
                                immediate = immediate,
                                streaming = streaming,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Table with fullscreen expand ───────────────────────────────────

@Composable
private fun IosMarkdownTableWithFullscreen(
    headers: List<String>,
    alignments: List<TableCellAlignment>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedMatchPosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
) {
    var showFullscreen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IosMarkdownTable(
            headers = headers,
            alignments = alignments,
            rows = rows,
            fontSizeMultiplier = fontSizeMultiplier,
            searchQuery = searchQuery,
            searchFocusedOccurrence = searchFocusedOccurrence,
            onFocusedMatchPosition = onFocusedMatchPosition,
        )

        IconButton(
            onClick = { showFullscreen = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Fullscreen,
                contentDescription = stringResource(Res.string.cd_expand_table),
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showFullscreen) {
        IosFullscreenTableDialog(
            headers = headers,
            alignments = alignments,
            rows = rows,
            fontSizeMultiplier = fontSizeMultiplier,
            onDismiss = { showFullscreen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IosFullscreenTableDialog(
    headers: List<String>,
    alignments: List<TableCellAlignment>,
    rows: List<List<String>>,
    fontSizeMultiplier: Float,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            TopAppBar(
                title = { Text(stringResource(Res.string.dialog_table)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(Res.string.cd_close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                IosMarkdownTable(
                    headers = headers,
                    alignments = alignments,
                    rows = rows,
                    fontSizeMultiplier = fontSizeMultiplier,
                )
            }
        }
    }
}

// ─── Table composable ───────────────────────────────────────────────

@Composable
private fun IosMarkdownTable(
    headers: List<String>,
    alignments: List<TableCellAlignment>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedMatchPosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
) {
    val isSearchActive = !searchQuery.isNullOrBlank()
    val textColor = MaterialTheme.colorScheme.onSurface
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val bodyStyle = MaterialTheme.typography.bodyMedium.scale(fontSizeMultiplier)
    val headerStyle = bodyStyle.copy(fontWeight = FontWeight.Bold)
    val cellPaddingH = 12.dp
    val cellPaddingV = 8.dp

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columnCount = headers.size

    val columnWidths = remember(headers, rows, fontSizeMultiplier) {
        val cellPaddingPx = with(density) { (cellPaddingH * 2).roundToPx() }
        List(columnCount) { col ->
            var maxWidth = textMeasurer.measure(text = headers[col], style = headerStyle).size.width
            for (row in rows) {
                val measured = textMeasurer.measure(text = row.getOrElse(col) { "" }, style = bodyStyle).size.width
                if (measured > maxWidth) maxWidth = measured
            }
            with(density) { (maxWidth + cellPaddingPx).toDp() }
        }
    }

    Box(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clip(MaterialTheme.shapes.small),
    ) {
        Column {
            // Per-cell occurrence base offsets, headers-first then rows row-major — the same order
            // as SearchMatchEnumeration's tableCellTexts. Computed once per (headers, rows, query).
            val (headerOffsets, rowOffsets) = remember(headers, rows, searchQuery) {
                val hOffsets = IntArray(headers.size)
                val rOffsets = rows.map { IntArray(it.size) }
                if (!searchQuery.isNullOrBlank()) {
                    var acc = 0
                    headers.forEachIndexed { i, header ->
                        hOffsets[i] = acc
                        acc += countOccurrences(header, searchQuery)
                    }
                    rows.forEachIndexed { r, row ->
                        row.forEachIndexed { c, cell ->
                            rOffsets[r][c] = acc
                            acc += countOccurrences(cell, searchQuery)
                        }
                    }
                }
                hOffsets to rOffsets
            }
            Row(modifier = Modifier.height(IntrinsicSize.Min).background(headerBackground)) {
                headers.forEachIndexed { colIndex, header ->
                    if (colIndex > 0) VerticalDivider(color = dividerColor, modifier = Modifier.fillMaxHeight())
                    TableCell(
                        header, headerStyle, textColor, alignments.getOrElse(colIndex) { TableCellAlignment.LEFT },
                        columnWidths[colIndex], cellPaddingH, cellPaddingV,
                        searchQuery = searchQuery,
                        searchFocusedOccurrence = if (isSearchActive) searchFocusedOccurrence - headerOffsets[colIndex] else -1,
                        onFocusedMatchPosition = onFocusedMatchPosition,
                    )
                }
            }
            HorizontalDivider(color = dividerColor, thickness = 1.dp)
            rows.forEachIndexed { rowIndex, row ->
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    row.forEachIndexed { colIndex, cell ->
                        if (colIndex > 0) VerticalDivider(color = dividerColor, modifier = Modifier.fillMaxHeight())
                        TableCell(
                            cell, bodyStyle, textColor, alignments.getOrElse(colIndex) { TableCellAlignment.LEFT },
                            columnWidths.getOrElse(colIndex) { columnWidths.last() }, cellPaddingH, cellPaddingV,
                            searchQuery = searchQuery,
                            searchFocusedOccurrence = if (isSearchActive) searchFocusedOccurrence - rowOffsets[rowIndex][colIndex] else -1,
                            onFocusedMatchPosition = onFocusedMatchPosition,
                        )
                    }
                }
                if (rowIndex < rows.lastIndex) HorizontalDivider(color = dividerColor, thickness = Dp.Hairline)
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String, style: TextStyle, color: Color,
    alignment: TableCellAlignment, cellWidth: Dp, paddingH: Dp, paddingV: Dp, modifier: Modifier = Modifier,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedMatchPosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val display = remember(text, searchQuery, searchFocusedOccurrence, isDarkTheme) {
        if (searchQuery.isNullOrBlank()) {
            AnnotatedString(text)
        } else {
            buildHighlightedString(text, searchQuery, searchFocusedOccurrence, isDarkTheme)
        }
    }
    val focusedRange = remember(text, searchQuery, searchFocusedOccurrence) {
        if (searchQuery.isNullOrBlank()) null else findOccurrenceRange(text, searchQuery, searchFocusedOccurrence)
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier = modifier.width(cellWidth).padding(horizontal = paddingH, vertical = paddingV),
        contentAlignment = when (alignment) {
            TableCellAlignment.LEFT -> Alignment.CenterStart
            TableCellAlignment.CENTER -> Alignment.Center
            TableCellAlignment.RIGHT -> Alignment.CenterEnd
        },
    ) {
        Text(
            text = display, style = style, color = color,
            // Only the focused cell needs full layout so its wrapped-line rect reports accurately;
            // every other cell keeps the 10-line clamp even while search is open, so opening search
            // doesn't balloon whole tables. focusedRange is non-null exactly for the focused cell.
            maxLines = if (focusedRange != null) Int.MAX_VALUE else 10,
            overflow = TextOverflow.Ellipsis,
            textAlign = when (alignment) {
                TableCellAlignment.LEFT -> TextAlign.Start
                TableCellAlignment.CENTER -> TextAlign.Center
                TableCellAlignment.RIGHT -> TextAlign.End
            },
            onTextLayout = { layoutResult = it },
            modifier = Modifier.reportFocusedMatchPosition(layoutResult, focusedRange, onFocusedMatchPosition),
        )
    }
}

private fun TextStyle.scale(m: Float): TextStyle {
    if (m == 1.0f) return this
    return copy(
        fontSize = if (fontSize.isSpecified) (fontSize.value * m).sp else fontSize,
        lineHeight = if (lineHeight.isSpecified) (lineHeight.value * m).sp else lineHeight,
    )
}
