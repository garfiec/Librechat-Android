package com.librechat.android.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Sidebar scaffold that switches between Conversations and Settings modes.
 * Uses [AnimatedContent] for a smooth horizontal slide transition.
 *
 * The Settings footer button now navigates directly to the tabbed settings
 * screen via [onSettingsClick], rather than switching sidebar mode.
 * The mode-switching infrastructure is preserved for potential future use.
 */
@Composable
fun SidebarScaffold(
    viewModel: NavHostViewModel,
    onNewChat: () -> Unit,
    onConversationClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onSettingsCategorySelected: (SettingsCategory) -> Unit,
    onAgentsClick: () -> Unit,
    onFilesClick: () -> Unit,
    onVoiceClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidebarMode by viewModel.sidebarMode.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedSettingsCategory.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = sidebarMode,
        modifier = modifier,
        transitionSpec = {
            if (targetState is SidebarMode.Settings) {
                // Slide in from right when switching to Settings
                slideInHorizontally { fullWidth -> fullWidth } togetherWith
                    slideOutHorizontally { fullWidth -> -fullWidth }
            } else {
                // Slide in from left when switching back to Conversations
                slideInHorizontally { fullWidth -> -fullWidth } togetherWith
                    slideOutHorizontally { fullWidth -> fullWidth }
            }
        },
        label = "SidebarModeTransition",
    ) { mode ->
        when (mode) {
            is SidebarMode.Conversations -> {
                DrawerContent(
                    viewModel = viewModel,
                    onNewChat = onNewChat,
                    onConversationClick = onConversationClick,
                    onSettingsClick = onSettingsClick,
                    onAgentsClick = onAgentsClick,
                    onFilesClick = onFilesClick,
                    onVoiceClick = onVoiceClick,
                )
            }
            is SidebarMode.Settings -> {
                SettingsSidebarContent(
                    selectedCategory = selectedCategory,
                    onBackToConversations = {
                        viewModel.setSidebarMode(SidebarMode.Conversations)
                    },
                    onCategorySelected = { category ->
                        viewModel.selectSettingsCategory(category)
                        onSettingsCategorySelected(category)
                    },
                )
            }
        }
    }
}
