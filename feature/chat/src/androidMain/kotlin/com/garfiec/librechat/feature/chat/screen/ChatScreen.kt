package com.garfiec.librechat.feature.chat.screen

import android.annotation.SuppressLint
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
import com.garfiec.librechat.feature.chat.prompts.components.VariableInputDialog
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
import com.garfiec.librechat.core.model.response.pickerMimeTypes
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.chat.components.ChatFloatingTopBar
import com.garfiec.librechat.feature.chat.components.ChatInput
import com.garfiec.librechat.feature.chat.components.ChatRoot
import com.garfiec.librechat.feature.chat.components.ChatOptionsPage
import com.garfiec.librechat.feature.chat.components.ChatToolsSheetContent
import com.garfiec.librechat.feature.chat.components.UploadRoutingSheet
import com.garfiec.librechat.feature.chat.components.rememberChatOptionsSheetController
import com.garfiec.librechat.feature.chat.components.rememberChatAttachmentActions
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import com.garfiec.librechat.feature.chat.viewmodel.asString
import com.garfiec.librechat.feature.chat.viewmodel.neutralizeStreamingChurn
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
// The Scaffold's content padding is deliberately unused: the thread draws under both bars (the
// floating top bar applies its own statusBarsPadding, the composer its own nav-bar padding) and the
// list reserves its insets from the measured bar heights instead. contentWindowInsets is still set
// so the snackbar clears the navigation bar.
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
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
    // Chrome-rate state; ChatContent collects at full rate. Never read a neutralized field here.
    val chromeFlow = remember(viewModel) {
        viewModel.uiState.map { it.neutralizeStreamingChurn() }.distinctUntilChanged()
    }
    val initialChrome = remember(viewModel) { viewModel.uiState.value.neutralizeStreamingChurn() }
    val uiState by chromeFlow.collectAsStateWithLifecycle(initialChrome)
    val attachedFiles by viewModel.attachedFiles.collectAsStateWithLifecycle()
    val shareLinkUrl by viewModel.shareLinkUrl.collectAsStateWithLifecycle()
    val prefs by viewModel.chatPreferences.collectAsStateWithLifecycle()
    val promptLibraryRevision by viewModel.promptLibraryRevision.collectAsStateWithLifecycle()
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

    // Options sheet ("+" menu, model selector/parameters as pages). Opened from the composer's "+"
    // and the pull-up, hence the controller. Independent of uiState.showModelSheet (the standalone
    // selector), which keeps dismissing straight to the chat.
    val optionsController = rememberChatOptionsSheetController()
    // On comparison tab 1 the composer edits the secondary model, so the selector page and label
    // both follow the active tab.
    val isSecondaryTab = uiState.comparisonState.isEnabled && activeComparisonTab == 1
    val effectiveSelectedModelDisplay = if (isSecondaryTab) {
        viewModel.getSecondaryModelDisplayName()
            ?: uiState.comparisonState.secondaryModel
            ?: displayModel
    } else {
        displayModel
    }
    // One launcher set, registered here and shared by both the composer "+" sheet (ChatInput) and
    // the pull-up sheet: a launcher stays usable from descendant compositions while the one that
    // registered it (this screen) is alive, so a second registration would be redundant.
    // Narrow the file picker to what this endpoint's `supportedMimeTypes` allows (web parity:
    // useUploadOptions). Recomputed only when the config or endpoint changes — the translation
    // compiles the server's regexes.
    // The text route is validated against `fileConfig.text`, not against this allowlist, so the
    // picker has to admit what it can extract too — otherwise a narrowed endpoint allowlist makes
    // routing-to-text unreachable, with no file even pickable to route.
    val filePickerMimeTypes = remember(
        uiState.fileUploadConfig,
        uiState.selectedEndpoint,
        uiState.isFileContextAvailable,
    ) {
        uiState.fileUploadConfig?.pickerMimeTypes(
            endpoint = uiState.selectedEndpoint,
            includeTextRoute = uiState.isFileContextAvailable,
        ).orEmpty()
    }
    val attachmentActions = rememberChatAttachmentActions(
        onFilesSelected = viewModel::onFilesSelected,
        filePickerMimeTypes = filePickerMimeTypes,
    )

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
        promptLibraryRevision = promptLibraryRevision,
        onRefreshPrompts = viewModel::refreshPromptsIfStale,
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
            // ChatToolsSheetContent hoists the MCP sub-list's expansion (the paged options sheet
            // needs it to survive a page swap), so this surface owns its own copy.
            var pullUpMcpExpanded by remember { mutableStateOf(false) }
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
            // Dismiss the IME the moment the sheet starts to reveal (the Scaffold uses imePadding()).
            LaunchedEffect(pullUpState, pullUpSheetHeightPx) {
                snapshotFlow {
                    val o = pullUpState.offset
                    !o.isNaN() && pullUpSheetHeightPx > 0 && o < pullUpSheetHeightPx
                }.distinctUntilChanged().collect { revealing ->
                    if (revealing) keyboardController?.hide()
                }
            }
            // Velocity-aware settle, only once the sheet is already partly open (so a fling that
            // merely reaches the list bottom can't fling it open). Velocity wins, else position at
            // 40%. animateToWithDecay, NOT settle(velocity) — the latter throws for this
            // threshold-less state. Shared by both bridges; reads height live, remember on state.
            val settlePullUpSheet: suspend (Float) -> Unit = remember(pullUpState, pullUpMinFlingVelocityPx) {
                { velocity ->
                    val height = pullUpSheetHeightPx.toFloat()
                    val o = pullUpState.offset
                    if (height > 0f && !o.isNaN() && o < height) {
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
            // Bridges the message-list over-scroll into the sheet: reveal on leftover upward drag once
            // the list is at its true bottom (onPostScroll), retract on downward drag while the sheet
            // is open (onPreScroll). Reads pullUpSheetHeightPx live, so remember only on the state.
            val pullUpConnection = remember(pullUpState, pullUpMinFlingVelocityPx, settlePullUpSheet) {
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
                            settlePullUpSheet(available.y)
                            available
                        } else {
                            Velocity.Zero
                        }
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        settlePullUpSheet(available.y)
                        return available
                    }
                }
            }
            // Bridges the sheet's own content scroll into the surface: anchoredDraggable is
            // pointer-input only and, unlike ModalBottomSheet, has no nested-scroll bridge, so
            // ChatToolsSheetContent's verticalScroll would otherwise swallow every drag. onPostScroll
            // for downward (content scrolls first, leftover retracts), onPreScroll for upward.
            val pullUpSheetConnection = remember(pullUpState, settlePullUpSheet) {
                object : NestedScrollConnection {
                    // Upward before the content, so a half-retracted sheet pulls back up mid-drag.
                    // Unconditional is safe: at Revealed, dispatchRawDelta consumes 0.
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        // NaN guard: before onSizeChanged sets anchors, offset is NaN and
                        // dispatchRawDelta would poison the child scrollable's delta.
                        val o = pullUpState.offset
                        return if (available.y < 0f && !o.isNaN()) {
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
                        val o = pullUpState.offset
                        val open = !o.isNaN() && o < pullUpSheetHeightPx
                        return if (available.y > 0f && open) {
                            Offset(0f, pullUpState.dispatchRawDelta(available.y))
                        } else {
                            Offset.Zero
                        }
                    }

                    // Mirror of onPreScroll: an upward flick on a half-retracted sheet settles it
                    // rather than handing velocity to the content.
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        val o = pullUpState.offset
                        val partlyRetracted = !o.isNaN() && o > 0f
                        return if (available.y < 0f && partlyRetracted) {
                            settlePullUpSheet(available.y)
                            available
                        } else {
                            Velocity.Zero
                        }
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        settlePullUpSheet(available.y)
                        return available
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
                onOpenTools = { optionsController.open() },
                // The mid-stream send button: the ViewModel resolves steer-vs-queue from the
                // user's preference and what this run can actually take, so the composer never
                // has to. `onQueue` stays the picker's explicit "add to queue".
                onDuringRunSend = {
                    viewModel.sendDuringRun()
                    if (dismissKeyboardOnSend) {
                        keyboardController?.hide()
                    }
                },
                onQueue = {
                    viewModel.queueMessage()
                    if (dismissKeyboardOnSend) {
                        keyboardController?.hide()
                    }
                },
                canQueue = uiState.canQueueFollowUp,
                // Explicit "steer this one", from the during-run picker or the send button when
                // steering is the standing default.
                onSteer = {
                    viewModel.steerMessage()
                    if (dismissKeyboardOnSend) {
                        keyboardController?.hide()
                    }
                },
                canSteer = uiState.canSteerNow,
                duringRunAction = uiState.effectiveDuringRunAction,
                duringRunSendTarget = uiState.duringRunSendTarget,
                pendingSteers = uiState.pendingSteers,
                onCancelSteer = viewModel::cancelSteer,
                onSetDuringRunAction = viewModel::setDuringRunAction,
                attachedFiles = attachedFiles,
                onRemoveFile = viewModel::removeFile,
                promptSuggestions = uiState.availablePrompts,
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
                selectedModelDisplay = effectiveSelectedModelDisplay,
                isCodeInterpreterAvailable = uiState.isCodeInterpreterAvailable,
                gates = uiState.chatInputGates,
                contextUsage = uiState.contextUsage,
                tokenUsage = uiState.tokenUsage,
                contextUsageEnabled = uiState.contextUsageEnabled,
                contextBarPlacement = uiState.contextBarPlacement,
                // After a Stop/error pause, the queue waits for an explicit nudge.
                queuedPausedCount = uiState.pausedQueueCount,
                onSendQueuedMessages = viewModel::sendQueuedNow,
                isEditingQueued = uiState.isEditingQueued,
                onCommitEdit = viewModel::commitQueuedEdit,
                onCancelEdit = viewModel::cancelQueuedEdit,
                isAwaitingUploadSend = uiState.isAwaitingUploadSend,
                arePicksUnsettled = uiState.arePicksUnsettled,
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
                    .onSizeChanged { size ->
                        if (size.height != pullUpSheetHeightPx) {
                            pullUpSheetHeightPx = size.height
                            if (size.height > 0) {
                                pullUpState.updateAnchors(
                                    DraggableAnchors {
                                        PullUpAnchor.Hidden at size.height.toFloat()
                                        PullUpAnchor.Revealed at 0f
                                    },
                                )
                            }
                        }
                    }
                    .offset {
                        val o = pullUpState.offset
                        IntOffset(0, if (o.isNaN()) pullUpSheetHeightPx else o.roundToInt())
                    }
                    .anchoredDraggable(
                        state = pullUpState,
                        orientation = Orientation.Vertical,
                        flingBehavior = pullUpFling,
                    )
                    // Lets a drag over the scrolling content move the surface. See pullUpSheetConnection.
                    .nestedScroll(pullUpSheetConnection),
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
                        // The pull-up hands these two pages off to the options sheet rather than
                        // swapping its own anchored-drag surface (which the selector's search/IME/list
                        // would fight), retracting itself here.
                        onOpenModelParameters = {
                            optionsController.open(ChatOptionsPage.ModelParameters)
                            coroutineScope.launch { pullUpState.animateTo(PullUpAnchor.Hidden) }
                        },
                        onOpenModelSelector = {
                            optionsController.open(ChatOptionsPage.ModelSelector)
                            coroutineScope.launch { pullUpState.animateTo(PullUpAnchor.Hidden) }
                        },
                        selectedModelDisplay = effectiveSelectedModelDisplay,
                        onDismiss = {
                            coroutineScope.launch { pullUpState.animateTo(PullUpAnchor.Hidden) }
                        },
                        isCodeInterpreterAvailable = uiState.isCodeInterpreterAvailable,
                        webSearchEnabled = uiState.webSearchEnabled,
                        urlContextEnabled = uiState.urlContextProviderGate,
                        runCodeEnabled = uiState.runCodeEnabled,
                        fileSearchEnabled = uiState.fileSearchEnabled,
                        memoryEnabled = uiState.isMemoryToolAvailable,
                        mcpServersEnabled = uiState.mcpServersEnabled,
                        gates = uiState.chatInputGates,
                        contextUsage = uiState.contextUsage,
                        tokenUsage = uiState.tokenUsage,
                        contextUsageEnabled = uiState.contextUsageEnabled,
                        contextBarPlacement = uiState.contextBarPlacement,
                        contextGaugeExpanded = uiState.contextGaugeExpanded,
                        onContextGaugeExpandedChange = viewModel::setContextGaugeExpanded,
                        mcpExpanded = pullUpMcpExpanded,
                        onMcpExpandedChange = { pullUpMcpExpanded = it },
                    )
                }
            }

            // Manual attachment routing. The pick can come from the pull-up surface, which stays
            // revealed and drag-responsive underneath — retract it before the sheet shows, or the
            // two surfaces fight for the same gestures.
            val pendingRouting = uiState.composer.pendingUploadRouting
            LaunchedEffect(pendingRouting != null) {
                if (pendingRouting != null) pullUpState.animateTo(PullUpAnchor.Hidden)
            }
            if (pendingRouting != null) {
                UploadRoutingSheet(
                    files = pendingRouting.files,
                    onRouteChange = viewModel::setPendingUploadRoute,
                    onApplyToAll = viewModel::setAllPendingUploadRoutes,
                    onConfirm = viewModel::confirmPendingUploadRouting,
                    onDismiss = viewModel::cancelPendingUploadRouting,
                )
            }
        }
    }

    ChatOptionsSheetHost(
        controller = optionsController,
        uiState = uiState,
        viewModel = viewModel,
        isSecondaryTab = isSecondaryTab,
        selectedModelDisplay = effectiveSelectedModelDisplay,
        onAttachFiles = attachmentActions.onAttachFiles,
        onTakePhoto = attachmentActions.onTakePhoto,
        onPickPhotos = attachmentActions.onPickPhotos,
        onAttachFromServer = onAttachFromServer,
        onNavigateToProviderKeys = onNavigateToProviderKeys,
        onShowSavePresetDialog = { showSavePresetDialog = true },
    )

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

    LaunchedEffect(Unit) { viewModel.consumePendingPromptInsertion() }

    uiState.pendingVariablePrompt?.let { pending ->
        VariableInputDialog(
            promptTemplate = pending.template,
            variables = pending.variables,
            onInsert = viewModel::confirmVariablePrompt,
            onDismiss = viewModel::dismissVariablePrompt,
        )
    }
    }
}
