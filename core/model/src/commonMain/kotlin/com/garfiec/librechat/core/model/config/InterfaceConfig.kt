package com.garfiec.librechat.core.model.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class InterfaceConfig(
    val privacyPolicy: PrivacyPolicyConfig? = null,
    val termsOfService: TermsOfServiceConfig? = null,
    val endpointsMenu: Boolean = true,
    val modelSelect: Boolean = true,
    val parameters: Boolean = true,
    val presets: Boolean = true,
    val sidePanel: Boolean = true,
    val bookmarks: Boolean = true,
    val prompts: JsonElement? = null,
    val agents: JsonElement? = null,
    val multiConvo: Boolean = true,
    val memories: Boolean = true,
    val temporaryChat: Boolean = true,
    val runCode: Boolean = true,
    val webSearch: Boolean = true,
    val fileSearch: Boolean = true,
    val fileCitations: Boolean = true,
    val customWelcome: String? = null,
    val remoteAgents: JsonElement? = null,
    // --- v0.8.6 detection (parse-surface only; no gating UI yet) ---
    /** Skills feature toggle. `bool | { use, create, share, public, defaultActiveOnShare }`.
     *  Kept as raw JSON for forward-compat, mirroring [prompts] / [agents] / [remoteAgents]. */
    val skills: JsonElement? = null,
    /** When true, the server exposes build metadata for an About-screen display. */
    val buildInfo: Boolean? = null,
    /** Whether the web client auto-submits a prompt passed via URL. No mobile counterpart. */
    val autoSubmitFromUrl: Boolean? = null,
    /** Temp-chat retention mode: `"all"` | `"temporary"`. Cosmetic on mobile (no temp-chat surface). */
    val retentionMode: String? = null,
    // --- v0.8.7 detection / gating ---
    /** Whether the context-usage gauge is enabled. Server default is `true`. Gates the
     *  chat-screen context gauge (combined with a `BackendVersion.isCompatibleOrNewer("0.8.7")` check). */
    val contextUsage: Boolean = true,
    /** Whether the gauge surfaces USD cost alongside token usage. Server default is `false`.
     *  Retained for wire fidelity (real `/api/config` key); not read yet — the cost readout
     *  isn't built. */
    val contextCost: Boolean = false,
    /** When `"immediate"`, the server emits a mid-stream `title` SSE frame so the conversation
     *  title can be revealed eagerly; `"final"` (or null) keeps the post-stream reveal. */
    val titleTiming: String? = null,
    /** Tool keys (and `"mcp"` / an MCP server name) pinned to the prompt bar by default.
     *  Parse-surface only — mobile has no pinned-tools prompt-bar concept yet (deferred). */
    val defaultPinnedTools: List<String>? = null,
    /** Shared-link sub-capabilities. `bool | { create, share, public, snapshotFiles }`.
     *  Kept as raw JSON for forward-compat, mirroring [prompts] / [agents] / [skills]. */
    val sharedLinks: JsonElement? = null,
    /** Maximum number of skills shown in the catalog. Parse-surface only (no mobile skills UI). */
    val maxCatalogSkills: Int? = null,
)
