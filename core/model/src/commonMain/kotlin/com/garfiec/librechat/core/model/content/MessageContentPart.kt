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
    /**
     * Which KIND of activity label this is. **Absent means the per-batch label** — the only kind
     * that existed before — and `"phase"` means a parent phase spanning several batches.
     *
     * A phase label is APPENDED AT THE END of the content array (upstream takes its index from
     * `getContentParts().length`) while [activityStartIndex] names where the phase actually
     * began, then it is re-emitted filled. So its position says nothing about its scope, which is
     * exactly the assumption per-batch grouping is built on: a renderer that treats any filled
     * label as a batch header lets a trailing phase label claim whatever is left in the block —
     * rendering as a stray sentence at the bottom of the reply, or wrapping the wrong span.
     */
    @SerialName("activity_label_type") val activityLabelType: String? = null,
    /** Phase labels only: the content index the phase began at. See [activityLabelType]. */
    @SerialName("activity_start_index") val activityStartIndex: Int? = null,
    /**
     * Phase labels only: the EXCLUSIVE content index the phase's span ends at (upstream #14768).
     * The marker itself may trail its span — phases split once their text grows past ~200 chars,
     * so several phase markers can land in one response, each appended after content the previous
     * phase does not cover. `[activityStartIndex, activityEndIndex)` is the authoritative span;
     * the marker's own position says nothing. Absent on pre-rc1 phase labels, whose span ran to
     * the marker itself.
     */
    @SerialName("activity_end_index") val activityEndIndex: Int? = null,
    /** Phase labels only: how many content parts the phase covers from [activityStartIndex]. */
    @SerialName("activity_count") val activityCount: Int? = null,
    /** Phase labels only: the agents that participated in the phase. Telemetry, not rendering. */
    @SerialName("agent_ids") val agentIds: List<String>? = null,
    /**
     * TEXT parts and run-step `message_creation`: the Open Responses semantic channel this text
     * belongs to — `commentary` (the model narrating its work) or `final_answer`.
     *
     * Upstream uses `final_answer` to terminate its backward scan for leading commentary, i.e.
     * this is the signal that scopes an activity phase. Absent on every server that does not emit
     * phases, so nothing may become conditional on it being present.
     */
    val phase: String? = null,
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
