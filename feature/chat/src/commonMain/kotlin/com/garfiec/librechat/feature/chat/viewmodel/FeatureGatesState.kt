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
    /**
     * Composer memory toggle. = MEMORIES USE+CREATE+UPDATE (the write set the inline
     * `set_memory`/`delete_memory` tools need) AND the agents endpoint's `memory` capability
     * AND the user not having opted out via `personalization.memories`. Mirrors web's
     * `useHasMemoryAccess` + `useAgentCapabilities().memoryEnabled` + opt-out check.
     */
    val memoryEnabled: Boolean = false,
    /**
     * Mid-run steering (v0.8.8): whether `POST /api/agents/chat/steer` exists on this server.
     *
     * Fails closed, and unlike the HITL pause this one *can* fail closed safely. A pause is
     * self-proving — the server pushed it, and hiding the card would strand the run — whereas
     * steering must be offered before any server has said anything about it. Closed here means
     * the composer keeps queueing mid-run, which is what mobile did before steering existed and
     * works against every supported server.
     *
     * Consequence of the date-gate's coverage window: a server built from an upstream commit
     * newer than the app's pin resolves to a null `DetectedBackend`, so steering hides there
     * until the next sync. Documented in VERSION_GATES.md.
     */
    val steeringSupported: Boolean = false,
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
