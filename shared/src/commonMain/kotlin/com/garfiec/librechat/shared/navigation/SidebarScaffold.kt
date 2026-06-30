package com.garfiec.librechat.shared.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sidebar scaffold that switches between Conversations and Settings modes.
 * Uses [AnimatedContent] for a smooth horizontal slide transition.
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
    viewModel: NavHostViewModel = koinViewModel(),
) {
    val sidebarMode by viewModel.sidebarMode.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedSettingsCategory.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = sidebarMode,
        modifier = modifier,
        transitionSpec = {
            if (targetState is SidebarMode.Settings) {
                slideInHorizontally { fullWidth -> fullWidth } togetherWith
                    slideOutHorizontally { fullWidth -> -fullWidth }
            } else {
                slideInHorizontally { fullWidth -> -fullWidth } togetherWith
                    slideOutHorizontally { fullWidth -> fullWidth }
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
