package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
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
    onFocusedOccurrencePosition: ((LayoutCoordinates) -> Unit)? = null,
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

// ─── ThinkingContentPart ────────────────────────────────────────────

@Composable
private fun ThinkingContentPart(
    thinkingText: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates) -> Unit)? = null,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val thinkingToggleCd =
        stringResource(if (isExpanded) Res.string.cd_collapse_thinking else Res.string.cd_expand_thinking)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { isExpanded = !isExpanded }
                .padding(12.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = thinkingToggleCd
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Psychology,
                stringResource(Res.string.cd_thinking_indicator),
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(Res.string.label_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                stringResource(if (isExpanded) Res.string.cd_collapse else Res.string.cd_expand),
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownContent(
                    thinkingText,
                    fontSizeMultiplier = fontSizeMultiplier,
                    useKatex = useKatex,
                    searchQuery = searchQuery,
                    searchFocusedOccurrence = searchFocusedOccurrence,
                    onFocusedOccurrencePosition = onFocusedOccurrencePosition,
                )
            }
        }
    }
}

// ─── SummaryContentPart ─────────────────────────────────────────────

/**
 * Collapsed "Summarized earlier messages" card rendered when the server
 * emits a SUMMARY content part. Content-compaction is triggered by long
 * agent chats (v0.8.5+); tap to expand and read the summary text.
 */
@Composable
private fun SummaryContentPart(
    summaryText: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
) {
    if (summaryText.isBlank()) return

    var isExpanded by remember { mutableStateOf(false) }
    val summaryToggleCd =
        stringResource(if (isExpanded) Res.string.cd_collapse_summary else Res.string.cd_expand_summary)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { isExpanded = !isExpanded }
                .padding(12.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = summaryToggleCd
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Notes,
                stringResource(Res.string.cd_summary_indicator),
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(Res.string.label_summary),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                stringResource(if (isExpanded) Res.string.cd_collapse else Res.string.cd_expand),
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownContent(
                    summaryText,
                    fontSizeMultiplier = fontSizeMultiplier,
                    useKatex = useKatex,
                )
            }
        }
    }
}

// ─── ImageContentPart ───────────────────────────────────────────────

@Composable
internal fun ImageContentPart(
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (imageUrl == null) return

    val openMedia = LocalChatMediaViewer.current

    SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = stringResource(Res.string.cd_embedded_image),
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { openMedia(imageUrl) }
            .semantics { role = Role.Image },
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.BrokenImage,
                    stringResource(Res.string.cd_failed_to_load_image),
                    Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

// ─── ErrorContentPart ───────────────────────────────────────────────

@Composable
internal fun ErrorContentPart(
    errorText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(errorText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
