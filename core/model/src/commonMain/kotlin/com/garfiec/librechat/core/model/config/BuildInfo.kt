package com.garfiec.librechat.core.model.config

import kotlinx.serialization.Serializable

/**
 * Server build metadata surfaced in `/api/config` (v0.8.6+), gated by `interface.buildInfo`.
 * All fields nullable — a server may report none. Intended for a future Settings > About row.
 *
 * Mirrors upstream `TStartupConfig.buildInfo` (`packages/data-provider/src/config.ts`).
 */
@Serializable
data class BuildInfo(
    val commit: String? = null,
    val commitShort: String? = null,
    val branch: String? = null,
    val buildDate: String? = null,
)
