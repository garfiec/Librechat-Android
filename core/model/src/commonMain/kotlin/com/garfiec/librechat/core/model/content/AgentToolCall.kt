package com.garfiec.librechat.core.model.content

import com.garfiec.librechat.core.model.ToolCallType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AgentToolCall(
    val type: ToolCallType? = null,
    val name: String? = null,
    val args: JsonElement? = null,
    val id: String? = null,
    val output: String? = null,
    val auth: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val function: FunctionCall? = null,
    /**
     * For a `subagent` tool_call: the child agent's full run trace (reasoning /
     * tool calls / final text) harvested onto the parent at message-save time
     * (server `finalizeSubagentContent`, v0.8.6). Present only on reload — live
     * progress arrives via `on_subagent_update` SSE events. Lets the trace
     * survive a refresh, mirroring the web client's persisted `subagent_content`.
     */
    @SerialName("subagent_content") val subagentContent: List<MessageContentPart>? = null,
)
