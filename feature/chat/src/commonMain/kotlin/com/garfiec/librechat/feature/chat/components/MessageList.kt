package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.ChatLayoutConstants
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.MinimalFeedback
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.request.ToolApprovalResolution
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.MessageNode
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.garfiec.librechat.feature.chat.viewmodel.SearchFocusRequest
import com.garfiec.librechat.feature.chat.viewmodel.SearchMatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

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
    onFeedback: (messageId: String, feedback: MinimalFeedback?) -> Unit = { _, _ -> },
    /**
     * The response that just took over from the streaming bubble, from `ChatUiState`. Read from
     * state rather than derived here from `isStreaming`: a UI-side derivation can only run in an
     * effect, which commits AFTER the composition that first renders the finalized message — by
     * then its activity groups have already chosen to collapse and nothing re-opens them.
     */
    justSettledMessageId: String? = null,
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
    searchFocusRequest: SearchFocusRequest? = null,
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
    /**
     * The live human-review pause, or null. Rendered at the tail of the streaming section — the
     * run is unfinished, so it belongs to the reply in progress, not after it. Only the callers
     * that can resolve one pass it; the comparison panes leave it null.
     */
    pendingAction: PendingAction? = null,
    isResolvingPendingAction: Boolean = false,
    onSubmitToolDecisions: (List<ToolApprovalResolution>) -> Unit = {},
    onSubmitPendingAnswer: (String) -> Unit = {},
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

    // A live `ask_user_question` pause is rendered by PendingActionCard, not as a tool card.
    val renderedToolCalls = remember(activeToolCalls) { activeToolCalls.withoutUnansweredQuestions() }
    val streamingToolCallCount = if (isStreaming) renderedToolCalls.size else 0
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
            // The LIST's own count, not the hand-computed [totalItemCount] above: that one omits
            // the trailing office-preview and human-review-pause items, so with one of those off
            // screen the last visible index equals it and the FAB — the only cue that the thread
            // scrolls further — is suppressed exactly when it is needed.
            val rendered = listState.layoutInfo.totalItemsCount
            rendered > 0 && lastVisibleItem < rendered - 1
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

    // ── Search focus scroll ───────────────────────────────────────────
    // Two-phase, race-free jump to the focused search occurrence:
    //   Phase 1: scrollToItem composes the target message (a LazyColumn item
    //            composes fully even when taller than the viewport), like a
    //            browser's instant find-in-page jump.
    //   Phase 2: the focused segment reports its live LayoutCoordinates plus
    //            the match's rect within it on every position pass (keyed
    //            state, never a consumable flag — reports for a stale target
    //            are simply ignored). Once a report matching the CURRENT
    //            request exists, one animateScrollBy places the match on the
    //            browser-style anchor line (~1/3 down the usable viewport).
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var focusedMatchReport by remember { mutableStateOf<FocusedSearchMatchReport?>(null) }
    val currentOnSearchScrollHandled by rememberUpdatedState(onSearchScrollHandle)

    // Drop the held LayoutCoordinates once search closes.
    LaunchedEffect(searchQuery) {
        if (searchQuery == null) focusedMatchReport = null
    }

    LaunchedEffect(searchFocusRequest) {
        val request = searchFocusRequest ?: return@LaunchedEffect
        if (request.messageIndex !in displayMessages.indices) return@LaunchedEffect

        val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()
        val anchorLine = topContentPaddingPx + (viewportHeight - topContentPaddingPx) / 3f

        // Only jump when the target message isn't laid out yet; a far jump is expected to be
        // instant. When it's already composed (next/prev within the same or a visible message),
        // skip the jump so phase 2 animates straight to the match with no teleport-then-reverse.
        val alreadyLaidOut = listState.layoutInfo.visibleItemsInfo.any { it.index == request.messageIndex }
        if (!alreadyLaidOut) {
            listState.scrollToItem(request.messageIndex, scrollOffset = -anchorLine.toInt())
        }

        val report = withTimeoutOrNull(SEARCH_FOCUS_REPORT_TIMEOUT_MS) {
            snapshotFlow { focusedMatchReport }
                .first { it != null && it.matches(request) && it.coordinates.isAttached }
        }

        val listCoords = listCoordinates
        if (report != null && listCoords != null && listCoords.isAttached && report.coordinates.isAttached) {
            val matchTop = listCoords.localPositionOf(
                report.coordinates,
                Offset(0f, report.matchRect.top),
            ).y
            val delta = matchTop - anchorLine
            if (abs(delta) > 1f) {
                listState.animateScrollBy(delta, SearchFocusScrollSpec)
            }
        }
        currentOnSearchScrollHandled()
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

    // A run pausing for human review changes NOTHING the other scroll effects key on:
    // displayMessages.size, streamingContent and isStreaming are all unchanged. Without this the
    // card is appended below the viewport and the user is left looking at a live cursor on a run
    // that will never produce another token.
    LaunchedEffect(pendingAction?.actionId) {
        if (pendingAction?.actionId != null) {
            userScrolledUp = false
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.animateScrollToItem(total - 1, scrollOffset = Int.MAX_VALUE)
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
                .onGloballyPositioned { listCoordinates = it }
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
                val isMatch = index in searchMatchMessageIndexSet
                val isCurrent = currentSearchMatch != null && index == currentSearchMatch.messageIndex
                // Which occurrence within this message is focused (-1 when this isn't the current match).
                val focusedOccurrenceInMessage = if (isCurrent) currentSearchMatch.occurrenceInMessage else -1

                // Last message parses markdown synchronously; see [LocalImmediateMarkdown].
                // Publish the focus-request nonce only around the current match so a collapsible
                // block re-arms its auto-expand on every navigation (even back to the same match);
                // non-current bubbles see 0L and don't recompose on navigation.
                CompositionLocalProvider(
                    LocalImmediateMarkdown provides (index == displayMessages.lastIndex),
                    LocalSearchFocusNonce provides if (isCurrent) searchFocusRequest?.requestId ?: 0L else 0L,
                    LocalSuppressGroupAutoCollapse provides (node.message.messageId == justSettledMessageId),
                    LocalFeedbackEnabled provides !isStreaming,
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
                        { feedback -> onFeedback(node.message.messageId, feedback) }
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
                    currentFeedback = node.message.feedback?.rating,
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
                    onFocusedOccurrencePosition = if (isCurrent && searchQuery != null) {
                        { coordinates, matchRect ->
                            // Only record while a jump is pending; the focused node keeps reporting
                            // on every later scroll pass, and consuming those would churn state.
                            if (searchFocusRequest != null) {
                                focusedMatchReport = FocusedSearchMatchReport(
                                    requestId = searchFocusRequest.requestId,
                                    coordinates = coordinates,
                                    matchRect = matchRect,
                                )
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

                if (renderedToolCalls.isNotEmpty()) {
                    items(
                        items = renderedToolCalls,
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

                // Human-review pause (v0.8.8): the run is waiting on the user, so the resolve
                // controls sit at the tail of the still-unfinished reply — the continuation
                // streams back into the bubble above.
                if (pendingAction != null) {
                    item(key = "pending_action_${pendingAction.actionId}") {
                        PendingActionCard(
                            pendingAction = pendingAction,
                            isResolving = isResolvingPendingAction,
                            onSubmitToolDecisions = onSubmitToolDecisions,
                            onSubmitAnswer = onSubmitPendingAnswer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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

/** How long phase 2 waits for the focused occurrence to report before settling for the message top. */
private const val SEARCH_FOCUS_REPORT_TIMEOUT_MS = 500L

private val SearchFocusScrollSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * Latest reported position of the focused search occurrence: the reporting text node's
 * [coordinates] (a live handle — queries return current geometry) and the match's
 * bounding [matchRect] within that node. Tagged with the [requestId] that was live when it was
 * emitted, so the scroll effect only ever acts on a report for the exact request it is serving
 * (and never mistakes a stale report from a prior wrap-around jump for the current one).
 */
private class FocusedSearchMatchReport(
    val requestId: Long,
    val coordinates: LayoutCoordinates,
    val matchRect: Rect,
) {
    fun matches(request: SearchFocusRequest): Boolean = requestId == request.requestId
}
