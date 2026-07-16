package com.garfiec.librechat.feature.chat.screen

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.animateToWithDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.LatexRenderer
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.chat.components.ChatFloatingTopBar
import com.garfiec.librechat.feature.chat.components.ChatInput
import com.garfiec.librechat.feature.chat.components.ChatRoot
import com.garfiec.librechat.feature.chat.components.ChatToolsSheetContent
import com.garfiec.librechat.feature.chat.components.rememberChatAttachmentActions
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import com.garfiec.librechat.feature.chat.viewmodel.asString
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Anchor states for the finger-following pull-up tools sheet. */
private enum class PullUpAnchor { Hidden, Revealed }

/**
 * Per-gesture bookkeeping for the pull-up reveal, held outside the [NestedScrollConnection] so it
 * survives the connection being recreated and can be reset from a pointer-down handler. [listScrolled]
 * is true once the thread list has consumed scroll within the current gesture — while set, the reveal
 * is suppressed so a single drag from the top can't overshoot the bottom into opening the sheet.
 */
private class PullUpGesture {
    var listScrolled = false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun ChatScreen(
    modifier: Modifier,
    conversationId: String?,
    isTemporaryRoute: Boolean,
    initialAgentId: String?,
    initialEndpoint: String?,
    initialModel: String?,
    onConversationStart: ((conversationId: String, isTemporary: Boolean) -> Unit)?,
    onNavigateToConversation: ((String) -> Unit)?,
    onOpenDrawer: (() -> Unit)?,
    onNavigateToPromptsLibrary: (() -> Unit)?,
    onNavigateBack: (() -> Unit)?,
    onShowAllMedia: (() -> Unit)?,
    onAttachFromServer: () -> Unit,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    val viewModel: ChatViewModel =
        koinViewModel {
            parametersOf(conversationId, initialAgentId, isTemporaryRoute, initialEndpoint, initialModel)
        }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val attachedFiles by viewModel.attachedFiles.collectAsStateWithLifecycle()
    val shareLinkUrl by viewModel.shareLinkUrl.collectAsStateWithLifecycle()
    val prefs by viewModel.chatPreferences.collectAsStateWithLifecycle()
    val showImageDescriptions = prefs.showImageDescriptions
    val dismissKeyboardOnSend = prefs.dismissKeyboardOnSend
    val chatLayoutStyle = prefs.chatLayoutStyle
    val showAvatars = prefs.showAvatars
    val showBubbles = prefs.showBubbles
    val useKatex = prefs.latexRenderer == LatexRenderer.KATEX
    val sttEngine = prefs.sttEngine
    val sttLanguage = prefs.sttLanguage
    val keyboardController = LocalSoftwareKeyboardController.current
    val fontSizeMultiplier = when (uiState.chatFontSize) {
        ChatFontSize.SMALL -> 0.85f
        ChatFontSize.MEDIUM -> 1.0f
        ChatFontSize.LARGE -> 1.2f
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var showPresetPicker by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showSecondaryModelSheet by remember { mutableStateOf(false) }
    var activeComparisonTab by remember { mutableStateOf(0) }

    // Header/composer model labels (agent name vs model name, with the "never a raw
    // model string under agents" rule). Shared with iOS via rememberChatModelLabel.
    val (agentName, displayModel) = rememberChatModelLabel(
        selectedEndpoint = uiState.selectedEndpoint,
        selectedModel = uiState.selectedModel,
        agents = uiState.agents,
    )

    val onStartRecordingWithPermission = rememberChatStartRecording(
        viewModel = viewModel,
        sttEngine = sttEngine,
        sttLanguage = sttLanguage,
        snackbarHostState = snackbarHostState,
        coroutineScope = coroutineScope,
    )

    // Shared by the composer "+" sheet (ChatInput) and the pull-up sheet: opening the model selector
    // routes to the secondary picker on the comparison screen's second tab, and the displayed model
    // label follows the same tab. Hoisted here so both entry points behave identically.
    val onOpenModelSelector: () -> Unit = {
        if (uiState.comparisonState.isEnabled && activeComparisonTab == 1) {
            showSecondaryModelSheet = true
        } else {
            viewModel.openModelSheet()
        }
    }
    val effectiveSelectedModelDisplay = if (uiState.comparisonState.isEnabled && activeComparisonTab == 1) {
        viewModel.getSecondaryModelDisplayName()
            ?: uiState.comparisonState.secondaryModel
            ?: displayModel
    } else {
        displayModel
    }
    // One launcher set, registered here and shared by both the composer "+" sheet (ChatInput) and
    // the pull-up sheet: a launcher stays usable from descendant compositions while the one that
    // registered it (this screen) is alive, so a second registration would be redundant.
    val attachmentActions = rememberChatAttachmentActions(viewModel::onFilesSelected)

    ChatScreenEffects(
        uiState = uiState,
        shareLinkUrl = shareLinkUrl,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        clipboardManager = clipboardManager,
        onConversationStart = onConversationStart,
        onNavigateToConversation = onNavigateToConversation,
        onNavigateBack = onNavigateBack,
        onNavigateToProviderKeys = onNavigateToProviderKeys,
    )

    val sendBlockMessage = uiState.sendBlockReason?.asString()

    ChatRoot(
        inlineArtifactPrefs = prefs.inlineArtifactPrefs,
        mermaidRenderCache = viewModel.mermaidRenderCache,
        parsedMarkdownCache = viewModel.parsedMarkdownCache,
        subagentProgress = uiState.subagentProgress,
        mediaPreview = uiState.mediaPreview,
        onOpenMedia = viewModel::openMedia,
        onCloseMedia = viewModel::closeMedia,
        onDownloadAttachment = viewModel::downloadFileBytes,
    ) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        // The floating top bar draws behind the status bar itself (statusBarsPadding), so the body
        // must extend under the status bar — only reserve the navigation-bar inset here (for the
        // snackbar); the composer handles its own nav-bar padding.
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        // The composer overlays the message list at the bottom; the list reserves a scrollable
        // bottom inset so its latest content rests above the bar. We measure the bar's actual
        // height (which grows as queued ghost rows stack above it) so streaming text never hides
        // behind the ghosts, while the list still scrolls *behind* the translucent overlay. The
        // floating top bar is measured the same way so the first message clears it while still
        // scrolling up behind its dimming scrim.
        var inputBarHeightPx by remember { mutableIntStateOf(0) }
        var topBarHeightPx by remember { mutableIntStateOf(0) }
        val bottomContentPadding = with(LocalDensity.current) {
            maxOf(160.dp, inputBarHeightPx.toDp() + 16.dp)
        }
        // Floor the top inset at status bar + one chip row so content clears the bar on the first
        // frame (before onSizeChanged reports the real height), avoiding a content-under-bar flash
        // on each screen entry. Mirrors the bottomContentPadding floor.
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topContentPadding = with(LocalDensity.current) {
            maxOf(statusBarTop + 56.dp, topBarHeightPx.toDp())
        }
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── Pull-up tools sheet ───────────────────────────────────────────────
            // An upward pull on the thread surface (once it's scrolled to the bottom) progressively
            // reveals the same "+" tools/attachment menu, following the finger via an anchored-drag
            // surface. Material 3's ModalBottomSheet can't be driven by external over-scroll, so this
            // is a custom surface rather than the ModalBottomSheet the composer's "+" still opens.
            val pullUpState = remember { AnchoredDraggableState(PullUpAnchor.Hidden) }
            var pullUpSheetHeightPx by remember { mutableIntStateOf(0) }
            val pullUpGesture = remember { PullUpGesture() }
            // Fling velocity above which a flick (rather than drag position) decides open/closed.
            val pullUpMinFlingVelocityPx = with(LocalDensity.current) { 125.dp.toPx() }
            // Cap the sheet's height to the space below the top bar so tall content (e.g. many MCP
            // servers) can't push the bottom-anchored surface up past the screen top and clip the
            // attachment cards off-screen. Excess content scrolls inside the sheet instead. No minimum
            // floor: in a very short window (split-screen/freeform) a floor could itself exceed the
            // window and reintroduce the top-clip. coerceAtLeast(0) only guards heightIn against a
            // negative on a pathologically small window.
            val pullUpMaxSheetHeight =
                (LocalConfiguration.current.screenHeightDp.dp - topContentPadding - 8.dp)
                    .coerceAtLeast(0.dp)
            val pullUpFling = AnchoredDraggableDefaults.flingBehavior(
                state = pullUpState,
                positionalThreshold = { distance -> distance * 0.4f },
                animationSpec = tween(),
            )
            LaunchedEffect(pullUpSheetHeightPx) {
                if (pullUpSheetHeightPx > 0) {
                    pullUpState.updateAnchors(
                        DraggableAnchors {
                            PullUpAnchor.Hidden at pullUpSheetHeightPx.toFloat()
                            PullUpAnchor.Revealed at 0f
                        },
                    )
                }
            }
            // Dismiss the IME the moment the sheet starts to reveal (the Scaffold uses imePadding()).
            LaunchedEffect(pullUpState, pullUpSheetHeightPx) {
                snapshotFlow {
                    val o = pullUpState.offset
                    !o.isNaN() && pullUpSheetHeightPx > 0 && o < pullUpSheetHeightPx
                }.distinctUntilChanged().collect { revealing ->
                    if (revealing) keyboardController?.hide()
                }
            }
            // Bridges the message-list over-scroll into the sheet: reveal on leftover upward drag once
            // the list is at its true bottom (onPostScroll), retract on downward drag while the sheet
            // is open (onPreScroll). Reads pullUpSheetHeightPx live, so remember only on the state.
            val pullUpConnection = remember(pullUpState, pullUpMinFlingVelocityPx) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        val o = pullUpState.offset
                        val open = !o.isNaN() && o < pullUpSheetHeightPx
                        return if (available.y > 0f && open) {
                            Offset(0f, pullUpState.dispatchRawDelta(available.y))
                        } else {
                            Offset.Zero
                        }
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (consumed.y != 0f) {
                            pullUpGesture.listScrolled = true
                        }
                        val o = pullUpState.offset
                        val open = !o.isNaN() && o < pullUpSheetHeightPx
                        // Only reveal from a gesture that began at the bottom (list never scrolled),
                        // or keep responding once the sheet is already opening. The flag is reset on
                        // each pointer-down (see pullUpListModifier), so a cancelled gesture can't
                        // leave the reveal permanently suppressed.
                        val allowReveal = !pullUpGesture.listScrolled || open
                        return if (available.y < 0f && allowReveal) {
                            Offset(0f, pullUpState.dispatchRawDelta(available.y))
                        } else {
                            Offset.Zero
                        }
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        val o = pullUpState.offset
                        val open = !o.isNaN() && o < pullUpSheetHeightPx
                        return if (available.y > 0f && open) {
                            settleSheet(available.y)
                            available
                        } else {
                            Velocity.Zero
                        }
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        settleSheet(available.y)
                        return available
                    }

                    // Velocity-aware settle to the nearest anchor. Only acts once the sheet is already
                    // partly open, so a fling that merely reaches the bottom (sheet still hidden) can't
                    // fling it open — the reveal must have been started by a deliberate pull. A fast
                    // flick then wins on velocity; otherwise position decides (open past 40% revealed,
                    // matching pullUpFling's 0.4 positionalThreshold). Uses the modifier-era
                    // animateToWithDecay — NOT settle(velocity), which throws for a state built with
                    // the threshold-less constructor.
                    private suspend fun settleSheet(velocity: Float) {
                        val height = pullUpSheetHeightPx.toFloat()
                        val o = pullUpState.offset
                        if (height <= 0f || o.isNaN() || o >= height) return
                        val target = when {
                            velocity <= -pullUpMinFlingVelocityPx -> PullUpAnchor.Revealed
                            velocity >= pullUpMinFlingVelocityPx -> PullUpAnchor.Hidden
                            o < height * 0.6f -> PullUpAnchor.Revealed
                            else -> PullUpAnchor.Hidden
                        }
                        pullUpState.animateToWithDecay(target, velocity)
                    }
                }
            }
            // The active list gets the nested-scroll bridge plus a pointer-down reset of the
            // per-gesture "list scrolled" flag, so every fresh touch re-arms the reveal even if a
            // prior drag ended without a fling callback.
            val pullUpListModifier = Modifier
                .nestedScroll(pullUpConnection)
                .pointerInput(pullUpGesture) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pullUpGesture.listScrolled = false
                    }
                }
            // The landing screen isn't scrollable, so nested-scroll never fires there — drive the same
            // state with a direct drag modifier instead.
            val pullUpLandingModifier = Modifier.anchoredDraggable(
                state = pullUpState,
                orientation = Orientation.Vertical,
                flingBehavior = pullUpFling,
            )

            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                ChatContent(
                    listPullUpModifier = pullUpListModifier,
                    pullUpModifier = pullUpLandingModifier,
                    uiState = uiState,
                    viewModel = viewModel,
                    clipboardManager = clipboardManager,
                    agentName = agentName,
                    displayModel = displayModel,
                    fontSizeMultiplier = fontSizeMultiplier,
                    showImageDescriptions = showImageDescriptions,
                    chatLayoutStyle = chatLayoutStyle,
                    showAvatars = showAvatars,
                    showBubbles = showBubbles,
                    useKatex = useKatex,
                    bottomContentPadding = bottomContentPadding,
                    topContentPadding = topContentPadding,
                    onShowSecondaryModelSheet = { showSecondaryModelSheet = true },
                    onComparisonTabChange = { activeComparisonTab = it },
                )
            }

            // ChatInput overlays at the bottom so gradient shows content behind
            val isAnyStreaming = uiState.isStreaming ||
                uiState.comparisonState.primaryIsStreaming ||
                uiState.comparisonState.secondaryIsStreaming
            ChatInput(
                inputText = uiState.inputText,
                isStreaming = isAnyStreaming,
                onInputChanged = viewModel::onInputChanged,
                onSend = {
                    viewModel.sendMessage()
                    if (dismissKeyboardOnSend) {
                        keyboardController?.hide()
                    }
                },
                onStop = viewModel::stopGeneration,
                onQueue = {
                    viewModel.queueMessage()
                    if (dismissKeyboardOnSend) {
                        keyboardController?.hide()
                    }
                },
                canQueue = uiState.canQueueFollowUp,
                attachedFiles = attachedFiles,
                attachmentActions = attachmentActions,
                onRemoveFile = viewModel::removeFile,
                onAttachFromServer = onAttachFromServer,
                promptSuggestions = uiState.availablePrompts,
                onPromptSelected = viewModel::handlePromptMention,
                onSlashCommandSelected = viewModel::handleSlashCommand,
                isRecording = uiState.isRecording,
                isTranscribing = uiState.isTranscribing,
                onStartRecording = onStartRecordingWithPermission,
                onStopRecording = viewModel::stopRecording,
                enabledTools = uiState.effectiveEnabledTools,
                pinnedToolKeys = uiState.pinnedToolChips,
                onToggleTool = viewModel::toggleTool,
                mcpServers = uiState.mcpServers,
                selectedMcpServerNames = uiState.selectedMcpServerNames,
                onToggleMcpServer = viewModel::toggleMcpServer,
                onOpenModelParameters = viewModel::showModelParameters,
                onOpenModelSelector = onOpenModelSelector,
                selectedModelDisplay = effectiveSelectedModelDisplay,
                isCodeInterpreterAvailable = uiState.isCodeInterpreterAvailable,
                webSearchEnabled = uiState.webSearchEnabled,
                urlContextEnabled = uiState.urlContextProviderGate,
                runCodeEnabled = uiState.runCodeEnabled,
                fileSearchEnabled = uiState.fileSearchEnabled,
                mcpServersEnabled = uiState.mcpServersEnabled,
                gates = uiState.chatInputGates,
                contextUsage = uiState.contextUsage,
                tokenUsage = uiState.tokenUsage,
                contextUsageEnabled = uiState.contextUsageEnabled,
                contextBarPlacement = uiState.contextBarPlacement,
                contextGaugeExpanded = uiState.contextGaugeExpanded,
                onContextGaugeExpandedChange = viewModel::setContextGaugeExpanded,
                // After a Stop/error pause, the queue waits for an explicit nudge.
                queuedPausedCount = uiState.pausedQueueCount,
                onSendQueuedMessages = viewModel::sendQueuedNow,
                isEditingQueued = uiState.isEditingQueued,
                onCommitEdit = viewModel::commitQueuedEdit,
                onCancelEdit = viewModel::cancelQueuedEdit,
                isAwaitingUploadSend = uiState.isAwaitingUploadSend,
                onCancelPendingSend = viewModel::cancelPendingUploadSend,
                queuedMessages = uiState.messageQueue,
                onEditQueuedMessage = viewModel::editQueued,
                onCancelQueuedMessage = viewModel::cancelQueued,
                onReorderQueuedMessages = viewModel::reorderQueue,
                fontSizeMultiplier = fontSizeMultiplier,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { inputBarHeightPx = it.height },
            )

            // Floating top bar overlays the message list (drawn last so it sits above content),
            // measured so ChatContent can reserve a matching scrollable top inset.
            ChatFloatingTopBar(
                uiState = uiState,
                viewModel = viewModel,
                onLoadPreset = { showPresetPicker = true },
                onSavePreset = { showSavePresetDialog = true },
                onRename = viewModel::showRenameDialog,
                onOpenDrawer = onOpenDrawer,
                onShowAllMedia = onShowAllMedia,
                onOpenPromptsLibrary = onNavigateToPromptsLibrary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { topBarHeightPx = it.height },
            )

            // Pull-up sheet overlay (drawn last so scrim + sheet sit above the composer and top bar).
            // `pullUpVisible` is derived so the scrim/back-handler recompose only on the open<->closed
            // transition; the scrim's dim level is drawn in the draw phase (drawBehind) and the sheet
            // offset is read in the layout phase (offset {}), so a drag doesn't recompose the screen.
            val pullUpVisible by remember {
                derivedStateOf {
                    val o = pullUpState.offset
                    pullUpSheetHeightPx > 0 && !o.isNaN() && o < pullUpSheetHeightPx
                }
            }
            if (pullUpVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Drag the dimmed backdrop to push the sheet back down, or tap to dismiss.
                        .anchoredDraggable(
                            state = pullUpState,
                            orientation = Orientation.Vertical,
                            flingBehavior = pullUpFling,
                        )
                        .pointerInput(Unit) {
                            detectTapGestures {
                                coroutineScope.launch { pullUpState.animateTo(PullUpAnchor.Hidden) }
                            }
                        }
                        .drawBehind {
                            val o = pullUpState.offset
                            val p = if (pullUpSheetHeightPx > 0 && !o.isNaN()) {
                                (1f - o / pullUpSheetHeightPx).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            drawRect(color = Color.Black, alpha = 0.5f * p)
                        },
                )
            }
            BackHandler(enabled = pullUpVisible) {
                coroutineScope.launch { pullUpState.animateTo(PullUpAnchor.Hidden) }
            }
            Surface(
                color = BottomSheetDefaults.ContainerColor,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Match ModalBottomSheet: full-bleed on phones, capped + centered on wide
                    // (fold/tablet) displays instead of edge-to-edge.
                    .fillMaxWidth()
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                    .heightIn(max = pullUpMaxSheetHeight)
                    // Stay invisible until measured: on the very first frame the offset is NaN and the
                    // measured height is still 0, so without this the sheet would place at offset 0
                    // (fully revealed) and flash the whole menu open on every chat entry.
                    .graphicsLayer { alpha = if (pullUpSheetHeightPx > 0) 1f else 0f }
                    .onSizeChanged { pullUpSheetHeightPx = it.height }
                    .offset {
                        val o = pullUpState.offset
                        IntOffset(0, if (o.isNaN()) pullUpSheetHeightPx else o.roundToInt())
                    }
                    .anchoredDraggable(
                        state = pullUpState,
                        orientation = Orientation.Vertical,
                        flingBehavior = pullUpFling,
                    ),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LowProfileDragHandle()
                    ChatToolsSheetContent(
                        enabledTools = uiState.effectiveEnabledTools,
                        onToggleTool = viewModel::toggleTool,
                        mcpServers = uiState.mcpServers,
                        selectedMcpServerNames = uiState.selectedMcpServerNames,
                        onToggleMcpServer = viewModel::toggleMcpServer,
                        onAttachFiles = attachmentActions.onAttachFiles,
                        onTakePhoto = attachmentActions.onTakePhoto,
                        onPickPhotos = attachmentActions.onPickPhotos,
                        onAttachFromServer = onAttachFromServer,
                        onOpenModelParameters = viewModel::showModelParameters,
                        onOpenModelSelector = onOpenModelSelector,
                        selectedModelDisplay = effectiveSelectedModelDisplay,
                        onDismiss = {
                            coroutineScope.launch { pullUpState.animateTo(PullUpAnchor.Hidden) }
                        },
                        isCodeInterpreterAvailable = uiState.isCodeInterpreterAvailable,
                        webSearchEnabled = uiState.webSearchEnabled,
                        urlContextEnabled = uiState.urlContextProviderGate,
                        runCodeEnabled = uiState.runCodeEnabled,
                        fileSearchEnabled = uiState.fileSearchEnabled,
                        mcpServersEnabled = uiState.mcpServersEnabled,
                        gates = uiState.chatInputGates,
                        contextUsage = uiState.contextUsage,
                        tokenUsage = uiState.tokenUsage,
                        contextUsageEnabled = uiState.contextUsageEnabled,
                        contextBarPlacement = uiState.contextBarPlacement,
                        contextGaugeExpanded = uiState.contextGaugeExpanded,
                        onContextGaugeExpandedChange = viewModel::setContextGaugeExpanded,
                    )
                }
            }
        }
    }

    ChatScreenDialogs(
        uiState = uiState,
        viewModel = viewModel,
        sendBlockMessage = sendBlockMessage,
        showPresetPicker = showPresetPicker,
        showSavePresetDialog = showSavePresetDialog,
        showSecondaryModelSheet = showSecondaryModelSheet,
        onSetShowPresetPicker = { showPresetPicker = it },
        onSetShowSavePresetDialog = { showSavePresetDialog = it },
        onSetShowSecondaryModelSheet = { showSecondaryModelSheet = it },
        onNavigateToProviderKeys = onNavigateToProviderKeys,
    )
    }
}
