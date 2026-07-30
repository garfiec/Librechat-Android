package com.garfiec.librechat.core.model.content

import com.garfiec.librechat.core.model.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    // Mid-run steering part payload (type == "steer", upstream #14220). Kept as a raw element
    // because the app doesn't render steering yet; its presence must not break deserialization
    // of the surrounding message. Paired with [ContentType.STEER].
    @SerialName("steer") val steer: JsonElement? = null,
    // Activity-group header text (type == "activity_label", upstream #14391). Top-level on the
    // wire like the SUMMARY fields below, not nested. Empty or absent while the label is still a
    // pending reservation, which upstream renders as nothing. Paired with
    // [ContentType.ACTIVITY_LABEL].
    @SerialName("activity_label") val activityLabel: String? = null,
    // Also activity-label fields. `pending` marks the reservation published at the batch boundary
    // before the text exists; `status` reports `failed` / `partial` for a batch that did not fully
    // return. Both only matter to the group header, which is why they arrive with it rather than
    // with the type itself.
    val pending: Boolean? = null,
    val status: String? = null,
    // SUMMARY content-part fields (type == "summary"). Fields are top-level on the wire,
    // not nested under a `summary` key. `content` can be an array of {type,text} blocks
    // or a raw string; legacy servers fall back to the top-level `text` field above.
    val content: JsonElement? = null,
    val tokenCount: Int? = null,
    val summarizing: Boolean? = null,
    val summaryVersion: Int? = null,
    val model: String? = null,
    val provider: String? = null,
    val createdAt: String? = null,
    val boundary: SummaryBoundary? = null,
    val agentId: String? = null,
    val groupId: Int? = null,
    val stepIndex: Int? = null,
    val siblingIndex: Int? = null,
)

@Serializable
data class SummaryBoundary(
    val messageId: String? = null,
    val contentIndex: Int? = null,
)
