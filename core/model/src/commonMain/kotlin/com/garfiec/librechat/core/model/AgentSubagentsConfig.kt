package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for spawning subagents (isolated-context child agents) from an agent.
 * When [enabled] is true, the agent gets a subagent-spawn tool that can delegate work
 * to itself (when [allowSelf] is true) and/or the agents listed in [agentIds].
 *
 * Mirrors upstream `AgentSubagentsConfig`
 * (`packages/data-provider/src/types/assistants.ts`). Forward-compat only: mobile has
 * no subagent editor yet, so this round-trips the server's value without surfacing it.
 */
@Serializable
data class AgentSubagentsConfig(
    val enabled: Boolean? = null,
    /** When true (default server-side), the agent may spawn itself in an isolated context. */
    val allowSelf: Boolean? = null,
    /** Specific agents that may be spawned as subagents. */
    @SerialName("agent_ids") val agentIds: List<String>? = null,
)
