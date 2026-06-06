package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Agent(
    val id: String,
    @SerialName("_id") val mongoId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val instructions: String? = null,
    val avatar: JsonElement? = null,
    val provider: String? = null,
    val model: String? = null,
    @SerialName("model_parameters") val modelParameters: JsonElement? = null,
    val artifacts: String? = null,
    @SerialName("access_level") val accessLevel: Int? = null,
    @SerialName("recursion_limit") val recursionLimit: Int? = null,
    @SerialName("hide_sequential_outputs") val hideSequentialOutputs: Boolean? = null,
    @SerialName("end_after_tools") val endAfterTools: Boolean? = null,
    val category: String? = "general",
    val author: String? = null,
    val authorName: String? = null,
    @SerialName("is_promoted") val isPromoted: Boolean = false,
    val isPublic: Boolean? = null,
    @SerialName("conversation_starters") val conversationStarters: List<String> = emptyList(),
    val tools: List<String>? = null,
    val actions: List<String>? = null,
    @SerialName("agent_ids") val agentIds: List<String>? = null,
    val edges: List<JsonElement>? = null,
    val isCollaborative: Boolean? = null,
    @SerialName("projectIds") val projectIds: List<String> = emptyList(),
    val updatedAt: String? = null,
    val createdAt: String? = null,
    /**
     * Full per-version snapshots of the agent. Each entry is a serialized agent
     * state (name/description/instructions/artifacts/capabilities/tools/...) plus
     * createdAt/updatedAt for that revision. Indexed positionally — index N in
     * this list is the [version_index] used by POST /api/agents/:id/revert.
     */
    val versions: List<JsonElement>? = null,
    @SerialName("support_contact") val supportContact: JsonElement? = null,
    @SerialName("tool_options") val toolOptions: JsonObject? = null,
    /**
     * Runtime-supplied extra instructions appended to the agent system prompt.
     * Set by admin tooling or the agent runtime, not the editor UI.
     *
     * NOTE: as of the upstream LibreChat backend pinned by `UPSTREAM_VERSION`,
     * `agentBaseSchema` (`packages/api/src/agents/validation.ts`) does not
     * include this field and Zod's default `strip` mode discards it on
     * `agentUpdateSchema.parse()` — so sending it has no server effect today.
     * The field is plumbed so future backend versions that admit it (or
     * server forks that already do) will round-trip correctly without a
     * mobile change. Treat the value as informational; do not assume the
     * backend persists what we send.
     */
    @SerialName("additional_instructions") val additionalInstructions: String? = null,
    /**
     * Runtime-supplied per-tool kwargs. The upstream Mongoose schema
     * (`packages/data-schemas/src/schema/agent.ts:47-49`) declares this as
     * `[{ type: Mixed }]` (an array of mixed values), so consumers must
     * branch on the [JsonElement] shape (`jsonArray` vs `jsonObject`) rather
     * than assuming a map.
     *
     * NOTE: stripped by the upstream `agentBaseSchema` (same situation as
     * [additionalInstructions]) — round-tripped for forward compatibility,
     * no server-side persistence effect today.
     */
    @SerialName("tool_kwargs") val toolKwargs: JsonElement? = null,
    val mcpServerNames: List<String>? = null,
    /**
     * Per-capability file attachments. Shape: `{ execute_code: { file_ids: [...] },
     * file_search: { file_ids: [...] }, context: { file_ids: [...] }, ocr: { file_ids: [...] } }`.
     * The backend writes these when a file is uploaded with `agent_id` + `tool_resource`;
     * mobile parses `file_ids` to surface per-capability chips in the agent editor.
     */
    @SerialName("tool_resources") val toolResources: JsonObject? = null,
    /**
     * Optional allowlist of skill ObjectIds. Only applies when [skillsEnabled] is true.
     * Forward-compat (v0.8.6): mobile has no Skills editor yet, so this is round-tripped
     * untouched. Kept nullable so `explicitNulls=false` omits it when the editor didn't set it.
     */
    val skills: List<String>? = null,
    /**
     * Master toggle for skill use on this agent. `true` = active (full catalog unless
     * [skills] narrows it); `false`/null = inactive. Forward-compat (v0.8.6).
     */
    @SerialName("skills_enabled") val skillsEnabled: Boolean? = null,
    /** Subagent spawning configuration — isolated-context child agents. Forward-compat (v0.8.6). */
    val subagents: AgentSubagentsConfig? = null,
) {
    val avatarUrl: String?
        get() = try {
            when (avatar) {
                is JsonObject -> avatar.jsonObject["filepath"]?.jsonPrimitive?.content
                else -> avatar?.jsonPrimitive?.content?.takeIf { it.startsWith("http") }
            }
        } catch (_: Exception) { null }
}
