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
)
