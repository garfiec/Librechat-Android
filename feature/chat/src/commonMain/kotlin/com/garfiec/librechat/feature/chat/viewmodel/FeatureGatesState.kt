package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable

/**
 * Feature availability gates loaded from the server's `interface.*` config AND role
 * permissions (effective value = flag AND permission, matching the web client). Default
 * permissive (true) until both the RoleRepository and the interface config emit; fails open
 * when a flag is absent (older backends). Written only by [ChatViewModel]'s config load.
 */
@Immutable
data class FeatureGatesState(
    val promptsEnabled: Boolean = true,
    val promptsCreateEnabled: Boolean = true,
    val agentsEnabled: Boolean = true,
    val agentsCreateEnabled: Boolean = true,
    val mcpServersEnabled: Boolean = true,
    val multiConvoEnabled: Boolean = true,
    val temporaryChatEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val runCodeEnabled: Boolean = true,
    val fileSearchEnabled: Boolean = true,
    val bookmarksEnabled: Boolean = true,
    /**
     * Interface-only gates (no corresponding role permission on web). Driven solely by
     * the server's `interface.*` config: presets on `interface.presets && interface.modelSelect`,
     * model select on `interface.modelSelect`, parameters on `interface.parameters`.
     */
    val presetsEnabled: Boolean = true,
    val modelSelectEnabled: Boolean = true,
    val parametersEnabled: Boolean = true,
    /** `interface.defaultPinnedTools` (v0.8.7): tool keys the server pins to the prompt bar.
     *  Raw, as sent; mapped/filtered to renderable chips by [ChatUiState.pinnedToolChips]. */
    val pinnedTools: List<String> = emptyList(),
    /**
     * Context-usage gauge gate (v0.8.7). = `interface.contextUsage` AND backend ≥ 0.8.7.
     * Fails closed on older/unknown servers (the gauge has no data source there).
     */
    val contextUsageEnabled: Boolean = false,
)

/**
 * Feature gates that flow into the composer ([ChatInput] → [ChatToolsSheetContent]),
 * bundled so they thread as one value across Android and iOS. Each defaults to true
 * (shown) so a default-constructed bundle is fully permissive. Built by
 * [ChatUiState.chatInputGates]; see [ChatUiState.modelSelectEnabled] /
 * [ChatUiState.parametersEnabled] / [ChatUiState.showEphemeralTools] /
 * [ChatUiState.fileUploadEnabled] for the individual gating rules.
 */
@Immutable
data class ChatInputGates(
    val modelSelectEnabled: Boolean = true,
    val parametersEnabled: Boolean = true,
    val showEphemeralTools: Boolean = true,
    val fileUploadEnabled: Boolean = true,
)
