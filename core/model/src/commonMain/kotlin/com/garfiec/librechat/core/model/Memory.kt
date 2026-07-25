package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Memory(
    val key: String,
    val value: String,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
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

@Serializable
data class MemoryPreferences(
    val enabled: Boolean = true,
)
