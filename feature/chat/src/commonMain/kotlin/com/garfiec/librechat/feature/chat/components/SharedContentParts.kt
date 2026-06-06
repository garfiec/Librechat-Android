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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import com.garfiec.librechat.feature.chat.components.artifact.Artifact
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactButton
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactPanel
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactSegment
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.components.artifact.InlineArtifactStrategy
import com.garfiec.librechat.feature.chat.components.artifact.InlineArtifactView
import com.garfiec.librechat.feature.chat.components.artifact.InlineMarkdownArtifact
import com.garfiec.librechat.feature.chat.components.artifact.InlineSvgArtifact
import com.garfiec.librechat.feature.chat.components.artifact.InlineSvgSurface
import com.garfiec.librechat.feature.chat.components.artifact.LocalInlineArtifactPrefs
import com.garfiec.librechat.feature.chat.components.artifact.LocalMermaidRenderCache
import com.garfiec.librechat.feature.chat.components.artifact.detectArtifacts
import com.garfiec.librechat.feature.chat.components.artifact.groupArtifactVersions
import com.garfiec.librechat.feature.chat.components.artifact.isCacheableMermaid
import com.garfiec.librechat.feature.chat.components.artifact.mermaidCacheKey
import com.garfiec.librechat.feature.chat.components.artifact.selectInlineArtifactStrategy
import com.garfiec.librechat.feature.chat.components.artifact.shouldRenderInline
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
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
            val imageUrl = part.imageFile?.filepath?.let { filepath ->
                when {
                    filepath.startsWith("http") -> filepath
                    filepath.startsWith("/images/") && baseUrl.isNotBlank() -> "$baseUrl$filepath"
                    baseUrl.isNotBlank() -> "$baseUrl/api/files/$filepath"
                    else -> filepath
                }
            } ?: part.imageFile?.fileId?.let { fileId ->
                if (baseUrl.isNotBlank()) "$baseUrl/api/files/$fileId" else null
            }
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

// ─── ToolCallDispatcher ─────────────────────────────────────────────

@Composable
private fun ToolCallDispatcher(
    part: MessageContentPart,
    baseUrl: String,
    attachments: List<Attachment>,
    showImageDescriptions: Boolean,
    modifier: Modifier = Modifier,
    allowSubagentCard: Boolean = true,
) {
    val toolCall = part.toolCall
    val toolName = toolCall?.name ?: toolCall?.function?.name ?: "Tool Call"
    val toolNameLower = toolName.lowercase()
    val output = toolCall?.output ?: toolCall?.function?.output

    // Subagent tool_call → collapsible trace card (v0.8.6). Live progress comes
    // from LocalSubagentProgress (keyed by the parent tool_call id); persisted
    // `subagentContent` (reload) takes precedence inside the card. Depth-1:
    // nested parts pass allowSubagentCard=false so this never recurses.
    if (allowSubagentCard && toolNameLower == ToolConstants.SUBAGENT) {
        val toolCallId = toolCall?.id
        val liveTrace = toolCallId?.let { LocalSubagentProgress.current[it] }
        SubagentTraceCard(
            persistedParts = toolCall?.subagentContent,
            liveTrace = liveTrace,
            modifier = modifier,
            baseUrl = baseUrl,
            attachments = attachments,
            showImageDescriptions = showImageDescriptions,
        )
        return
    }

    when {
        toolNameLower.contains("search") -> {
            val results = remember(output) { parseWebSearchResults(output) }
            if (results.isNotEmpty()) {
                WebSearchResultList(results = results, modifier = modifier)
            } else {
                GenericToolCallCard(toolName, toolCall?.function?.arguments, output, modifier)
            }
        }
        toolNameLower.contains(ToolConstants.CODE_INTERPRETER) || toolNameLower.contains("execute") -> {
            val result = remember(toolCall) { parseCodeExecution(toolCall) }
            if (result != null) {
                CodeExecutionCard(result = result, modifier = modifier)
            } else {
                GenericToolCallCard(toolName, toolCall?.function?.arguments, output, modifier)
            }
        }
        toolNameLower.contains("memory") -> {
            val artifact = remember(output) { parseMemoryArtifact(output) }
            if (artifact != null) {
                MemoryArtifactCard(artifact = artifact, modifier = modifier)
            } else {
                GenericToolCallCard(toolName, toolCall?.function?.arguments, output, modifier)
            }
        }
        toolNameLower.contains("mcp") -> {
            val resources = remember(output) { parseMcpResources(output) }
            if (resources.isNotEmpty()) {
                McpResourceCarousel(resources = resources, modifier = modifier)
            } else {
                GenericToolCallCard(toolName, toolCall?.function?.arguments, output, modifier)
            }
        }
        isImageGenToolCall(toolNameLower) -> {
            val imageResult = remember(toolCall, baseUrl, attachments) {
                parseImageGenResult(toolCall, baseUrl, attachments)
            }
            ImageGenCard(result = imageResult, showDescription = showImageDescriptions, modifier = modifier)
        }
        toolNameLower.contains("log") -> {
            val logContent = remember(toolCall) { parseLogContent(toolCall) }
            LogContentCard(log = logContent, modifier = modifier)
        }
        else -> {
            GenericToolCallCard(toolName, toolCall?.function?.arguments, output, modifier)
        }
    }
}

// ─── TextContentPart ────────────────────────────────────────────────

@Composable
private fun TextContentPart(
    text: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates) -> Unit)? = null,
) {
    if (text.isBlank()) return

    val segments = remember(text) { detectArtifacts(text) }
    val hasArtifacts = remember(segments) { segments.any { it is ArtifactSegment.ArtifactReference } }

    if (!hasArtifacts) {
        MarkdownContent(
            text,
            modifier,
            fontSizeMultiplier,
            useKatex,
            searchQuery,
            searchFocusedOccurrence,
            onFocusedOccurrencePosition,
        )
    } else {
        val versionMap = remember(segments) { groupArtifactVersions(segments) }
        val inlinePrefs = LocalInlineArtifactPrefs.current
        var activeArtifact by remember {
            mutableStateOf<Artifact?>(null)
        }
        Column(modifier = modifier) {
            segments.forEach { segment ->
                when (segment) {
                    is ArtifactSegment.Text -> {
                        MarkdownContent(
                            segment.text,
                            Modifier.fillMaxWidth(),
                            fontSizeMultiplier,
                            useKatex,
                            searchQuery,
                            searchFocusedOccurrence,
                            onFocusedOccurrencePosition,
                        )
                    }
                    is ArtifactSegment.ArtifactReference -> {
                        val versions = versionMap[segment.artifact.identifier] ?: listOf(segment.artifact)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (inlinePrefs.shouldRenderInline(segment.artifact.type)) {
                            val type = ArtifactType.from(segment.artifact.type)
                            val cachedSvg = rememberCachedMermaidSvg(segment.artifact.content, type)
                            when (val strategy = selectInlineArtifactStrategy(type, segment.artifact.content, cachedSvg)) {
                                is InlineArtifactStrategy.CachedMermaidSvg -> InlineSvgSurface(
                                    svg = strategy.svg,
                                    onTap = { activeArtifact = segment.artifact },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = 4.dp,
                                )
                                InlineArtifactStrategy.NativeMarkdown -> InlineMarkdownArtifact(
                                    artifact = segment.artifact,
                                    onTap = { activeArtifact = segment.artifact },
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSizeMultiplier = fontSizeMultiplier,
                                    searchQuery = searchQuery,
                                    searchFocusedOccurrence = searchFocusedOccurrence,
                                    onFocusedOccurrencePosition = onFocusedOccurrencePosition,
                                )
                                InlineArtifactStrategy.IntrinsicSvg -> InlineSvgArtifact(
                                    artifact = segment.artifact,
                                    onTap = { activeArtifact = segment.artifact },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                InlineArtifactStrategy.WebViewSlot -> InlineArtifactView(
                                    artifact = segment.artifact,
                                    onTap = { activeArtifact = segment.artifact },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            ArtifactButton(
                                artifact = segment.artifact,
                                onClick = { activeArtifact = segment.artifact },
                                versionCount = versions.size,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
        activeArtifact?.let { artifact ->
            val versions = versionMap[artifact.identifier] ?: listOf(artifact)
            ArtifactPanel(
                artifact = artifact,
                onDismiss = { activeArtifact = null },
                versions = versions,
            )
        }
    }
}

/**
 * Reads the [LocalMermaidRenderCache] for the given artifact content and theme,
 * returning the cached SVG when present. Returns `null` for non-mermaid types
 * or non-cacheable mermaid sources so callers don't need to gate themselves.
 */
@Composable
private fun rememberCachedMermaidSvg(content: String, type: ArtifactType): String? {
    if (type != ArtifactType.MERMAID || !isCacheableMermaid(content)) return null
    val cache = LocalMermaidRenderCache.current
    val isDark = isSurfaceDark()
    val key = remember(content, isDark) { mermaidCacheKey(content, isDark) }
    return cache[key]
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

// ─── GenericToolCallCard ────────────────────────────────────────────

@Composable
internal fun GenericToolCallCard(
    toolName: String,
    args: String?,
    output: String?,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val toolCallCd =
                stringResource(if (isExpanded) Res.string.cd_collapse_tool_call else Res.string.cd_expand_tool_call, toolName)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .semantics {
                        role = Role.Button
                        contentDescription = toolCallCd
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Build,
                    stringResource(Res.string.cd_tool_call),
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    toolName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    stringResource(if (isExpanded) Res.string.cd_collapse else Res.string.cd_expand),
                    Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    if (!args.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.label_input),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CodeBlock(code = args, language = "json")
                    }
                    if (!output.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.label_output),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CodeBlock(code = output, language = null)
                    }
                }
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

    var showFullscreen by remember { mutableStateOf(false) }

    SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = stringResource(Res.string.cd_embedded_image),
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { showFullscreen = true }
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

    if (showFullscreen) {
        FullscreenImageViewer(imageUrl = imageUrl, onDismiss = { showFullscreen = false })
    }
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
