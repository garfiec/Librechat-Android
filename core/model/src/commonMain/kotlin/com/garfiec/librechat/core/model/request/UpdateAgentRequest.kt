package com.garfiec.librechat.core.model.request

import com.garfiec.librechat.core.model.AgentSubagentsConfig
import com.garfiec.librechat.core.model.SupportContact
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class UpdateAgentRequest(
    val name: String? = null,
    val description: String? = null,
    val instructions: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("model_parameters") val modelParameters: JsonObject? = null,
    val artifacts: String? = null,
    @SerialName("recursion_limit") val recursionLimit: Int? = null,
    @SerialName("hide_sequential_outputs") val hideSequentialOutputs: Boolean? = null,
    @SerialName("end_after_tools") val endAfterTools: Boolean? = null,
    val category: String? = null,
    val tools: List<String>? = null,
    @SerialName("conversation_starters") val conversationStarters: List<String>? = null,
    val isPublic: Boolean? = null,
    val isCollaborative: Boolean? = null,
    @SerialName("projectIds") val projectIds: List<String>? = null,
    @SerialName("support_contact") val supportContact: SupportContact? = null,
    @SerialName("tool_options") val toolOptions: JsonObject? = null,
    @SerialName("agent_ids") val agentIds: List<String>? = null,
    val edges: List<JsonElement>? = null,
    @SerialName("tool_kwargs") val toolKwargs: JsonElement? = null,
    @SerialName("additional_instructions") val additionalInstructions: String? = null,
    @SerialName("code_files") val codeFiles: List<String>? = null,
    @SerialName("knowledge_files") val knowledgeFiles: List<String>? = null,
    @SerialName("context_files") val contextFiles: List<String>? = null,
    // Skills/Subagents (v0.8.6). Nullable so explicitNulls=false omits the field when a caller
    // leaves it unset, and the server's findOneAndUpdate $set merge then preserves the stored
    // value. NOTE: the agent editor does NOT rely on that omission for skills — it always sends
    // a non-null skills/skills_enabled on update. Its non-wipe guarantee instead comes from
    // re-hydration: AgentEditorViewModel.applyAgentData loads the server-pruned saved agent and
    // re-sends that pruned set, so a future maintainer must NOT "simplify" the always-send-on-
    // update logic on the assumption that null-omission still protects server-set skills.
    val skills: List<String>? = null,
    @SerialName("skills_enabled") val skillsEnabled: Boolean? = null,
    val subagents: AgentSubagentsConfig? = null,
)
