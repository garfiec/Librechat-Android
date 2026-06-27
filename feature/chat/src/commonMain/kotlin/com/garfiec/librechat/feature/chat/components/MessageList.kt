package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.FeedbackRating
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.MessageNode
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.garfiec.librechat.feature.chat.viewmodel.SearchMatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageList(
    displayMessages: List<MessageNode>,
    isStreaming: Boolean,
    streamingContent: String,
    onSiblingNavigation: (parentMessageId: String, siblingIndex: Int) -> Unit,
    onEditMessage: (messageId: String) -> Unit,
    onRegenerateMessage: (messageId: String) -> Unit,
    onCopyMessage: (messageId: String) -> Unit,
    modifier: Modifier = Modifier,
    activeToolCalls: List<ActiveToolCall> = emptyList(),
    streamingAttachments: List<Attachment> = emptyList(),
    onFeedback: (messageId: String, rating: String?) -> Unit = { _, _ -> },
    onContinue: (messageId: String) -> Unit = {},
    onReadAloud: (messageId: String) -> Unit = {},
    onFork: (messageId: String) -> Unit = {},
    currentlyReadingMessageId: String? = null,
    editingMessageId: String? = null,
    editingText: String = "",
    onEditTextChange: (String) -> Unit = {},
    onEditSaveAndSubmit: () -> Unit = {},
    onEditSaveOnly: () -> Unit = {},
    onEditCancel: () -> Unit = {},
    baseUrl: String = "",
    fontSizeMultiplier: Float = 1.0f,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    userAvatarUrl: String? = null,
    userName: String? = null,
    selectedEndpoint: String? = null,
    streamingSenderName: String = "Assistant",
    showImageDescriptions: Boolean = true,
    chatLayoutStyle: String = ChatLayoutConstants.THREAD,
    showAvatars: Boolean = true,
    showBubbles: Boolean = false,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchMatchIndices: List<SearchMatch> = emptyList(),
    currentSearchMatchIndex: Int = 0,
    searchScrollToIndex: Int? = null,
    onSearchScrollHandle: () -> Unit = {},
    // Scrollable bottom inset that keeps the latest content clear of the overlaid input bar. The
    // caller measures the actual bar height (which grows as queued ghost rows stack above the
    // composer) so streaming text rests above the ghosts rather than behind them. It's a
    // LazyColumn contentPadding, not a solid spacer: content still scrolls up *behind* the
    // translucent ghosts. Defaults to the single-line bar's reserve.
    bottomContentPadding: Dp = 160.dp,
    // Scrollable top inset mirroring [bottomContentPadding] for the floating top bar: the first
    // message rests below the bar yet still scrolls up *behind* its translucent scrim. The caller
    // measures the bar's actual height (status bar + chips). Defaults to 0 for callers without an
    // overlaid bar (e.g. comparison panes that sit under a separate header).
    topContentPadding: Dp = 0.dp,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // Track whether the user's finger is currently touching the list.
    // This is more reliable than isScrollInProgress which only covers flings.
    var isTouching by remember { mutableStateOf(false) }
    val currentStreamingContent by rememberUpdatedState(streamingContent)
    val nearBottomThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    // Height (px) of the floating top bar occluding the list's top edge. The search fine-tune adds
    // this so a focused match settles below the bar rather than flush against (or under) it. Zero
    // for callers without an overlaid bar (e.g. comparison panes), leaving their behavior unchanged.
    val topContentPaddingPx = with(LocalDensity.current) { topContentPadding.toPx() }
    var lastNavigatedParentKey by remember { mutableStateOf<String?>(null) }

    val streamingToolCallCount = if (isStreaming) activeToolCalls.size else 0
    val totalItemCount = displayMessages.size + streamingToolCallCount + if (isStreaming) 1 else 0

    // Track whether user has deliberately scrolled away from the bottom.
    // Reset when user scrolls back to bottom (via FAB or manual scroll).
    var userScrolledUp by remember { mutableStateOf(false) }

    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.lastOrNull()
            val itemCount = info.totalItemsCount
            if (lastItem == null || itemCount == 0) {
                true
            } else {
                lastItem.index >= itemCount - 2 &&
                    (lastItem.offset + lastItem.size - info.viewportEndOffset) < nearBottomThresholdPx
            }
        }
    }

    // Detect user scroll-away: when user touches/flings away from bottom, set the flag.
    // When they return to the bottom (manually or via FAB), clear it.
    LaunchedEffect(Unit) {
        snapshotFlow { (isTouching || listState.isScrollInProgress) to isNearBottom }
            .collect { (userIsScrolling, nearBottom) ->
                if (userIsScrolling && !nearBottom) {
                    userScrolledUp = true
                } else if (!userIsScrolling && nearBottom) {
                    userScrolledUp = false
                }
            }
    }

    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItemCount > 0 && lastVisibleItem < totalItemCount - 1
        }
    }

    // ── Auto-scroll behavior ──────────────────────────────────────────
    //
    // 1. INITIAL LOAD — Scroll to the most recent message once on the
    //    first non-empty emission. Never fires again for this instance.
    //
    // 2. STREAMING START — Jump to bottom when isStreaming flips true.
    //
    // 3. STREAMING FOLLOW — A snapshotFlow observes streamingContent
    //    length (via rememberUpdatedState) so every token guarantees an
    //    emission. On each emission we check two conditions:
    //      a) User is NOT actively scrolling (isScrollInProgress) so
    //         programmatic scrolls never fight user gestures.
    //      b) Pixel-based "near bottom" — the bottom edge of the last
    //         visible item must be within 200dp of the viewport bottom.
    //         This avoids yanking the user when they're reading the top
    //         of a tall streaming bubble.
    //    Scrolls are launched in an isolated coroutine so that if a
    //    late user touch cancels the scroll via the scroll mutex, the
    //    CancellationException doesn't kill the snapshotFlow collector.
    //
    // 4. POST-STREAMING — When streaming ends, a 300ms delay lets the
    //    Room observer replace the streaming bubble with the real AI
    //    message, then we scroll to show the complete response.
    //
    // Auto-scroll does NOT fire for branch switching, message deletion,
    // or any other display-list change — only streaming and initial load.
    // ─────────────────────────────────────────────────────────────────

    // Track whether we need a fine-tune scroll after the item scroll completes.
    // When the focused occurrence's HighlightedTextSegment reports its layout position,
    // we compare it against the viewport and animateScrollBy the delta if needed.
    var pendingFineTuneScroll by remember { mutableStateOf(false) }
    val currentOnSearchScrollHandled by rememberUpdatedState(onSearchScrollHandle)

    // Scroll to search match when navigating prev/next
    LaunchedEffect(searchScrollToIndex) {
        val index = searchScrollToIndex
        if (index != null && index in displayMessages.indices) {
            pendingFineTuneScroll = true
            listState.animateScrollToItem(index)
            // After scroll completes, the focused segment will report its position
            // via onGloballyPositioned, and the fine-tune scroll will happen there.
            currentOnSearchScrollHandled()
        }
    }

    // Determine the currently focused SearchMatch
    val currentSearchMatch: SearchMatch? = if (searchMatchIndices.isNotEmpty() &&
        currentSearchMatchIndex in searchMatchIndices.indices
    ) {
        searchMatchIndices[currentSearchMatchIndex]
    } else {
        null
    }

    // Build a set of matching message indices for O(1) lookup
    val searchMatchMessageIndexSet = remember(searchMatchIndices) {
        searchMatchIndices.map { it.messageIndex }.toSet()
    }

    // Saveable so it survives the list leaving/re-entering composition (e.g. the
    // full-screen artifact route disposes the chat entry); otherwise the initial
    // scroll re-fires on return and overrides the restored scroll position.
    var hasScrolledToBottom by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(displayMessages.size) {
        if (!hasScrolledToBottom && displayMessages.isNotEmpty()) {
            listState.scrollToItem(totalItemCount - 1, scrollOffset = Int.MAX_VALUE)
            hasScrolledToBottom = true
        }
    }

    var wasStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            wasStreaming = true
            // Reset scroll-away flag — user just sent a message, start at bottom
            userScrolledUp = false
            // Jump to bottom immediately (user just sent a message)
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                listState.scrollToItem(total - 1, scrollOffset = Int.MAX_VALUE)
            }
            // Rule 3: streaming follow (see block comment above)
            snapshotFlow {
                currentStreamingContent.length
            }.collect {
                // Skip auto-scroll if user is actively touching the screen,
                // if a fling is in progress, or if user has scrolled away.
                if (!isTouching && !listState.isScrollInProgress && !userScrolledUp) {
                    val info = listState.layoutInfo
                    val lastItem = info.visibleItemsInfo.lastOrNull()
                    val itemCount = info.totalItemsCount
                    if (lastItem != null && itemCount > 0 && lastItem.index >= itemCount - 2) {
                        val contentBottom = lastItem.offset + lastItem.size
                        val distanceFromBottom = contentBottom - info.viewportEndOffset
                        if (distanceFromBottom < nearBottomThresholdPx) {
                            coroutineScope.launch {
                                listState.scrollToItem(itemCount - 1, scrollOffset = Int.MAX_VALUE)
                            }
                        }
                    }
                }
            }
        } else if (wasStreaming) {
            // Streaming just ended. The final messages are now in displayMessages
            // synchronously (finalizeChatDisplay swaps the streaming bubble for the real
            // AI message in the same state update that clears isStreaming), so we only need
            // a brief layout-settle before scrolling to show the complete response.
            // Only auto-scroll if user hasn't scrolled away.
            wasStreaming = false
            if (!userScrolledUp) {
                delay(50)
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0) {
                    listState.scrollToItem(total - 1, scrollOffset = Int.MAX_VALUE)
                }
            }
        }
    }

    // ── Keyboard scroll ────────────────────────────────────────────
    // When the soft keyboard (IME) opens, the Scaffold's imePadding()
    // shrinks the viewport. We detect this by observing the
    // LazyColumn's viewportEndOffset via snapshotFlow. When it
    // shrinks and the user was near the bottom, we scroll down to
    // keep the latest messages visible above the input box.
    //
    // This approach is more reliable than observing WindowInsets.ime
    // directly because:
    //  - It fires AFTER the layout has actually resized (no timing
    //    issues with keyboard animation).
    //  - It avoids known issues with WindowInsets.isImeVisible on
    //    targetSdk 35.
    //  - It naturally handles any cause of viewport shrinkage, not
    //    just keyboard appearance.
    // ───────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        var previousViewportEnd = 0
        snapshotFlow {
            listState.layoutInfo.viewportEndOffset
        }.collect { viewportEnd ->
            if (previousViewportEnd > 0 && viewportEnd < previousViewportEnd) {
                // Viewport shrank (e.g., keyboard opened). If user was
                // near the bottom, scroll to keep them there.
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()
                val itemCount = info.totalItemsCount
                if (lastVisible != null && itemCount > 0 && lastVisible.index >= itemCount - 2) {
                    coroutineScope.launch {
                        listState.scrollToItem(itemCount - 1, scrollOffset = Int.MAX_VALUE)
                    }
                }
            }
            previousViewportEnd = viewportEnd
        }
    }

    val pullToRefreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        state = pullToRefreshState,
        // The list extends under the floating top bar; offset the refresh indicator by the same
        // top inset so it appears in clear space below the bar rather than behind its scrim.
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topContentPadding),
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            // Observe on Initial pass so we don't interfere with scrolling
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            isTouching = event.changes.any { it.pressed }
                        }
                    }
                },
            state = listState,
            contentPadding = PaddingValues(top = topContentPadding + 8.dp, bottom = bottomContentPadding),
        ) {
            itemsIndexed(
                items = displayMessages,
                // Key by the conversation SLOT (parent in the active path), not the message's
                // own server-assigned id: the slot is stable across the streaming→final swap
                // and regenerate siblings, so the trailing streaming reply (keyed to the same
                // slot below) and its finalized message stay ONE in-place item and the list
                // keeps its scroll anchor at completion. Unique within the path (one node/level).
                key = { _, node -> node.treeParentKey },
                contentType = { _, node ->
                    val role = if (node.message.isCreatedByUser) "user" else "assistant"
                    "${chatLayoutStyle}_$role"
                },
            ) { index, node ->
                val feedbackRating = node.message.feedback?.rating
                val currentFeedbackStr = when (feedbackRating) {
                    FeedbackRating.THUMBS_UP -> "thumbsUp"
                    FeedbackRating.THUMBS_DOWN -> "thumbsDown"
                    null -> null
                }

                val isMatch = index in searchMatchMessageIndexSet
                val isCurrent = currentSearchMatch != null && index == currentSearchMatch.messageIndex
                // Determine which occurrence in this message is focused
                val focusedOccurrenceInMessage = if (isCurrent) {
                    currentSearchMatch?.occurrenceInMessage ?: -1
                } else {
                    -1
                }

                // Last message parses markdown synchronously; see [LocalImmediateMarkdown].
                CompositionLocalProvider(
                    LocalImmediateMarkdown provides (index == displayMessages.lastIndex),
                ) {
                MessageBubble(
                    message = node.message,
                    siblingIndex = node.siblingIndex,
                    siblingCount = node.siblingCount,
                    onSiblingNavigation = { newIndex ->
                        lastNavigatedParentKey = node.treeParentKey
                        onSiblingNavigation(node.treeParentKey, newIndex)
                    },
                    showActionsInitially = lastNavigatedParentKey == node.treeParentKey,
                    onEdit = { onEditMessage(node.message.messageId) },
                    onRegenerate = if (!node.message.isCreatedByUser) {
                        { onRegenerateMessage(node.message.messageId) }
                    } else {
                        null
                    },
                    onCopy = { onCopyMessage(node.message.messageId) },
                    onFeedback = if (!node.message.isCreatedByUser) {
                        { rating -> onFeedback(node.message.messageId, rating) }
                    } else {
                        null
                    },
                    onContinue = if (!node.message.isCreatedByUser) {
                        { onContinue(node.message.messageId) }
                    } else {
                        null
                    },
                    onReadAloud = { onReadAloud(node.message.messageId) },
                    onFork = { onFork(node.message.messageId) },
                    baseUrl = baseUrl,
                    fontSizeMultiplier = fontSizeMultiplier,
                    isReading = currentlyReadingMessageId == node.message.messageId,
                    currentFeedback = currentFeedbackStr,
                    isEditing = editingMessageId == node.message.messageId,
                    editText = if (editingMessageId == node.message.messageId) editingText else "",
                    onEditTextChange = onEditTextChange,
                    onEditSaveAndSubmit = onEditSaveAndSubmit,
                    onEditSaveOnly = onEditSaveOnly,
                    onEditCancel = onEditCancel,
                    userAvatarUrl = userAvatarUrl,
                    userName = userName,
                    selectedEndpoint = selectedEndpoint,
                    showImageDescriptions = showImageDescriptions,
                    chatLayoutStyle = chatLayoutStyle,
                    showAvatars = showAvatars,
                    showBubbles = showBubbles,
                    useKatex = useKatex,
                    searchQuery = searchQuery,
                    isSearchMatch = isMatch,
                    isCurrentSearchMatch = isCurrent,
                    searchFocusedOccurrence = focusedOccurrenceInMessage,
                    onFocusedOccurrencePosition = if (isCurrent) {
                        { coordinates ->
                            if (!pendingFineTuneScroll) return@MessageBubble
                            pendingFineTuneScroll = false

                            val layoutInfo = listState.layoutInfo
                            val viewportTop = layoutInfo.viewportStartOffset.toFloat()
                            val viewportBottom = layoutInfo.viewportEndOffset.toFloat()

                            val segmentBounds = coordinates.boundsInRoot()
                            val padding = 80f // Extra padding so the highlight isn't flush against edges
                            // Push the focused match clear of the floating top bar, which overlays
                            // the list's top edge (the list is full-bleed beneath it).
                            val topInset = padding + topContentPaddingPx

                            coroutineScope.launch {
                                if (segmentBounds.top < viewportTop + topInset) {
                                    // Segment is above or too close to the top of the viewport
                                    listState.animateScrollBy(segmentBounds.top - viewportTop - topInset)
                                } else if (segmentBounds.bottom > viewportBottom - padding) {
                                    // Segment is below or too close to the bottom of the viewport
                                    listState.animateScrollBy(segmentBounds.bottom - viewportBottom + padding)
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
                }
            }

            if (isStreaming) {
                // Key the reply to the slot its finalized message will occupy: it attaches to
                // the last message in the truncated path, whose id is the finalized node's
                // treeParentKey (see itemsIndexed key above). Sharing that key makes the swap
                // an in-place content update rather than a scroll-resetting remove+add.
                val streamingSlotKey = displayMessages.lastOrNull()?.message?.messageId
                    ?: "streaming_message"
                item(key = streamingSlotKey) {
                    StreamingMessageBubble(
                        streamingContent = streamingContent,
                        senderName = streamingSenderName,
                        senderIconUrl = null,
                        fontSizeMultiplier = fontSizeMultiplier,
                        selectedEndpoint = selectedEndpoint,
                        chatLayoutStyle = chatLayoutStyle,
                        showAvatars = showAvatars,
                        showBubbles = showBubbles,
                        useKatex = useKatex,
                    )
                }

                if (activeToolCalls.isNotEmpty()) {
                    items(
                        items = activeToolCalls,
                        key = { "tool_call_${it.id}" },
                        contentType = { "tool_call" },
                    ) { toolCall ->
                        StreamingToolCallCard(
                            toolCall = toolCall,
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 4.dp,
                            ),
                            baseUrl = baseUrl,
                            streamingAttachments = streamingAttachments,
                            showImageDescriptions = showImageDescriptions,
                        )
                    }
                }

                // Live deferred office-doc previews (v0.8.6): pending→Preparing,
                // ready→artifact, failed→chip. Folded by file_id in the VM delegate.
                val officeAttachments = streamingAttachments.filter {
                    ArtifactType.isOfficePreviewMime(it.type)
                }
                if (officeAttachments.isNotEmpty()) {
                    item(key = "streaming_office_previews") {
                        OfficePreviewAttachments(
                            attachments = officeAttachments,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            isDarkTheme = isSurfaceDark(),
                        )
                    }
                }
            }
        }

        // Scroll-to-bottom FAB
        AnimatedVisibility(
            visible = showScrollToBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Float the FAB just above the input bar, tracking the same inset as the content.
                .padding(bottom = (bottomContentPadding - 30.dp).coerceAtLeast(16.dp)),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    userScrolledUp = false
                    coroutineScope.launch {
                        if (totalItemCount > 0) {
                            listState.animateScrollToItem(totalItemCount - 1, scrollOffset = Int.MAX_VALUE)
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 4.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(Res.string.cd_scroll_to_bottom),
                )
            }
        }
    }
}
