package com.garfiec.librechat.core.model.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EphemeralAgent(
    val mcp: List<String>? = null,
    @SerialName("web_search") val webSearch: Boolean? = null,
    @SerialName("file_search") val fileSearch: Boolean? = null,
    @SerialName("execute_code") val executeCode: Boolean? = null,
    val artifacts: String? = null,
    /**
     * Equip this run with the memory tools (`set_memory`/`delete_memory`). Only honored when the
     * server has the `memory` agent capability enabled and the caller holds MEMORIES
     * USE+CREATE+UPDATE — the backend drops the tools otherwise.
     */
    val memory: Boolean? = null,
)
