package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

@Serializable
data class CreateMemoryRequest(
    val key: String,
    val value: String,
    /**
     * Partitions the new entry to an agent instead of the shared personal pool.
     * Omitted (null) writes to the shared pool, which is the pre-partition behavior
     * and what older backends do regardless.
     */
    val agentId: String? = null,
)
