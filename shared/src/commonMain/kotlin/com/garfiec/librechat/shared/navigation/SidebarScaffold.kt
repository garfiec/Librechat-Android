package com.garfiec.librechat.shared.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.ui.components.PlatformBackHandler
import com.garfiec.librechat.feature.conversations.drawer.DrawerContent
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sidebar scaffold that switches between the Conversations and Settings modes with an
 * [AnimatedContent] horizontal slide — a carousel where the Settings sub-mode enters from the right
 * and returns to Conversations by sliding back. On Android the system back button pops Settings →
 * Conversations (see [PlatformBackHandler]); on platforms without a system back (iOS) the in-mode
 * back arrow does the same. Projects live inline in the Conversations drawer (a segmented toggle),
 * not as a carousel mode.
 */
@Composable
fun SidebarScaffold(
    onNewChat: () -> Unit,
    onConversationClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onSettingsCategorySelect: (SettingsCategory) -> Unit,
    onAgentsClick: () -> Unit,
    onFilesClick: () -> Unit,
    onSkillsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenProjectsIndex: () -> Unit = {},
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: () -> Unit = {},
    // Deleting the open conversation navigates the pane off it; see DrawerContent's param for the
    // onNewChat distinction. Defaults to [onNewChat].
    onActiveConversationDelete: () -> Unit = onNewChat,
    viewModel: NavHostViewModel = koinViewModel(),
) {
    val sidebarMode by viewModel.sidebarMode.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedSettingsCategory.collectAsStateWithLifecycle()
    // Account list + switching stay in the nav shell; the drawer only renders the footer chip/sheet,
    // so the list and account callbacks are hoisted down into DrawerContent (which owns only its own
    // DrawerViewModel now).
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    // System back pops any sub-mode (Projects/Settings) back to Conversations, as if it were on the
    // back stack. A no-op on platforms without a system back (iOS), where the in-mode back arrow is
    // used instead.
    PlatformBackHandler(enabled = sidebarMode !is SidebarMode.Conversations) {
        viewModel.setSidebarMode(SidebarMode.Conversations)
    }

    AnimatedContent(
        targetState = sidebarMode,
        modifier = modifier,
        transitionSpec = {
            // Returning to Conversations slides back (enter from left, exit right); entering a
            // sub-mode (Projects/Settings) slides forward (enter from right, exit left).
            if (targetState is SidebarMode.Conversations) {
                slideInHorizontally { fullWidth -> -fullWidth } togetherWith
                    slideOutHorizontally { fullWidth -> fullWidth }
            } else {
                slideInHorizontally { fullWidth -> fullWidth } togetherWith
                    slideOutHorizontally { fullWidth -> -fullWidth }
            }
        },
        label = "SidebarModeTransition",
    ) { mode ->
        when (mode) {
            is SidebarMode.Conversations -> {
                DrawerContent(
                    onNewChat = onNewChat,
                    onActiveConversationDelete = onActiveConversationDelete,
                    onConversationClick = onConversationClick,
                    onSettingsClick = onSettingsClick,
                    onAgentsClick = onAgentsClick,
                    onFilesClick = onFilesClick,
                    onSkillsClick = onSkillsClick,
                    accounts = accounts,
                    onOpenProjectsIndex = onOpenProjectsIndex,
                    onSwitchAccount = onSwitchAccount,
                    onAddAccount = onAddAccount,
                    // Swipe round-robins in place; the sheet's remove asks the nav shell directly.
                    onSwitchAccountInPlace = viewModel::switchAccount,
                    onRemoveAccount = viewModel::removeAccount,
                )
            }
            is SidebarMode.Settings -> {
                SettingsSidebarContent(
                    selectedCategory = selectedCategory,
                    onBackToConversations = {
                        viewModel.setSidebarMode(SidebarMode.Conversations)
                    },
                    onCategorySelect = { category ->
                        viewModel.selectSettingsCategory(category)
                        onSettingsCategorySelect(category)
                    },
                )
            }
        }
    }
}
