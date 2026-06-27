package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_more_options
import com.garfiec.librechat.feature.chat.resources.cd_open_drawer
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * The chat screen's floating top bar, shared by Android and iOS. Chips (hamburger, conversation
 * title, optional temp-chat toggle, options) are drawn over a top-down [chatTopBarScrim] so chat
 * content scrolls up *behind* a gently dimmed status-bar region rather than being capped by an
 * opaque app bar — a ChatGPT/Telegram-style header. The in-conversation search bar is pinned
 * directly beneath the chips so the overlay measures as one unit.
 *
 * Most actions are wired straight to [viewModel]; only the triggers whose dialog hosting differs by
 * platform (preset load/save, rename) and the navigation callbacks are passed in. The header
 * intentionally has no model selector — model/params stay reachable from the composer "+" menu, a
 * deliberate mobile decluttering choice that diverges from web's header model selector.
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

            // Conversation title in its own chip, left-aligned next to the hamburger. The flexible
            // region also right-pins the options control. The chip hugs its content but is bounded
            // by the region, so long titles ellipsize at the full available width.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (conversationId != null && !conversationTitle.isNullOrBlank()) {
                    FloatingBarChip(modifier = Modifier.height(FloatingBarChipSize)) {
                        Box(
                            modifier = Modifier.fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = conversationTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
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
