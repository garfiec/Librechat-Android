package com.garfiec.librechat.feature.agents.viewmodel.delegate

import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.model.SkillSummary
import com.garfiec.librechat.core.model.mcp.McpTool
import com.garfiec.librechat.feature.agents.AgentToolDisplayData
import com.garfiec.librechat.feature.agents.components.model.MarketplaceKind
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarketplaceCatalogTest {

    private fun state() = AgentEditorUiState(
        isCodeInterpreterAvailable = true,
        isWebSearchAvailable = true,
        isSkillsAvailable = true,
        availableTools = listOf(
            AgentToolDisplayData(
                toolId = "wolfram",
                name = "Wolfram",
                description = "Computational knowledge",
                icon = null,
                isAvailable = true,
            ),
        ),
        mcpTools = listOf(
            McpTool(name = "create_issue", description = "Files a ticket", serverName = "jira"),
            McpTool(name = "search", description = "Finds a ticket", serverName = "jira"),
        ),
        availableSkills = listOf(
            SkillSummary(id = "skill-1", name = "code-review", description = "Reviews a diff"),
        ),
    )

    @Test
    fun `catalog mixes every kind into one list`() {
        val catalog = state().marketplaceCatalog()

        assertThat(catalog.map { it.kind }.toSet()).containsExactly(
            MarketplaceKind.BUILTIN,
            MarketplaceKind.TOOL,
            MarketplaceKind.MCP,
            MarketplaceKind.SKILL,
        )
    }

    @Test
    fun `capabilities the server does not offer are not listed`() {
        val catalog = state()
            .copy(isCodeInterpreterAvailable = false, isWebSearchAvailable = false)
            .marketplaceCatalog()

        val builtinIds = catalog.filter { it.kind == MarketplaceKind.BUILTIN }.map { it.id }
        assertThat(builtinIds).doesNotContain(ToolConstants.EXECUTE_CODE)
        assertThat(builtinIds).doesNotContain(ToolConstants.WEB_SEARCH)
        assertThat(builtinIds).contains(ToolConstants.FILE_SEARCH)
    }

    @Test
    fun `every MCP tool on one server pins the same favorite`() {
        val mcp = state().marketplaceCatalog().filter { it.kind == MarketplaceKind.MCP }

        // The star means "pin this server", so two tools from `jira` must not produce two
        // different favorites — otherwise the same control means different things per row.
        assertThat(mcp.map { it.favoriteKey }.toSet()).containsExactly("mcp:jira")
        assertThat(mcp.map { it.itemKey }.toSet()).hasSize(2)
    }

    @Test
    fun `search matches a built-in by its resolved label`() {
        val catalog = state().marketplaceCatalog()
        val labels = mapOf("builtin:${ToolConstants.WEB_SEARCH}" to "Web Search")

        val hits = filterMarketplace(catalog, "web sea", MarketplaceFilter.ALL, emptySet(), labels)

        assertThat(hits.map { it.id }).containsExactly(ToolConstants.WEB_SEARCH)
    }

    @Test
    fun `pinned items float above their unpinned siblings`() {
        val catalog = state().marketplaceCatalog()

        val tools = filterMarketplace(
            catalog = catalog,
            query = "",
            filter = MarketplaceFilter.MCP,
            favoriteKeys = setOf("mcp:jira"),
        )

        // Both jira tools are pinned by the same key, so ordering falls to the name tiebreak.
        assertThat(tools.map { it.id }).containsExactly("create_issue", "search").inOrder()
    }

    @Test
    fun `favorites filter keeps only pinned rows`() {
        val catalog = state().marketplaceCatalog()

        val pinned = filterMarketplace(
            catalog = catalog,
            query = "",
            filter = MarketplaceFilter.FAVORITES,
            favoriteKeys = setOf("tool:wolfram"),
        )

        assertThat(pinned.map { it.id }).containsExactly("wolfram")
    }

    // ── capability gating (v0.8.8-rc1, web catalog.ts parity) ──────

    private fun askPlugin() = AgentToolDisplayData(
        toolId = ToolConstants.ASK_USER_QUESTION,
        name = "Ask User",
        description = "Pauses to ask",
        icon = null,
        isAvailable = true,
    )

    @Test
    fun `ask_user_question surfaces as a builtin-style row, not a plugin`() {
        val catalog = state()
            .copy(availableTools = state().availableTools + askPlugin())
            .marketplaceCatalog()

        val builtins = catalog.filter { it.kind == MarketplaceKind.BUILTIN }.map { it.id }
        val tools = catalog.filter { it.kind == MarketplaceKind.TOOL }.map { it.id }
        assertThat(builtins).contains(ToolConstants.ASK_USER_QUESTION)
        assertThat(tools).doesNotContain(ToolConstants.ASK_USER_QUESTION)
    }

    @Test
    fun `ask_user_question is hidden when its capability is disabled`() {
        // The user-facing bug this pins: the server drops the tool at runtime when the
        // capability is off, so offering the row produced agents whose tool "does not exist".
        val catalog = state()
            .copy(
                availableTools = state().availableTools + askPlugin(),
                isAskUserQuestionAvailable = false,
            )
            .marketplaceCatalog()

        assertThat(catalog.map { it.id }).doesNotContain(ToolConstants.ASK_USER_QUESTION)
    }

    @Test
    fun `ask_user_question is hidden when the server does not list the plugin`() {
        // Plugin presence is the pre-feature-server gate: capability lists that predate the
        // feature fail open, but /api/agents/tools never lists a tool the server lacks.
        val catalog = state().marketplaceCatalog()

        assertThat(catalog.map { it.id }).doesNotContain(ToolConstants.ASK_USER_QUESTION)
    }

    @Test
    fun `generic plugins are gated on the tools capability`() {
        val catalog = state().copy(isGenericToolsAvailable = false).marketplaceCatalog()

        assertThat(catalog.filter { it.kind == MarketplaceKind.TOOL }).isEmpty()
        // The other kinds are their own gates, not casualties of this one.
        assertThat(catalog.filter { it.kind == MarketplaceKind.MCP }).isNotEmpty()
    }

    @Test
    fun `the ask row reads and toggles agent tools membership`() {
        val selected = state().copy(
            availableTools = state().availableTools + askPlugin(),
            selectedTools = listOf(ToolConstants.ASK_USER_QUESTION),
        )

        assertThat(selected.isBuiltinEnabled(ToolConstants.ASK_USER_QUESTION)).isTrue()
        assertThat(state().isBuiltinEnabled(ToolConstants.ASK_USER_QUESTION)).isFalse()
    }

    @Test
    fun `selection reads through to whichever list owns that kind`() {
        val selected = state().copy(
            webSearchEnabled = true,
            selectedTools = listOf("wolfram"),
            selectedMcpTools = setOf("search"),
            selectedSkillIds = listOf("skill-1"),
        )
        val catalog = selected.marketplaceCatalog()

        val on = catalog.filter { selected.isMarketplaceItemSelected(it) }.map { it.id }
        assertThat(on).containsExactly(ToolConstants.WEB_SEARCH, "wolfram", "search", "skill-1")
    }
}
