package com.garfiec.librechat.feature.agents.viewmodel

import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.AgentAction
import com.garfiec.librechat.core.model.AgentFile
import com.garfiec.librechat.core.model.AgentTool
import com.garfiec.librechat.core.model.ArtifactsMode
import com.garfiec.librechat.core.model.HandoffEdge
import com.garfiec.librechat.feature.agents.AgentActionDisplayData
import com.garfiec.librechat.feature.agents.AgentHandoffDisplayData
import com.garfiec.librechat.feature.agents.AgentToolDisplayData
import com.garfiec.librechat.feature.agents.components.model.AgentAdvancedSettings
import com.garfiec.librechat.feature.agents.components.model.AgentCapabilities
import com.garfiec.librechat.feature.agents.components.model.AgentSharingState
import com.garfiec.librechat.feature.agents.components.model.AgentVisibility
import com.garfiec.librechat.feature.agents.components.model.SupportContactState
import com.garfiec.librechat.feature.agents.components.model.buildAgentVersionList
import com.garfiec.librechat.feature.agents.util.OpenApiSpecParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Pure, side-effect-free transforms between the agent wire model and
// [AgentEditorUiState]. Extracted from AgentEditorViewModel so the editor's
// load/save mapping is unit-testable without a ViewModel, coroutines, or
// Android. Everything here is a function of its inputs only.

// Tool identifiers that represent capabilities (not user-selectable tools).
// These are stored in the agent's tools list but displayed as capability toggles in the UI.
private val CAPABILITY_TOOLS = setOf(
    ToolConstants.EXECUTE_CODE,
    ToolConstants.FILE_SEARCH,
    ToolConstants.WEB_SEARCH,
    "context",
    "end_after_tools",
    "hide_sequential_outputs",
    ToolConstants.PROGRAMMATIC_TOOLS,
    ToolConstants.DEFERRED_TOOLS,
)

// MCP server marker prefix: tools starting with "sys__server__sys" or containing "_mcp_"
// are MCP-related entries in the tools list.
private const val MCP_SERVER_MARKER = "sys__server__sys"
private const val MCP_TOOL_SEPARATOR = "_mcp_"

private val EDGE_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Partitions the agent's raw tools list into:
 * - regular tools (for the tool selector UI)
 * - capability booleans (code interpreter, file search, etc.)
 * - selected MCP tool names (tools containing "_mcp_")
 *
 * This mirrors how the web frontend splits the tools array when loading
 * an agent for editing (see AgentSelect.tsx resetAgentForm).
 */
private fun partitionTools(
    rawTools: List<String>?,
): Triple<List<String>, Set<String>, Set<String>> {
    if (rawTools == null) return Triple(emptyList(), emptySet(), emptySet())

    val regularTools = mutableListOf<String>()
    val capabilityTools = mutableSetOf<String>()
    val mcpToolNames = mutableSetOf<String>()

    for (tool in rawTools) {
        when {
            tool in CAPABILITY_TOOLS -> capabilityTools.add(tool)
            tool.contains(MCP_TOOL_SEPARATOR) -> {
                // MCP tools are stored as "toolName_mcp_serverName" or
                // "sys__server__sys_mcp_serverName" in the agent's tools list.
                // Extract the tool name part (before _mcp_) for matching against
                // the McpTool.name from the MCP tools API.
                val toolName = tool.substringBefore(MCP_TOOL_SEPARATOR)
                // The sys__server__sys marker means the entire MCP server was
                // toggled on -- we still track the server name for display.
                if (toolName == MCP_SERVER_MARKER) {
                    // Server-level toggle: store the server name
                    val serverName = tool.substringAfter(MCP_TOOL_SEPARATOR)
                    mcpToolNames.add(serverName)
                } else {
                    mcpToolNames.add(toolName)
                }
            }
            else -> regularTools.add(tool)
        }
    }

    return Triple(regularTools, capabilityTools, mcpToolNames)
}

/**
 * Applies agent data to the UI state using the copy() function.
 * Returns a new AgentEditorUiState with all agent fields populated.
 * This is the single source of truth for mapping agent API response data
 * to the editor UI, used by both loadAgent() and revertToVersion().
 */
internal fun AgentEditorUiState.applyAgentData(agent: Agent): AgentEditorUiState {
    val (regularTools, capabilityTools, mcpToolNames) = partitionTools(agent.tools)
    val parsedEdges = parseHandoffEdges(agent.edges)

    return copy(
        name = agent.name ?: "",
        description = agent.description ?: "",
        instructions = agent.instructions ?: "",
        model = agent.model ?: "",
        provider = agent.provider ?: "",
        category = agent.category ?: "general",
        selectedTools = regularTools,
        conversationStarters = agent.conversationStarters,
        avatarUrl = agent.avatarUrl,
        codeInterpreterEnabled = ToolConstants.EXECUTE_CODE in capabilityTools,
        fileSearchEnabled = ToolConstants.FILE_SEARCH in capabilityTools,
        webSearchEnabled = ToolConstants.WEB_SEARCH in capabilityTools,
        fileContextEnabled = "context" in capabilityTools,
        selectedMcpTools = mcpToolNames,
        capabilities = AgentCapabilities(
            artifactsMode = ArtifactsMode.fromWire(agent.artifacts),
            endAfterTools = agent.endAfterTools ?: false,
            hideSequentialOutputs = agent.hideSequentialOutputs ?: false,
            recursionLimit = agent.recursionLimit ?: 25,
        ),
        advancedSettings = parseModelParameters(agent.modelParameters),
        sharingState = AgentSharingState(
            visibility = when {
                agent.isPublic == true -> AgentVisibility.PUBLIC
                agent.isCollaborative == true -> AgentVisibility.TEAM
                else -> AgentVisibility.PRIVATE
            },
            isCollaborative = agent.isCollaborative ?: false,
        ),
        supportContact = agent.parseSupportContact(),
        chainAgentIds = agent.agentIds ?: emptyList(),
        // Re-hydrate from the saved agent (server silently drops
        // inaccessible skill ids in sanitizeViewerSkillScope), so the
        // chips reflect what was actually persisted, not stale local
        // state. Empty + enabled stays "full catalog".
        skillsEnabled = agent.skillsEnabled ?: false,
        selectedSkillIds = agent.skills ?: emptyList(),
        // Subagents config (v0.8.6). allowSelf defaults true (upstream
        // `allowSelf !== false`); self never appears in agent_ids.
        subagentsEnabled = agent.subagents?.enabled ?: false,
        subagentAllowSelf = agent.subagents?.allowSelf != false,
        selectedSubagentIds = agent.subagents?.agentIds
            ?.filter { it != agent.id }
            ?: emptyList(),
        handoffEdges = parsedEdges.typed,
        unparsedHandoffEdges = parsedEdges.unparsed,
        toolOptions = agent.toolOptions,
        additionalInstructions = agent.additionalInstructions,
        toolKwargs = agent.toolKwargs,
        codeFiles = parseToolResourceFiles(agent.toolResources, ToolConstants.EXECUTE_CODE),
        knowledgeFiles = parseToolResourceFiles(agent.toolResources, ToolConstants.FILE_SEARCH),
        contextFiles = parseToolResourceFiles(agent.toolResources, "context") +
            // The OCR resource is merged into Context in the editor UI on web
            // (see upstream client/src/utils/forms.tsx). Mirror that.
            parseToolResourceFiles(agent.toolResources, "ocr"),
        versions = buildAgentVersionList(
            rawVersions = agent.versions
                ?.filterIsInstance<JsonObject>()
                ?: emptyList(),
            currentName = agent.name,
            currentDescription = agent.description,
            currentInstructions = agent.instructions,
            currentArtifacts = agent.artifacts,
            // Match upstream's isActiveVersion exactly: capabilities is
            // not a separate field on the agent record — the snapshot's
            // `tools` array carries capability markers (execute_code,
            // file_search, web_search, context) mixed in with regular
            // tool names. Passing the union here keeps the active-
            // version marker working; previously we filtered capability
            // markers out of currentTools and compared against an empty
            // capabilities set, which never matched.
            currentCapabilities = emptySet(),
            currentTools = (regularTools + mcpToolNames + capabilityTools).toSet(),
        ),
    )
}

/**
 * Pulls `tool_resources.<resource>.file_ids` out of the agent payload and
 * lifts each id into an [AgentFile] stub. Filename / bytes / type are
 * filled in later by loadAgentFiles hitting `GET /api/files/agent/:id`.
 */
private fun parseToolResourceFiles(
    toolResources: JsonObject?,
    resource: String,
): List<AgentFile> {
    val obj = toolResources ?: return emptyList()
    return try {
        val resourceObj = obj[resource] as? JsonObject ?: return emptyList()
        val ids = resourceObj["file_ids"] as? JsonElement ?: return emptyList()
        ids.jsonArray.mapNotNull { element ->
            val id = (element as? JsonPrimitive)?.content ?: return@mapNotNull null
            AgentFile(fileId = id, originResource = resource)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private data class ParsedEdges(
    val typed: List<HandoffEdge>,
    val unparsed: List<JsonElement>,
)

private fun parseHandoffEdges(edges: List<JsonElement>?): ParsedEdges {
    if (edges.isNullOrEmpty()) return ParsedEdges(emptyList(), emptyList())
    val typed = mutableListOf<HandoffEdge>()
    val unparsed = mutableListOf<JsonElement>()
    edges.forEach { element ->
        try {
            typed += EDGE_JSON.decodeFromJsonElement(HandoffEdge.serializer(), element)
        } catch (_: Exception) {
            // Keep the raw element so save() can re-emit it. Without
            // this preservation, a single decoder mismatch (e.g.
            // upstream adds a new required field) silently drops
            // server-side edges on every subsequent save.
            unparsed += element
        }
    }
    return ParsedEdges(typed, unparsed)
}

internal fun encodeHandoffEdges(edges: List<HandoffEdge>): List<JsonElement>? {
    if (edges.isEmpty()) return null
    return edges.map { EDGE_JSON.encodeToJsonElement(HandoffEdge.serializer(), it) }
}

/** Update-path encoder: always returns a list (possibly empty) so the
 *  server overwrites its `edges` field. Use [encodeHandoffEdges] on
 *  create where an empty list adds noise without semantic meaning. */
internal fun encodeHandoffEdgesAlways(edges: List<HandoffEdge>): List<JsonElement> {
    return edges.map { EDGE_JSON.encodeToJsonElement(HandoffEdge.serializer(), it) }
}

/**
 * Parse model_parameters JsonElement into AgentAdvancedSettings.
 *
 * Three fields are typed because the agent-editor UI exposes them
 * (temperature, top_p, max_tokens). Every other key is preserved
 * verbatim in [AgentAdvancedSettings.extras] so values like
 * `frequency_penalty`, `presence_penalty`, `reasoning_effort`,
 * `verbosity`, `thinking`, `thinkingBudget`, `web_search`, `region`,
 * etc. round-trip through load → save without being dropped — even
 * though mobile doesn't yet surface them in the agent editor.
 */
private fun parseModelParameters(params: JsonElement?): AgentAdvancedSettings {
    if (params == null) return AgentAdvancedSettings()
    return try {
        val obj = params.jsonObject
        val typedKeys = setOf(
            "temperature",
            "top_p", "topP",
            "max_tokens", "maxTokens", "maxOutputTokens",
        )
        // Remember which alias each typed slot was loaded under so the
        // save path can emit using the same key. Without this, a Google
        // agent's `maxOutputTokens` would silently become `max_tokens`
        // on every save, and a Bedrock-Anthropic `topP` would become
        // `top_p`. Servers that index on the exact key (or compute a
        // version diff) treat these as different fields.
        val topPKey = when {
            "top_p" in obj -> "top_p"
            "topP" in obj -> "topP"
            else -> null
        }
        val maxTokensKey = when {
            "max_tokens" in obj -> "max_tokens"
            "maxTokens" in obj -> "maxTokens"
            "maxOutputTokens" in obj -> "maxOutputTokens"
            else -> null
        }
        AgentAdvancedSettings(
            temperature = obj["temperature"]?.jsonPrimitive?.floatOrNull,
            topP = topPKey?.let { obj[it]?.jsonPrimitive?.floatOrNull },
            maxTokens = maxTokensKey?.let { obj[it]?.jsonPrimitive?.intOrNull },
            topPKey = topPKey,
            maxTokensKey = maxTokensKey,
            extras = obj.filterKeys { it !in typedKeys },
        )
    } catch (_: Exception) {
        AgentAdvancedSettings()
    }
}

/**
 * Build model_parameters JsonObject from the advanced settings.
 * Returns null when no parameters are set (avoids sending empty
 * objects). Re-emits everything in [AgentAdvancedSettings.extras]
 * untouched so server-set keys mobile doesn't yet edit survive a
 * save. Typed slots are emitted under the same key the server
 * originally sent — see [AgentAdvancedSettings.topPKey] / [maxTokensKey].
 */
internal fun buildModelParameters(settings: AgentAdvancedSettings): JsonObject? {
    val map = mutableMapOf<String, JsonElement>()
    map.putAll(settings.extras)
    settings.temperature?.let { map["temperature"] = JsonPrimitive(it) }
    settings.topP?.let { map[settings.topPKey ?: "top_p"] = JsonPrimitive(it) }
    settings.maxTokens?.let { map[settings.maxTokensKey ?: "max_tokens"] = JsonPrimitive(it) }
    return if (map.isEmpty()) null else JsonObject(map)
}

/**
 * Build the full tools list for saving, combining:
 * - User-selected tools (from tool selector)
 * - Capability tools (execute_code, file_search) based on toggle state
 * - MCP server markers for selected MCP tools
 */
internal fun buildToolsList(state: AgentEditorUiState): List<String> {
    val tools = state.selectedTools.toMutableList()

    // Add capability tools based on toggle state, but gated on
    // server availability — drops the entry if the server's
    // agents.capabilities list excludes the capability (even when
    // the in-memory toggle is on from a previously-loaded agent).
    // The observers intentionally don't reset the toggle on
    // availability transitions; the filter lives here instead.
    if (state.codeInterpreterEnabled && state.isCodeInterpreterAvailable) tools.add(ToolConstants.EXECUTE_CODE)
    if (state.fileSearchEnabled) tools.add(ToolConstants.FILE_SEARCH)
    if (state.webSearchEnabled && state.isWebSearchAvailable) tools.add(ToolConstants.WEB_SEARCH)
    if (state.fileContextEnabled) tools.add("context")

    // Add MCP server markers for each selected MCP tool
    for (mcpToolName in state.selectedMcpTools) {
        // Check if this is a server name or a tool name by looking at available MCP tools
        val matchingTool = state.mcpTools.find { it.name == mcpToolName }
        if (matchingTool != null) {
            val serverName = matchingTool.serverName
            if (serverName != null) {
                // Store as "toolName_mcp_serverName" format
                tools.add("${mcpToolName}${MCP_TOOL_SEPARATOR}$serverName")
            } else {
                tools.add(mcpToolName)
            }
        } else {
            // May be a server name marker
            tools.add("${MCP_SERVER_MARKER}${MCP_TOOL_SEPARATOR}$mcpToolName")
        }
    }

    return tools
}

internal fun Agent.toHandoffDisplayData() = AgentHandoffDisplayData(
    id = id,
    name = name ?: id,
)

internal fun AgentAction.toDisplayData(): AgentActionDisplayData {
    val rawSpec = metadata?.rawSpec
    val functionCount = if (!rawSpec.isNullOrBlank()) {
        try {
            OpenApiSpecParser.extractFunctionInfo(rawSpec).size
        } catch (_: Exception) {
            0
        }
    } else {
        0
    }
    return AgentActionDisplayData(
        actionId = actionId,
        domain = metadata?.domain,
        type = type,
        authType = metadata?.auth?.type,
        rawSpec = metadata?.rawSpec,
        functionCount = functionCount,
    )
}

internal fun AgentTool.toDisplayData() = AgentToolDisplayData(
    toolId = pluginKey ?: toolId,
    name = name,
    description = description,
    icon = icon,
    isAvailable = isAvailable,
)

/**
 * Parse the support_contact JsonElement from the Agent model into
 * a SupportContactState for the UI.
 */
private fun Agent.parseSupportContact(): SupportContactState {
    val json = supportContact ?: return SupportContactState()
    return try {
        val obj = json as? JsonObject
            ?: return SupportContactState()
        val name = obj["name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: ""
        val email = obj["email"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: ""
        SupportContactState(name = name, email = email)
    } catch (_: Exception) {
        SupportContactState()
    }
}
