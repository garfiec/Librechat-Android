package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.TokenUsage
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.core.ui.components.ModelParameterContent
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.core.ui.components.PlatformBackHandler
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_back
import com.garfiec.librechat.feature.chat.resources.tool_model_parameters
import com.garfiec.librechat.feature.chat.viewmodel.ChatInputGates
import org.jetbrains.compose.resources.stringResource

/** Duration of the page slide + resize. */
private const val PageSwapMillis = 250

/** Sub-page height floor if the sheet ever measures unbounded (the selector's list needs bounds). */
private val SubPageFallbackHeight = 560.dp

/** Everything the Options (tools/attachment) page renders. See [ChatToolsSheetContent]. */
@Immutable
data class ChatToolsPageParams(
    val enabledTools: Set<String>,
    val onToggleTool: (String) -> Unit,
    val mcpServers: List<McpServerDisplayData>,
    val selectedMcpServerNames: Set<String>,
    val onToggleMcpServer: (String) -> Unit,
    val onAttachFiles: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onPickPhotos: () -> Unit,
    val onAttachFromServer: () -> Unit,
    val selectedModelDisplay: String?,
    val isCodeInterpreterAvailable: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val urlContextEnabled: Boolean = false,
    val runCodeEnabled: Boolean = true,
    val fileSearchEnabled: Boolean = true,
    val memoryEnabled: Boolean = false,
    val mcpServersEnabled: Boolean = true,
    val gates: ChatInputGates = ChatInputGates(),
    val contextUsage: ContextUsage? = null,
    val tokenUsage: TokenUsage? = null,
    val contextUsageEnabled: Boolean = false,
    val contextBarPlacement: ContextBarPlacement = ContextBarPlacement.OPTIONS_SHEET,
    val contextGaugeExpanded: Boolean = false,
    val onContextGaugeExpandedChange: (Boolean) -> Unit = {},
)

/**
 * Everything the ModelSelector page renders. In comparison mode the host points the selection
 * fields at the secondary model, so this page is agnostic to which tab is active.
 */
@Immutable
data class ModelSelectorPageParams(
    val endpointConfigs: Map<String, EndpointConfig>,
    val availableModels: Map<String, List<String>>,
    val agents: List<Agent>,
    val selectedEndpoint: String?,
    val selectedModel: String?,
    val onModelSelect: (endpoint: String, model: String) -> Unit,
    val onSetApiKey: (endpointName: String) -> Unit,
    /** Fired on each surfacing; hosts route it to `ChatViewModel.prepareModelSelector()`. */
    val onSurfaced: () -> Unit = {},
    /** Inline error; required because the Scaffold snackbar draws behind the sheet scrim. */
    val errorMessage: String? = null,
    val onErrorDismiss: () -> Unit = {},
    val serverUrl: String = "",
    val favoriteAgentIds: Set<String> = emptySet(),
    val favoriteModelKeys: Set<String> = emptySet(),
    val onToggleAgentFavorite: ((agentId: String) -> Unit)? = null,
    val onToggleModelFavorite: ((endpoint: String, model: String) -> Unit)? = null,
    val starredDisplay: StarredModelsDisplay = StarredModelsDisplay.OFF,
    val endpointKeyStates: Map<String, KeyState> = emptyMap(),
)

/** Everything the ModelParameters page renders. See [ModelParameterContent]. */
@Immutable
data class ModelParametersPageParams(
    val parameters: ModelParameters,
    val onParametersChange: (ModelParameters) -> Unit,
    val selectedEndpoint: String = "",
    val extendedEffortSupported: Boolean = false,
    /** Underlying provider when the endpoint is "agents"; routes to that provider's param set. */
    val selectedProvider: String? = null,
    val selectedModel: String? = null,
    val onSaveAsPreset: () -> Unit = {},
)

/**
 * The chat options sheet: one [ModalBottomSheet] that swaps between the tools menu and the model
 * selector / parameters, so choosing a model returns to the Options page rather than the chat. The
 * standalone selector (top-bar chip, send-block, dual-pane) lives in `ChatModelSheets.kt`. Opened
 * via [ChatOptionsSheetController].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatOptionsBottomSheet(
    page: ChatOptionsPage,
    onPageChange: (ChatOptionsPage) -> Unit,
    onDismiss: () -> Unit,
    tools: ChatToolsPageParams,
    selector: ModelSelectorPageParams,
    parameters: ModelParametersPageParams,
    modifier: Modifier = Modifier,
) {
    // Held here, not in ChatToolsSheetContent: AnimatedContent drops the Options page from
    // composition on a sub-page, so state remembered there would reset. Saveable to survive the
    // host teardown the page survives (the "Set API Key" round trip, config change).
    var mcpExpanded by rememberSaveable { mutableStateOf(false) }
    val optionsScrollState = rememberScrollState()
    // Or the Options page — the common path — would open at half height.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // On every arrival at the selector. Not latched: both side effects are idempotent (the agent
    // retry self-guards; the favourites refetch conflates by equality), matching the pre-refactor
    // "every open self-heals" behaviour.
    LaunchedEffect(page) {
        if (page == ChatOptionsPage.ModelSelector) {
            selector.onSurfaced()
        }
    }

    // Clear the selector's inline error when leaving that page, so its snackbar (drawn behind the
    // scrim) doesn't re-surface after close. Scoped to the selector: an Options-page error must
    // reach the snackbar untouched. onErrorDismiss no-ops when there's no error.
    val leaveSelectorError: () -> Unit = {
        if (page == ChatOptionsPage.ModelSelector) selector.onErrorDismiss()
    }
    val goTo: (ChatOptionsPage) -> Unit = { target ->
        leaveSelectorError()
        onPageChange(target)
    }
    val dismiss: () -> Unit = {
        leaveSelectorError()
        onDismiss()
    }
    val back: () -> Unit = {
        if (page == ChatOptionsPage.Options) dismiss() else goTo(ChatOptionsPage.Options)
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = { LowProfileDragHandle() },
    ) {
        // Inside the sheet: on Android the content is its own dialog window, so an outer handler
        // never sees its back events. Takes precedence over Material3's back-to-dismiss so a
        // sub-page pops to Options. Never fires on iOS — hence the header back arrows.
        PlatformBackHandler(enabled = page != ChatOptionsPage.Options, onBack = back)

        BoxWithConstraints {
            // Sub-pages need a bounded height for the selector's weight(1f) LazyColumn; Options and
            // Parameters wrap their content and SizeTransform animates between the heights.
            val subPageHeight = if (constraints.hasBoundedHeight) maxHeight else SubPageFallbackHeight

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    // Forward slides in from the right, back from the left, reading as depth.
                    val direction = if (initialState == ChatOptionsPage.Options) 1 else -1
                    val spec = tween<Float>(PageSwapMillis)
                    (
                        slideInHorizontally(tween(PageSwapMillis)) { width -> direction * width } +
                            fadeIn(spec)
                        ).togetherWith(
                        slideOutHorizontally(tween(PageSwapMillis)) { width -> -direction * width } +
                            fadeOut(spec),
                    ).using(SizeTransform { _, _ -> tween(PageSwapMillis) })
                },
                label = "chatOptionsPage",
            ) { currentPage ->
                when (currentPage) {
                    ChatOptionsPage.Options -> ChatToolsSheetContent(
                        enabledTools = tools.enabledTools,
                        onToggleTool = tools.onToggleTool,
                        mcpServers = tools.mcpServers,
                        selectedMcpServerNames = tools.selectedMcpServerNames,
                        onToggleMcpServer = tools.onToggleMcpServer,
                        onAttachFiles = tools.onAttachFiles,
                        onTakePhoto = tools.onTakePhoto,
                        onPickPhotos = tools.onPickPhotos,
                        onAttachFromServer = tools.onAttachFromServer,
                        onOpenModelParameters = { goTo(ChatOptionsPage.ModelParameters) },
                        onOpenModelSelector = { goTo(ChatOptionsPage.ModelSelector) },
                        selectedModelDisplay = tools.selectedModelDisplay,
                        onDismiss = dismiss,
                        isCodeInterpreterAvailable = tools.isCodeInterpreterAvailable,
                        webSearchEnabled = tools.webSearchEnabled,
                        urlContextEnabled = tools.urlContextEnabled,
                        runCodeEnabled = tools.runCodeEnabled,
                        fileSearchEnabled = tools.fileSearchEnabled,
                        memoryEnabled = tools.memoryEnabled,
                        mcpServersEnabled = tools.mcpServersEnabled,
                        gates = tools.gates,
                        contextUsage = tools.contextUsage,
                        tokenUsage = tools.tokenUsage,
                        contextUsageEnabled = tools.contextUsageEnabled,
                        contextBarPlacement = tools.contextBarPlacement,
                        contextGaugeExpanded = tools.contextGaugeExpanded,
                        onContextGaugeExpandedChange = tools.onContextGaugeExpandedChange,
                        mcpExpanded = mcpExpanded,
                        onMcpExpandedChange = { mcpExpanded = it },
                        scrollState = optionsScrollState,
                    )

                    ChatOptionsPage.ModelSelector -> ModelSelectorSheetContent(
                        endpointConfigs = selector.endpointConfigs,
                        availableModels = selector.availableModels,
                        agents = selector.agents,
                        selectedEndpoint = selector.selectedEndpoint,
                        selectedModel = selector.selectedModel,
                        onModelSelect = { endpoint, model ->
                            selector.onModelSelect(endpoint, model)
                            goTo(ChatOptionsPage.Options)
                        },
                        onSetApiKey = selector.onSetApiKey,
                        modifier = Modifier.height(subPageHeight),
                        serverUrl = selector.serverUrl,
                        errorMessage = selector.errorMessage,
                        onErrorDismiss = selector.onErrorDismiss,
                        favoriteAgentIds = selector.favoriteAgentIds,
                        favoriteModelKeys = selector.favoriteModelKeys,
                        onToggleAgentFavorite = selector.onToggleAgentFavorite,
                        onToggleModelFavorite = selector.onToggleModelFavorite,
                        starredDisplay = selector.starredDisplay,
                        endpointKeyStates = selector.endpointKeyStates,
                        header = { SheetPageBackRow(title = null, onBack = back) },
                    )

                    ChatOptionsPage.ModelParameters -> Column {
                        SheetPageBackRow(
                            title = stringResource(Res.string.tool_model_parameters),
                            onBack = back,
                        )
                        ModelParameterContent(
                            parameters = parameters.parameters,
                            onParametersChange = parameters.onParametersChange,
                            selectedEndpoint = parameters.selectedEndpoint,
                            extendedEffortSupported = parameters.extendedEffortSupported,
                            selectedProvider = parameters.selectedProvider,
                            selectedModel = parameters.selectedModel,
                            onSaveAsPreset = parameters.onSaveAsPreset,
                            // The back row above replaces the content's own title.
                            showHeader = false,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .navigationBarsPadding()
                                .imePadding(),
                        )
                    }
                }
            }
        }
    }
}

/** A sub-page's back affordance — the only way back on iOS, where `PlatformBackHandler` is inert. */
@Composable
private fun SheetPageBackRow(
    title: String?,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.cd_back),
            )
        }
        if (title != null) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
