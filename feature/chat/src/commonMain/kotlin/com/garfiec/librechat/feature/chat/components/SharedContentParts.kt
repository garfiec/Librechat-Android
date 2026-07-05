package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.resolveImageFilePartUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource

// ─── ContentPartDispatcher ──────────────────────────────────────────

/**
 * Shared content part dispatch logic used by both Android and iOS
 * [ContentPartRenderer] implementations. Platform-specific renderers
 * delegate here for all content types.
 */
@Composable
internal fun ContentPartDispatcher(
    part: MessageContentPart,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    attachments: List<Attachment> = emptyList(),
    showImageDescriptions: Boolean = true,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
    // When false, a `subagent` tool_call renders flat instead of as a trace card.
    // Set false while rendering a subagent's own nested parts (depth-1 guard).
    allowSubagentCard: Boolean = true,
) {
    val mod = modifier.fillMaxWidth()
    when (part.type) {
        ContentType.TEXT, ContentType.TEXT_DELTA -> {
            TextContentPart(
                text = part.text.orEmpty(),
                modifier = mod,
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
                searchQuery = searchQuery,
                searchFocusedOccurrence = searchFocusedOccurrence,
                onFocusedOccurrencePosition = onFocusedOccurrencePosition,
            )
        }
        ContentType.THINK -> {
            ThinkingContentPart(
                thinkingText = part.think.orEmpty(),
                modifier = mod,
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
                searchQuery = searchQuery,
                searchFocusedOccurrence = searchFocusedOccurrence,
                onFocusedOccurrencePosition = onFocusedOccurrencePosition,
            )
        }
        ContentType.TOOL_CALL -> {
            ToolCallDispatcher(
                part = part,
                modifier = mod,
                baseUrl = baseUrl,
                attachments = attachments,
                showImageDescriptions = showImageDescriptions,
                allowSubagentCard = allowSubagentCard,
            )
        }
        ContentType.IMAGE_FILE -> {
            val imageUrl = resolveImageFilePartUrl(part, baseUrl)
            ImageContentPart(imageUrl = imageUrl, modifier = mod)
        }
        ContentType.IMAGE_URL -> {
            ImageContentPart(imageUrl = part.imageUrl?.url, modifier = mod)
        }
        ContentType.VIDEO_URL -> {
            val videoUrl = part.videoUrl?.url
            if (videoUrl != null) {
                VideoContent(url = videoUrl, modifier = mod)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = mod) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(Res.string.video_not_supported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ContentType.INPUT_AUDIO -> {
            AudioContent(data = part.inputAudio?.data, format = part.inputAudio?.format, modifier = mod)
        }
        ContentType.ERROR -> {
            ErrorContentPart(errorText = part.error ?: part.text.orEmpty(), modifier = mod)
        }
        ContentType.AGENT_UPDATE -> {
            val agentUpdate = part.agentUpdate
            AgentHandoffCard(
                handoff = AgentHandoff(
                    fromAgent = agentUpdate?.agentId,
                    toAgent = part.agentId?.let { "Agent $it" },
                    reason = agentUpdate?.runId?.let { "Run: $it" },
                ),
                modifier = mod,
            )
        }
        ContentType.SUMMARY -> {
            SummaryContentPart(
                summaryText = extractSummaryText(part),
                modifier = mod,
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
            )
        }
        else -> {
            if (!part.text.isNullOrEmpty()) {
                MarkdownContent(part.text.orEmpty(), mod, fontSizeMultiplier, useKatex)
            }
        }
    }
}

/**
 * Extracts text from a SUMMARY content part. Mirrors upstream's `getSummaryText`:
 * `content` may be an array of {type:"text", text} blocks, a raw string, or absent —
 * in which case the legacy top-level `text` field is the fallback.
 */
private fun extractSummaryText(part: MessageContentPart): String {
    val content = part.content
    if (content is JsonArray) {
        val builder = StringBuilder()
        for (element in content) {
            val item = element as? JsonObject ?: continue
            val type = item["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") {
                item["text"]?.jsonPrimitive?.contentOrNull?.let { builder.append(it) }
            }
        }
        return builder.toString()
    }
    if (content is JsonPrimitive && content.isString) {
        return content.content
    }
    return part.text.orEmpty()
}
