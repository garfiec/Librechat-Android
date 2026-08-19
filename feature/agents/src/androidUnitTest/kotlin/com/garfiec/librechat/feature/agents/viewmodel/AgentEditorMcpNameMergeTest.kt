package com.garfiec.librechat.feature.agents.viewmodel

import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.mcp.McpTool
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The MCP tool list and the agent are fetched by independent, concurrent requests, so either can
 * land first. Both orders have to end at the RAW configured server name in `selectedMcpTools` —
 * that is what every display and match surface speaks.
 */
class AgentEditorMcpNameMergeTest {

    private val servers = listOf(
        McpTool(name = "list_events", serverName = "Google Workspace"),
        McpTool(name = "send_mail", serverName = "Google Workspace"),
    )

    private val agent = Agent(
        id = "agent-1",
        // The key stores the NORMALIZED name; the servers list advertises "Google Workspace".
        tools = listOf("sys__server__sys_mcp_Google_Workspace"),
    )

    @Test
    fun `mcp tools first - applyAgentData resolves the raw name directly`() {
        val state = AgentEditorUiState(mcpTools = servers).applyAgentData(agent)

        assertThat(state.selectedMcpTools).containsExactly("Google Workspace")
    }

    @Test
    fun `agent first - the raw name is resolved when the mcp tools land`() {
        val afterAgent = AgentEditorUiState().applyAgentData(agent)
        // Nothing to resolve against yet, so the normalized name is kept verbatim.
        assertThat(afterAgent.selectedMcpTools).containsExactly("Google_Workspace")

        val merged = afterAgent.copy(mcpTools = servers).remergeMcpServerNames(agent.tools)

        assertThat(merged.selectedMcpTools).containsExactly("Google Workspace")
    }

    @Test
    fun `remerge leaves a selection the user made in the meantime alone`() {
        val state = AgentEditorUiState()
            .applyAgentData(agent)
            .let { it.copy(mcpTools = servers, selectedMcpTools = it.selectedMcpTools + "list_events") }

        val merged = state.remergeMcpServerNames(agent.tools)

        assertThat(merged.selectedMcpTools).containsExactly("Google Workspace", "list_events")
    }

    @Test
    fun `remerge is a no-op when the server list is unknown or the name already resolves`() {
        val unresolved = AgentEditorUiState().applyAgentData(agent)
        assertThat(unresolved.remergeMcpServerNames(agent.tools)).isEqualTo(unresolved)

        val resolved = AgentEditorUiState(mcpTools = servers).applyAgentData(agent)
        assertThat(resolved.remergeMcpServerNames(agent.tools)).isEqualTo(resolved)
    }

    @Test
    fun `an unknown server name round-trips unchanged`() {
        val orphan = Agent(id = "agent-2", tools = listOf("sys__server__sys_mcp_retired_server"))
        val state = AgentEditorUiState(mcpTools = servers).applyAgentData(orphan)

        assertThat(state.selectedMcpTools).containsExactly("retired_server")
        assertThat(state.remergeMcpServerNames(orphan.tools)).isEqualTo(state)
    }
}
