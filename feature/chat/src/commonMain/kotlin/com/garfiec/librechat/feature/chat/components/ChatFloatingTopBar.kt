package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_edit_title
import com.garfiec.librechat.feature.chat.resources.cd_more_options
import com.garfiec.librechat.feature.chat.resources.cd_open_drawer
import com.garfiec.librechat.feature.chat.resources.select_model
import com.garfiec.librechat.feature.chat.screen.rememberChatModelLabel
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * The chat screen's floating top bar, shared by Android and iOS. Chips (hamburger, the configurable
 * content bubble, optional temp-chat toggle, options) are drawn over a top-down [chatTopBarScrim]
 * so chat content scrolls up *behind* a gently dimmed status-bar region rather than being capped by
 * an opaque app bar — a ChatGPT/Telegram-style header. The in-conversation search bar is pinned
 * directly beneath the chips so the overlay measures as one unit.
 *
 * The bubble is configurable along two mobile-only axes ([ChatUiState.chatHeaderContent] /
 * [ChatUiState.chatHeaderAlignment]): it shows the conversation title (long-press to edit in place),
 * the selected model (tap to open the model selector), or nothing. Showing the model here is an
 * opt-in that partially reverses the default decluttering choice of keeping model/params on the
 * composer "+" menu; the title remains the default.
 *
 * Most actions are wired straight to [viewModel]; only the triggers whose dialog hosting differs by
 * platform (preset load/save, rename) and the navigation callbacks are passed in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatFloatingTopBar(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    onLoadPreset: () -> Unit,
    onSavePreset: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    onShowAllMedia: (() -> Unit)? = null,
    onOpenPromptsLibrary: (() -> Unit)? = null,
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    val conversationId = uiState.conversationId
    val conversationTitle = uiState.conversationTitle
    // Interactive on the new-chat landing; once a temporary chat is active it stays visible (ON) as
    // a persistent indicator. Gated on TEMPORARY_CHAT.USE.
    val showTempChatToggle = (conversationId == null || uiState.isTemporaryChat) &&
        uiState.temporaryChatEnabled

    val fillWidth = uiState.chatHeaderAlignment == ChatHeaderAlignment.FILL
    val contentAlignment = when (uiState.chatHeaderAlignment) {
        ChatHeaderAlignment.LEFT, ChatHeaderAlignment.FILL -> Alignment.CenterStart
        ChatHeaderAlignment.CENTER -> Alignment.Center
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = chatTopBarScrim())
                .consumeFloatingBarTouches()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onOpenDrawer != null) {
                FloatingBarIconButton(
                    icon = Icons.Default.Menu,
                    contentDescription = stringResource(Res.string.cd_open_drawer),
                    onClick = onOpenDrawer,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // The configurable content bubble. The flexible region always reserves the space between
            // the hamburger and the right-pinned controls, so the bubble can hug its content
            // (left/center) or fill the region, and `NONE` simply leaves the region empty.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = contentAlignment,
            ) {
                when (uiState.chatHeaderContent) {
                    ChatHeaderContent.TITLE ->
                        if (conversationId != null && !conversationTitle.isNullOrBlank()) {
                            HeaderTitleChip(
                                title = conversationTitle,
                                conversationKey = conversationId,
                                fillWidth = fillWidth,
                                onCommit = viewModel::renameConversation,
                            )
                        }

                    ChatHeaderContent.MODEL -> {
                        val label = rememberChatModelLabel(
                            selectedEndpoint = uiState.selectedEndpoint,
                            selectedModel = uiState.selectedModel,
                            agents = uiState.agents,
                        )
                        FloatingBarLabelChip(
                            text = label.displayModel ?: stringResource(Res.string.select_model),
                            fillWidth = fillWidth,
                            onClick = viewModel::openModelSheet,
                        )
                    }

                    ChatHeaderContent.NONE -> Unit
                }
            }
            Spacer(modifier = Modifier.width(8.dp))

            if (showTempChatToggle) {
                FloatingBarChip(modifier = Modifier.size(FloatingBarChipSize)) {
                    TempChatToggle(
                        isTemporary = uiState.isTemporaryChat,
                        onToggle = viewModel::toggleTemporaryChat,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Box {
                FloatingBarIconButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.cd_more_options),
                    onClick = { showOverflowMenu = true },
                )
                ChatOverflowMenu(
                    expanded = showOverflowMenu,
                    onDismiss = { showOverflowMenu = false },
                    conversationId = conversationId,
                    conversationTitle = conversationTitle,
                    presetsEnabled = uiState.presetsEnabled,
                    promptsEnabled = uiState.promptsEnabled,
                    multiConvoEnabled = uiState.multiConvoEnabled,
                    sharedLinksEnabled = uiState.sharedLinksEnabled,
                    isComparisonEnabled = uiState.comparisonState.isEnabled,
                    onOpenSearch = viewModel::openSearch,
                    onShowAllMedia = onShowAllMedia,
                    onLoadPreset = onLoadPreset,
                    onSavePreset = onSavePreset,
                    onOpenPromptsLibrary = onOpenPromptsLibrary,
                    onToggleComparison = viewModel::toggleComparison,
                    onShare = viewModel::shareConversation,
                    onRename = onRename,
                    onDuplicate = viewModel::duplicateConversation,
                    onArchive = viewModel::archiveConversation,
                    onDelete = viewModel::showDeleteConfirmation,
                )
            }
        }

        // In-conversation search bar, pinned directly under the floating bar.
        AnimatedVisibility(
            visible = uiState.isSearchOpen,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            InConvoSearchBar(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChanged,
                currentMatchIndex = uiState.currentSearchMatchIndex,
                totalMatches = uiState.searchMatchIndices.size,
                onPreviousMatch = viewModel::previousSearchMatch,
                onNextMatch = viewModel::nextSearchMatch,
                onClose = viewModel::closeSearch,
            )
        }
    }
}

/**
 * The conversation-title bubble. Renders the title (ellipsized) and, on long-press, swaps in an
 * inline editor that commits via [onCommit] (the same [ChatViewModel.renameConversation] path as the
 * overflow Rename dialog). Edit state resets when [conversationKey] changes so switching chats never
 * leaves a stale editor open.
 */
@Composable
private fun HeaderTitleChip(
    title: String,
    conversationKey: String,
    fillWidth: Boolean,
    onCommit: (String) -> Unit,
) {
    var isEditing by remember(conversationKey) { mutableStateOf(false) }

    if (isEditing) {
        FloatingBarContentChip(fillWidth = fillWidth) {
            HeaderTitleEditor(
                initial = title,
                fillWidth = fillWidth,
                onCommit = {
                    isEditing = false
                    onCommit(it)
                },
                onCancel = { isEditing = false },
            )
        }
    } else {
        FloatingBarLabelChip(
            text = title,
            fillWidth = fillWidth,
            onLongClick = { isEditing = true },
            onLongClickLabel = stringResource(Res.string.cd_edit_title),
        )
    }
}

/**
 * Inline single-line title editor. Commit happens ONLY on the explicit IME Done action; any focus
 * loss (tapping the composer, opening the overflow menu, switching conversations, config change) or
 * the Escape key DISCARDS the edit. This makes a rename an explicit, confirmed action — the bar's
 * scrim ([consumeFloatingBarTouches]) swallows background taps, so a commit-on-blur would otherwise
 * persist abandoned, half-typed titles. Done with an unchanged or blank value also discards, which
 * covers the case where the title updated underneath an untouched editor (e.g. async gen_title).
 */
@Composable
private fun HeaderTitleEditor(
    initial: String,
    fillWidth: Boolean,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var settled by remember { mutableStateOf(false) }
    var everFocused by remember { mutableStateOf(false) }

    fun commit() {
        if (settled) return
        settled = true
        val trimmed = value.text.trim()
        if (trimmed.isNotEmpty() && trimmed != initial) onCommit(trimmed) else onCancel()
    }
    fun cancel() {
        if (settled) return
        settled = true
        onCancel()
    }

    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 120.dp, max = 240.dp))
            .padding(horizontal = 16.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                    cancel()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { state ->
                if (state.isFocused) {
                    everFocused = true
                } else if (everFocused) {
                    // Focus left (composer, overflow, chat switch) — discard the unconfirmed edit.
                    cancel()
                }
            },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
