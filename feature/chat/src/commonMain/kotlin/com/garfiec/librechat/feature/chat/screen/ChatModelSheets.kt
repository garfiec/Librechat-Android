package com.garfiec.librechat.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.garfiec.librechat.feature.chat.components.ModelSelectorSheet
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel

/*
 * The standalone model-selector sheets, shared by the Android and iOS chat screens. Unlike the
 * selector page in `ChatOptionsBottomSheet`, these open straight to the list and dismiss back to
 * the chat on selection. Both surfaces share the list body, ModelSelectorSheetContent.
 */

/**
 * The primary standalone selector, driven by `uiState.showModelSheet` (top-bar chip, dual-pane
 * primary, send-block auto-open, which supplies [sendBlockMessage]). Renders unconditionally.
 */
@Composable
internal fun PrimaryModelSelectorSheet(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    sendBlockMessage: String?,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    ModelSelectorSheet(
        endpointConfigs = uiState.endpointConfigs,
        availableModels = uiState.availableModels,
        agents = uiState.agents,
        selectedEndpoint = uiState.selectedEndpoint,
        selectedModel = uiState.selectedModel,
        onModelSelect = { endpoint, model ->
            viewModel.onModelSelected(endpoint, model)
            // Clear a pending snackbar for the same error so it doesn't flash behind the close.
            viewModel.dismissError()
            viewModel.dismissSendBlockReason()
            viewModel.dismissModelSheet()
        },
        onDismiss = {
            viewModel.dismissError()
            viewModel.dismissSendBlockReason()
            viewModel.dismissModelSheet()
        },
        serverUrl = uiState.serverUrl,
        // Send-block reasons take precedence: when set, the sheet was auto-opened
        // to help the user resolve the block, so surface that context inline.
        errorMessage = sendBlockMessage ?: uiState.error,
        onErrorDismiss = {
            viewModel.dismissSendBlockReason()
            viewModel.dismissError()
        },
        favoriteAgentIds = uiState.favoriteAgentIds,
        favoriteModelKeys = uiState.favoriteModelKeys,
        onToggleAgentFavorite = viewModel::toggleAgentFavorite,
        onToggleModelFavorite = viewModel::toggleModelFavorite,
        starredDisplay = uiState.starredModelsDisplay,
        endpointKeyStates = uiState.endpointKeyStates,
        onSetApiKey = { name -> onNavigateToProviderKeys(name) },
    )
}

/**
 * The secondary selector for comparison mode's second model (dual-pane button only; the composer's
 * "+" edits it through the options sheet). Renders unconditionally.
 */
@Composable
internal fun SecondaryModelSelectorSheet(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
) {
    // Route through prepareModelSelector like every selector path, so a failed cold-start agent
    // load can self-heal here too (see its KDoc).
    LaunchedEffect(Unit) { viewModel.prepareModelSelector() }

    ModelSelectorSheet(
        endpointConfigs = uiState.endpointConfigs,
        availableModels = uiState.availableModels,
        agents = uiState.agents,
        selectedEndpoint = uiState.comparisonState.secondaryEndpoint,
        selectedModel = uiState.comparisonState.secondaryModel,
        onModelSelect = { endpoint, model ->
            viewModel.setSecondaryModel(endpoint, model)
            onDismiss()
        },
        onDismiss = onDismiss,
        serverUrl = uiState.serverUrl,
        favoriteAgentIds = uiState.favoriteAgentIds,
        favoriteModelKeys = uiState.favoriteModelKeys,
        onToggleAgentFavorite = viewModel::toggleAgentFavorite,
        onToggleModelFavorite = viewModel::toggleModelFavorite,
        starredDisplay = uiState.starredModelsDisplay,
        endpointKeyStates = uiState.endpointKeyStates,
        onSetApiKey = { name -> onNavigateToProviderKeys(name) },
    )
}
