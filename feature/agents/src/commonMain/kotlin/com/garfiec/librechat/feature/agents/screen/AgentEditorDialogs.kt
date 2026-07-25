package com.garfiec.librechat.feature.agents.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.garfiec.librechat.feature.agents.components.AgentVersionHistory
import com.garfiec.librechat.feature.agents.components.ToolAuthDialog
import com.garfiec.librechat.feature.agents.components.ToolsMarketplaceDialog
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorUiState
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorViewModel
import com.garfiec.librechat.feature.agents.viewmodel.ToolAuthState
import com.garfiec.librechat.feature.agents.viewmodel.delegate.isMarketplaceItemSelected
import com.garfiec.librechat.feature.agents.viewmodel.delegate.marketplaceCatalog
import org.jetbrains.compose.resources.stringResource

/**
 * The editor's modal layer: delete/duplicate confirmations, version history,
 * tool selection, and the Code Interpreter API-key dialog. Each is gated on its
 * own state flag, so this composable renders nothing when no dialog is active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentEditorDialogs(
    uiState: AgentEditorUiState,
    viewModel: AgentEditorViewModel,
    showToolDialog: Boolean,
    onDismissToolDialog: () -> Unit,
) {
    // Delete confirmation dialog
    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text(stringResource(Res.string.delete_agent)) },
            text = { Text(stringResource(Res.string.delete_agent_editor_confirm)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) {
                    Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    // Duplicate confirmation dialog
    if (uiState.showDuplicateConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicateConfirmation,
            title = { Text(stringResource(Res.string.duplicate_agent)) },
            text = { Text(stringResource(Res.string.duplicate_agent_confirm)) },
            confirmButton = {
                TextButton(onClick = viewModel::duplicate) {
                    Text(stringResource(Res.string.duplicate))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDuplicateConfirmation) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    // Version history sheet
    if (uiState.showVersionHistory) {
        AgentVersionHistory(
            versions = uiState.versions,
            onRevert = viewModel::revertToVersion,
            onDismiss = viewModel::dismissVersionHistory,
            isLoading = uiState.isLoadingVersions,
        )
    }

    // Unified tools picker (capabilities + plugin tools + MCP + skills, v0.8.8)
    if (showToolDialog) {
        val catalog = remember(uiState) { uiState.marketplaceCatalog() }
        val selectedKeys = remember(uiState, catalog) {
            catalog.filter { uiState.isMarketplaceItemSelected(it) }.map { it.itemKey }.toSet()
        }
        ToolsMarketplaceDialog(
            catalog = catalog,
            selectedKeys = selectedKeys,
            favoriteKeys = uiState.favoriteToolKeys,
            favoritesSupported = uiState.areToolFavoritesSupported,
            query = uiState.marketplaceQuery,
            filter = uiState.marketplaceFilter,
            onQueryChange = viewModel::onMarketplaceQueryChanged,
            onFilterChange = viewModel::onMarketplaceFilterChanged,
            onToggleItem = viewModel::onMarketplaceItemToggled,
            onToggleFavorite = viewModel::onMarketplaceFavoriteToggled,
            onDismiss = onDismissToolDialog,
        )
    }

    // Code Interpreter API key dialog
    if (uiState.showCodeAuthDialog) {
        val alreadyAuthed = uiState.codeToolAuthState == ToolAuthState.UserProvided
        ToolAuthDialog(
            title = stringResource(Res.string.tool_auth_code_title),
            fieldLabel = stringResource(Res.string.tool_auth_code_field),
            description = stringResource(Res.string.tool_auth_code_description),
            isAlreadyAuthenticated = alreadyAuthed,
            onSubmit = viewModel::submitCodeToolApiKey,
            onRevoke = viewModel::revokeCodeToolApiKey,
            onDismiss = viewModel::dismissCodeToolAuthDialog,
        )
    }
}
