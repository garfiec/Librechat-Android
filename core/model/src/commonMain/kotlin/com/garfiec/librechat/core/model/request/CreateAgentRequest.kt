package com.garfiec.librechat.core.model.request

import com.garfiec.librechat.core.model.AgentSubagentsConfig
import com.garfiec.librechat.core.model.SupportContact
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class CreateAgentRequest(
    val name: String,
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
    // Forward-compat (v0.8.6) Skills/Subagents. Nullable so explicitNulls=false omits them
    // when the editor doesn't set them, leaving any server-side values untouched.
    val skills: List<String>? = null,
    @SerialName("skills_enabled") val skillsEnabled: Boolean? = null,
    val subagents: AgentSubagentsConfig? = null,
)
