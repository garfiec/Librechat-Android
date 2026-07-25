package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Memory(
    val key: String,
    val value: String,
    /**
     * ISO timestamp of the last write. The schema names this column `updated_at` and defines no
     * creation timestamp at all, so this is the only time a memory row carries.
     */
    @SerialName("updated_at") val updatedAt: String? = null,
    /**
     * Agent this memory is partitioned to, or null for the shared personal pool.
     * Server-side, `tokenLimit`/`totalTokens` usage totals count the shared pool only
     * (entries with a non-null [agentId] are excluded), because the limit applies per
     * partition.
     */
    val agentId: String? = null,
    /**
     * Display name resolved server-side for [agentId], present only when the requester
     * can VIEW that agent. Null for shared-pool entries and for agent-partitioned entries
     * whose agent is no longer visible.
     */
    val agentName: String? = null,
)

/** The `preferences` object of `PATCH /api/memories/preferences`, whose sole key is `memories`. */
@Serializable
data class MemoryPreferences(
    @SerialName("memories") val enabled: Boolean = true,
)
