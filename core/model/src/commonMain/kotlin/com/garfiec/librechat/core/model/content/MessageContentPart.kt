package com.garfiec.librechat.core.model.content

import com.garfiec.librechat.core.model.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MessageContentPart(
    val type: ContentType,
    val text: String? = null,
    val think: String? = null,
    val error: String? = null,
    @SerialName("tool_call_ids") val toolCallIds: List<String>? = null,
    @SerialName("tool_call") val toolCall: AgentToolCall? = null,
    @SerialName("image_file") val imageFile: ImageFileContent? = null,
    @SerialName("image_url") val imageUrl: ImageUrlContent? = null,
    @SerialName("video_url") val videoUrl: VideoUrlContent? = null,
    @SerialName("input_audio") val inputAudio: InputAudioContent? = null,
    @SerialName("agent_update") val agentUpdate: AgentUpdateContent? = null,
    val summary: JsonObject? = null,
    val agentId: String? = null,
    val groupId: Int? = null,
    val stepIndex: Int? = null,
    val siblingIndex: Int? = null,
)
