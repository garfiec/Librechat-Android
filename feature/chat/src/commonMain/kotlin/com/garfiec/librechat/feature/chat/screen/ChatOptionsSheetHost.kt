package com.garfiec.librechat.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.feature.chat.components.ChatOptionsBottomSheet
import com.garfiec.librechat.feature.chat.components.ChatOptionsSheetController
import com.garfiec.librechat.feature.chat.components.ChatToolsPageParams
import com.garfiec.librechat.feature.chat.components.ModelParametersPageParams
import com.garfiec.librechat.feature.chat.components.ModelSelectorPageParams
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel

/**
 * Hosts the chat options sheet (the "+" menu, with selector/parameters as pages) for both
 * platforms, opened through [controller]. In commonMain so the Android compile type-checks the
 * wiring iosMain shares. Callers pass their own attach actions, which are platform plumbing.
 */
@Composable
internal fun ChatOptionsSheetHost(
    controller: ChatOptionsSheetController,
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    /**
     * True when comparison mode is on and its second tab is active, i.e. the composer is editing
     * the secondary model. Points the selector page at that model instead of the primary.
     */
    isSecondaryTab: Boolean,
    /** Model label for the tools page's Model row; the caller resolves it for the active tab. */
    selectedModelDisplay: String?,
    onAttachFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
    onAttachFromServer: () -> Unit,
    onNavigateToProviderKeys: (endpointName: String?) -> Unit,
    onShowSavePresetDialog: () -> Unit,
) {
    // A send-block auto-opens the standalone selector; close this one so they can't stack. Gate on
    // the flag (not just its change) to also catch it being already true when this sheet opens.
    LaunchedEffect(uiState.showModelSheet) {
        if (uiState.showModelSheet) controller.close()
    }
    val page = controller.openPage
    if (page == null || uiState.showModelSheet) return

    // The agent behind the current selection, if the endpoint is "agents" — routes the parameters
    // page to the agent's underlying provider rather than the "agents" pseudo-endpoint.
    val activeAgent = remember(uiState.agents, uiState.selectedModel, uiState.selectedEndpoint) {
        if (uiState.selectedEndpoint == EndpointConstants.AGENTS) {
            uiState.agents.find { it.id == uiState.selectedModel }
        } else {
            null
        }
    }

    ChatOptionsBottomSheet(
        page = page,
        onPageChange = controller::open,
        onDismiss = controller::close,
        tools = ChatToolsPageParams(
            enabledTools = uiState.effectiveEnabledTools,
            onToggleTool = viewModel::toggleTool,
            mcpServers = uiState.mcpServers,
            selectedMcpServerNames = uiState.selectedMcpServerNames,
            onToggleMcpServer = viewModel::toggleMcpServer,
            onAttachFiles = onAttachFiles,
            onTakePhoto = onTakePhoto,
            onPickPhotos = onPickPhotos,
            onAttachFromServer = onAttachFromServer,
            selectedModelDisplay = selectedModelDisplay,
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
        ),
        // Point the selector page at the active tab's model, so the sheet needs no comparison branch.
        selector = ModelSelectorPageParams(
            endpointConfigs = uiState.endpointConfigs,
            availableModels = uiState.availableModels,
            agents = uiState.agents,
            selectedEndpoint = if (isSecondaryTab) {
                uiState.comparisonState.secondaryEndpoint
            } else {
                uiState.selectedEndpoint
            },
            selectedModel = if (isSecondaryTab) {
                uiState.comparisonState.secondaryModel
            } else {
                uiState.selectedModel
            },
            // Neither branch clears the error: the sheet clears the selector's inline banner on
            // page-leave itself (see ChatOptionsBottomSheet), the only error that can flash.
            onModelSelect = if (isSecondaryTab) {
                viewModel::setSecondaryModel
            } else {
                viewModel::onModelSelected
            },
            onSetApiKey = { name -> onNavigateToProviderKeys(name) },
            onSurfaced = viewModel::prepareModelSelector,
            // Inline because the Scaffold snackbar draws behind the sheet scrim.
            errorMessage = uiState.error,
            onErrorDismiss = viewModel::dismissError,
            serverUrl = uiState.serverUrl,
            favoriteAgentIds = uiState.favoriteAgentIds,
            favoriteModelKeys = uiState.favoriteModelKeys,
            onToggleAgentFavorite = viewModel::toggleAgentFavorite,
            onToggleModelFavorite = viewModel::toggleModelFavorite,
            starredDisplay = uiState.starredModelsDisplay,
            endpointKeyStates = uiState.endpointKeyStates,
        ),
        // Always the primary model, even on the secondary tab: ComparisonState carries no per-pane
        // parameters, so there is nothing else to point this at (pre-existing; porting web's
        // per-addedConvo parameters would be the real fix).
        parameters = ModelParametersPageParams(
            parameters = uiState.modelParameters,
            onParametersChange = viewModel::updateModelParameters,
            selectedEndpoint = uiState.selectedEndpoint,
            extendedEffortSupported = uiState.extendedEffortSupported,
            selectedProvider = activeAgent?.provider,
            selectedModel = activeAgent?.model ?: uiState.selectedModel,
            onSaveAsPreset = {
                controller.close()
                onShowSavePresetDialog()
            },
        ),
    )
}
