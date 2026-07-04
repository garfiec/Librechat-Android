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
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sidebar scaffold that switches between the Conversations, Projects, and Settings modes with an
 * [AnimatedContent] horizontal slide — a carousel where the sub-modes (Projects/Settings) enter
 * from the right and return to Conversations by sliding back. On Android the system back button
 * pops Projects → Conversations (see [BackHandler]); on platforms without a system back (iOS) the
 * in-mode back arrow does the same.
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
    onOpenProject: (projectId: String, projectName: String) -> Unit = { _, _ -> },
    onOpenProjectsIndex: () -> Unit = {},
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: () -> Unit = {},
    viewModel: NavHostViewModel = koinViewModel(),
) {
    val sidebarMode by viewModel.sidebarMode.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedSettingsCategory.collectAsStateWithLifecycle()

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
                    onConversationClick = onConversationClick,
                    onSettingsClick = onSettingsClick,
                    onAgentsClick = onAgentsClick,
                    onFilesClick = onFilesClick,
                    onSkillsClick = onSkillsClick,
                    onOpenProjects = { viewModel.setSidebarMode(SidebarMode.Projects) },
                    onSwitchAccount = onSwitchAccount,
                    onAddAccount = onAddAccount,
                )
            }
            is SidebarMode.Projects -> {
                ProjectsSidebarContent(
                    onBackToConversations = {
                        viewModel.setSidebarMode(SidebarMode.Conversations)
                    },
                    onOpenProject = onOpenProject,
                    onOpenProjectsIndex = onOpenProjectsIndex,
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
