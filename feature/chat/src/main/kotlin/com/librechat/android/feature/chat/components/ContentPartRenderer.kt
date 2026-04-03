package com.librechat.android.feature.chat.components

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
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.librechat.android.core.common.ToolConstants
import com.librechat.android.core.model.AgentToolCall
import com.librechat.android.core.model.ContentType
import com.librechat.android.core.model.MessageContentPart
import com.librechat.android.feature.chat.R
import com.librechat.android.feature.chat.components.artifact.ArtifactButton
import com.librechat.android.feature.chat.components.artifact.ArtifactPanel
import com.librechat.android.feature.chat.components.artifact.ArtifactSegment
import com.librechat.android.feature.chat.components.artifact.detectArtifacts
import com.librechat.android.feature.chat.components.artifact.groupArtifactVersions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

@Composable
fun ContentPartRenderer(
    part: MessageContentPart,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    attachments: List<com.librechat.android.core.model.Attachment> = emptyList(),
    showImageDescriptions: Boolean = true,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePositioned: ((LayoutCoordinates) -> Unit)? = null,
) {
    val constrainedModifier = modifier.fillMaxWidth()
    when (part.type) {
        ContentType.TEXT, ContentType.TEXT_DELTA -> {
            TextContentPart(
                text = part.text.orEmpty(),
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
                searchQuery = searchQuery,
                searchFocusedOccurrence = searchFocusedOccurrence,
                onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
                modifier = constrainedModifier,
            )
        }
        ContentType.THINK -> {
            ThinkingContentPart(
                thinkingText = part.think.orEmpty(),
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
                searchQuery = searchQuery,
                searchFocusedOccurrence = searchFocusedOccurrence,
                onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
                modifier = constrainedModifier,
            )
        }
        ContentType.TOOL_CALL -> {
            val toolCall = part.toolCall
            val toolName = toolCall?.name ?: toolCall?.function?.name ?: "Tool Call"
            val toolNameLower = toolName.lowercase()
            val output = toolCall?.output ?: toolCall?.function?.output

            when {
                toolNameLower.contains("search") || toolNameLower.contains(ToolConstants.WEB_SEARCH) -> {
                    val results = remember(output) { parseWebSearchResults(output) }
                    if (results.isNotEmpty()) {
                        WebSearchResultList(
                            results = results,
                            modifier = constrainedModifier,
                        )
                    } else {
                        ToolCallContentPart(
                            toolName = toolName,
                            args = toolCall?.function?.arguments,
                            output = output,
                            modifier = constrainedModifier,
                        )
                    }
                }
                toolNameLower.contains(ToolConstants.CODE_INTERPRETER) || toolNameLower.contains("execute") -> {
                    val result = remember(toolCall) { parseCodeExecution(toolCall) }
                    if (result != null) {
                        CodeExecutionCard(
                            result = result,
                            modifier = constrainedModifier,
                        )
                    } else {
                        ToolCallContentPart(
                            toolName = toolName,
                            args = toolCall?.function?.arguments,
                            output = output,
                            modifier = constrainedModifier,
                        )
                    }
                }
                toolNameLower.contains("memory") -> {
                    val artifact = remember(output) { parseMemoryArtifact(output) }
                    if (artifact != null) {
                        MemoryArtifactCard(
                            artifact = artifact,
                            modifier = constrainedModifier,
                        )
                    } else {
                        ToolCallContentPart(
                            toolName = toolName,
                            args = toolCall?.function?.arguments,
                            output = output,
                            modifier = constrainedModifier,
                        )
                    }
                }
                toolNameLower.contains("mcp") -> {
                    val resources = remember(output) { parseMcpResources(output) }
                    if (resources.isNotEmpty()) {
                        McpResourceCarousel(
                            resources = resources,
                            modifier = constrainedModifier,
                        )
                    } else {
                        ToolCallContentPart(
                            toolName = toolName,
                            args = toolCall?.function?.arguments,
                            output = output,
                            modifier = constrainedModifier,
                        )
                    }
                }
                isImageGenToolCall(toolNameLower) -> {
                    val imageResult = remember(toolCall, baseUrl, attachments) {
                        parseImageGenResult(toolCall, baseUrl, attachments)
                    }
                    ImageGenCard(
                        result = imageResult,
                        showDescription = showImageDescriptions,
                        modifier = constrainedModifier,
                    )
                }
                toolNameLower.contains("log") -> {
                    val logContent = remember(toolCall) { parseLogContent(toolCall) }
                    LogContentCard(
                        log = logContent,
                        modifier = constrainedModifier,
                    )
                }
                else -> {
                    ToolCallContentPart(
                        toolName = toolName,
                        args = toolCall?.function?.arguments,
                        output = output,
                        modifier = constrainedModifier,
                    )
                }
            }
        }
        ContentType.ERROR -> {
            ErrorContentPart(
                errorText = part.error.orEmpty(),
                modifier = constrainedModifier,
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
            ImageContentPart(
                imageUrl = imageUrl,
                modifier = constrainedModifier,
            )
        }
        ContentType.IMAGE_URL -> {
            ImageContentPart(
                imageUrl = part.imageUrl?.url,
                modifier = constrainedModifier,
            )
        }
        ContentType.VIDEO_URL -> {
            val videoUrl = part.videoUrl?.url
            if (videoUrl != null) {
                VideoContent(
                    url = videoUrl,
                    modifier = constrainedModifier,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = constrainedModifier,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.video_not_supported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ContentType.INPUT_AUDIO -> {
            AudioContent(
                data = part.inputAudio?.data,
                format = part.inputAudio?.format,
                modifier = constrainedModifier,
            )
        }
        ContentType.AGENT_UPDATE -> {
            val agentUpdate = part.agentUpdate
            AgentHandoffCard(
                handoff = AgentHandoff(
                    fromAgent = agentUpdate?.agentId?.let { stringResource(R.string.cd_agent_handoff).format(it, "") },
                    toAgent = part.agentId?.let { "Agent $it" },
                    reason = agentUpdate?.runId?.let { "Run: $it" },
                ),
                modifier = constrainedModifier,
            )
        }
    }
}

@Composable
private fun TextContentPart(
    text: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePositioned: ((LayoutCoordinates) -> Unit)? = null,
) {
    if (text.isBlank()) return

    val segments = remember(text) { detectArtifacts(text) }
    val hasArtifacts = remember(segments) { segments.any { it is ArtifactSegment.ArtifactReference } }

    Column(modifier = modifier) {
        if (!hasArtifacts) {
            MarkdownContent(
                text = text,
                fontSizeMultiplier = fontSizeMultiplier,
                useKatex = useKatex,
                searchQuery = searchQuery,
                searchFocusedOccurrence = searchFocusedOccurrence,
                onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
            )
        } else {
            val versionMap = remember(segments) { groupArtifactVersions(segments) }
            var activeArtifact by remember {
                mutableStateOf<com.librechat.android.feature.chat.components.artifact.Artifact?>(null)
            }

            segments.forEachIndexed { segIndex, segment ->
                key(segIndex) {
                    when (segment) {
                        is ArtifactSegment.Text -> {
                            MarkdownContent(
                                text = segment.text,
                                fontSizeMultiplier = fontSizeMultiplier,
                                useKatex = useKatex,
                                searchQuery = searchQuery,
                                searchFocusedOccurrence = searchFocusedOccurrence,
                                onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
                            )
                        }
                        is ArtifactSegment.ArtifactReference -> {
                            val versions = versionMap[segment.artifact.identifier] ?: listOf(segment.artifact)
                            Spacer(modifier = Modifier.height(8.dp))
                            ArtifactButton(
                                artifact = segment.artifact,
                                onClick = { activeArtifact = segment.artifact },
                                versionCount = versions.size,
                            )
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
}

@Composable
private fun ThinkingContentPart(
    thinkingText: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePositioned: ((LayoutCoordinates) -> Unit)? = null,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val thinkingToggleCd =
        stringResource(if (isExpanded) R.string.cd_collapse_thinking else R.string.cd_expand_thinking)

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
                imageVector = Icons.Default.Psychology,
                contentDescription = stringResource(R.string.cd_thinking_indicator),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.label_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(if (isExpanded) R.string.cd_collapse else R.string.cd_expand),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownContent(
                    text = thinkingText,
                    fontSizeMultiplier = fontSizeMultiplier,
                    useKatex = useKatex,
                    searchQuery = searchQuery,
                    searchFocusedOccurrence = searchFocusedOccurrence,
                    onFocusedOccurrencePositioned = onFocusedOccurrencePositioned,
                )
            }
        }
    }
}

@Composable
private fun ToolCallContentPart(
    toolName: String,
    args: String?,
    output: String?,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val toolCallCd =
                stringResource(if (isExpanded) R.string.cd_collapse_tool_call else R.string.cd_expand_tool_call, toolName)
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
                    imageVector = Icons.Default.Build,
                    contentDescription = stringResource(R.string.cd_tool_call),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (isExpanded) R.string.cd_collapse else R.string.cd_expand),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    if (!args.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.label_input),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        CodeBlock(code = args, language = "json")
                    }
                    if (!output.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.label_output),
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

@Composable
private fun ImageContentPart(
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (imageUrl == null) return

    var showFullscreen by remember { mutableStateOf(false) }

    SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = stringResource(R.string.cd_embedded_image),
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { showFullscreen = true }
            .semantics {
                role = Role.Image
            },
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(12.dp),
                    ),
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
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = stringResource(R.string.cd_failed_to_load_image),
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    if (showFullscreen) {
        FullscreenImageViewer(
            imageUrl = imageUrl,
            onDismiss = { showFullscreen = false },
        )
    }
}

@Composable
private fun ErrorContentPart(
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
        Text(
            text = errorText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

// --- Tool name classification helpers ---

/**
 * Set of exact tool names that are image-generation tools, matching the
 * official LibreChat web app's `Part.tsx` routing logic.
 */
private val IMAGE_GEN_EXACT_NAMES = setOf(
    "image_gen_oai",
    "image_edit_oai",
    "gemini_image_gen",
)

/**
 * Set of legacy tool names from the `imageGenTools` set in the official repo
 * (`data-provider/src/config.ts`). These are matched by containment.
 */
private val IMAGE_GEN_CONTAINS = listOf(
    "dall",
    "image_gen",
    "stable-diffusion",
    "flux",
)

/**
 * Returns true if the given (lowercased) tool name represents an image
 * generation or editing tool that should be rendered with [ImageGenCard].
 */
private fun isImageGenToolCall(toolNameLower: String): Boolean {
    if (toolNameLower in IMAGE_GEN_EXACT_NAMES) return true
    return IMAGE_GEN_CONTAINS.any { toolNameLower.contains(it) }
}

// --- JSON parsing helpers for specialized tool call cards ---

/**
 * Attempts to parse web search results from tool call output JSON.
 * Supports both a JSON array of results and a JSON object with a "results" array field.
 */
private fun parseWebSearchResults(output: String?): List<WebSearchResult> {
    if (output.isNullOrBlank()) return emptyList()
    return try {
        val element = lenientJson.parseToJsonElement(output)
        val resultsArray = when (element) {
            is JsonArray -> element
            is JsonObject -> element["results"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        resultsArray.mapNotNull { item ->
            try {
                val obj = item.jsonObject
                WebSearchResult(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    url = obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: obj["link"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null,
                    snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull
                        ?: obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: obj["content"]?.jsonPrimitive?.contentOrNull
                        ?: "",
                    favicon = obj["favicon"]?.jsonPrimitive?.contentOrNull,
                )
            } catch (_: Exception) {
                null
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Attempts to parse code execution result from a tool call.
 * Looks at the tool call args for code/language and output for results.
 */
private fun parseCodeExecution(toolCall: AgentToolCall?): CodeExecutionResult? {
    if (toolCall == null) return null
    return try {
        val argsElement = toolCall.args
        val argsObj = argsElement?.jsonObject
        val code = argsObj?.get("code")?.jsonPrimitive?.contentOrNull
            ?: toolCall.function?.arguments?.let { argsStr ->
                try {
                    lenientJson.parseToJsonElement(argsStr).jsonObject["code"]?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) {
                    null
                }
            }
        val language = argsObj?.get("language")?.jsonPrimitive?.contentOrNull
            ?: argsObj?.get("lang")?.jsonPrimitive?.contentOrNull
            ?: toolCall.function?.arguments?.let { argsStr ->
                try {
                    val parsed = lenientJson.parseToJsonElement(argsStr).jsonObject
                    parsed["language"]?.jsonPrimitive?.contentOrNull
                        ?: parsed["lang"]?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) {
                    null
                }
            }

        val outputStr = toolCall.output ?: toolCall.function?.output
        var outputText: String? = null
        var errorText: String? = null
        var exitCode: Int? = null

        if (!outputStr.isNullOrBlank()) {
            try {
                val outputObj = lenientJson.parseToJsonElement(outputStr).jsonObject
                outputText = outputObj["output"]?.jsonPrimitive?.contentOrNull
                    ?: outputObj["stdout"]?.jsonPrimitive?.contentOrNull
                errorText = outputObj["error"]?.jsonPrimitive?.contentOrNull
                    ?: outputObj["stderr"]?.jsonPrimitive?.contentOrNull
                exitCode = outputObj["exit_code"]?.jsonPrimitive?.intOrNull
                    ?: outputObj["exitCode"]?.jsonPrimitive?.intOrNull
                    ?: outputObj["status"]?.jsonPrimitive?.intOrNull
            } catch (_: Exception) {
                // Output is plain text, not JSON
                outputText = outputStr
            }
        }

        CodeExecutionResult(
            language = language,
            code = code,
            output = outputText,
            error = errorText,
            exitCode = exitCode,
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Attempts to parse a memory artifact from tool call output JSON.
 */
private fun parseMemoryArtifact(output: String?): MemoryArtifact? {
    if (output.isNullOrBlank()) return null
    return try {
        val obj = lenientJson.parseToJsonElement(output).jsonObject
        MemoryArtifact(
            title = obj["title"]?.jsonPrimitive?.contentOrNull
                ?: obj["key"]?.jsonPrimitive?.contentOrNull,
            content = obj["content"]?.jsonPrimitive?.contentOrNull
                ?: obj["value"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull,
            key = obj["key"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (_: Exception) {
        // Fall back to treating the whole output as content
        MemoryArtifact(
            title = null,
            content = output,
        )
    }
}

/**
 * Attempts to parse MCP resources from tool call output JSON.
 */
private fun parseMcpResources(output: String?): List<McpResource> {
    if (output.isNullOrBlank()) return emptyList()
    return try {
        val element = lenientJson.parseToJsonElement(output)
        val resourcesArray = when (element) {
            is JsonArray -> element
            is JsonObject -> element["resources"]?.jsonArray
                ?: element["contents"]?.jsonArray
                ?: return emptyList()
            else -> return emptyList()
        }
        resourcesArray.mapNotNull { item ->
            try {
                val obj = item.jsonObject
                McpResource(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: "Resource",
                    uri = obj["uri"]?.jsonPrimitive?.contentOrNull
                        ?: obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null,
                    preview = obj["preview"]?.jsonPrimitive?.contentOrNull
                        ?: obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: obj["text"]?.jsonPrimitive?.contentOrNull,
                )
            } catch (_: Exception) {
                null
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Parses image generation result from a DALL-E or image_gen tool call.
 * Handles various backend response formats: url, image_url, result, filepath, file_id.
 */
private fun parseImageGenResult(
    toolCall: AgentToolCall?,
    baseUrl: String = "",
    attachments: List<com.librechat.android.core.model.Attachment> = emptyList(),
): ImageGenResult {
    if (toolCall == null) return ImageGenResult()

    // Extract prompt from args — args may be a JsonObject or a JsonPrimitive string
    val prompt = try {
        val argsElement = toolCall.args
        when {
            argsElement is kotlinx.serialization.json.JsonObject ->
                argsElement["prompt"]?.jsonPrimitive?.contentOrNull
            argsElement is kotlinx.serialization.json.JsonPrimitive && argsElement.isString ->
                lenientJson.parseToJsonElement(argsElement.content).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull
            else -> null
        } ?: toolCall.function?.arguments?.let { argsStr ->
            try {
                lenientJson.parseToJsonElement(argsStr).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { null }

    val outputStr = toolCall.output ?: toolCall.function?.output
    var imageUrl: String? = null

    // Primary source: match attachment by toolCallId — this is where
    // the backend stores the actual generated image filepath.
    val toolCallId = toolCall.id
    if (toolCallId != null) {
        val attachment = attachments.firstOrNull { it.toolCallId == toolCallId }
        val filepath = attachment?.filepath
        if (filepath != null) {
            imageUrl = when {
                filepath.startsWith("http") -> filepath
                filepath.startsWith("/") && baseUrl.isNotBlank() -> "$baseUrl$filepath"
                baseUrl.isNotBlank() -> "$baseUrl/$filepath"
                else -> filepath
            }
        }
        if (imageUrl == null) {
            val fileId = attachment?.fileId
            if (fileId != null && baseUrl.isNotBlank()) {
                imageUrl = "$baseUrl/api/files/$fileId"
            }
        }
    }

    // Fallback: try parsing the tool output JSON for url/filepath/file_id fields
    if (imageUrl == null && !outputStr.isNullOrBlank()) {
        try {
            val outputObj = lenientJson.parseToJsonElement(outputStr).jsonObject
            imageUrl = outputObj["url"]?.jsonPrimitive?.contentOrNull
                ?: outputObj["image_url"]?.jsonPrimitive?.contentOrNull
                ?: outputObj["result"]?.jsonPrimitive?.contentOrNull
            if (imageUrl == null) {
                val filepath = outputObj["filepath"]?.jsonPrimitive?.contentOrNull
                if (filepath != null) {
                    imageUrl = when {
                        filepath.startsWith("http") -> filepath
                        filepath.startsWith("/images/") && baseUrl.isNotBlank() -> "$baseUrl$filepath"
                        baseUrl.isNotBlank() -> "$baseUrl/images/$filepath"
                        else -> filepath
                    }
                }
            }
            if (imageUrl == null) {
                val fileId = outputObj["file_id"]?.jsonPrimitive?.contentOrNull
                if (fileId != null && baseUrl.isNotBlank()) {
                    imageUrl = "$baseUrl/api/files/$fileId"
                }
            }
        } catch (_: Exception) {
            // Output might be a plain URL
            if (outputStr.startsWith("http")) {
                imageUrl = outputStr.trim()
            }
        }
    }

    return ImageGenResult(
        imageUrl = imageUrl,
        prompt = prompt,
        isGenerating = outputStr.isNullOrBlank() && imageUrl == null,
    )
}

/**
 * Parses log content from a log-type tool call.
 */
private fun parseLogContent(toolCall: AgentToolCall?): LogContent {
    if (toolCall == null) return LogContent()
    return try {
        val outputStr = toolCall.output ?: toolCall.function?.output
        val toolName = toolCall.name ?: toolCall.function?.name ?: "Log Output"

        if (!outputStr.isNullOrBlank()) {
            try {
                val outputObj = lenientJson.parseToJsonElement(outputStr).jsonObject
                val title = outputObj["title"]?.jsonPrimitive?.contentOrNull ?: toolName
                val content = outputObj["content"]?.jsonPrimitive?.contentOrNull
                    ?: outputObj["log"]?.jsonPrimitive?.contentOrNull
                    ?: outputObj["text"]?.jsonPrimitive?.contentOrNull
                    ?: outputStr
                LogContent(title = title, content = content)
            } catch (_: Exception) {
                LogContent(title = toolName, content = outputStr)
            }
        } else {
            LogContent(title = toolName)
        }
    } catch (_: Exception) {
        LogContent()
    }
}
