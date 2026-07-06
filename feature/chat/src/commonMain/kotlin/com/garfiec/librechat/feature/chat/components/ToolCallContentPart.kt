package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

// ─── ToolCallDispatcher ─────────────────────────────────────────────

@Composable
internal fun ToolCallDispatcher(
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
        Column {
            val toolCallCd =
                stringResource(if (isExpanded) Res.string.cd_collapse_tool_call else Res.string.cd_expand_tool_call, toolName)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(12.dp)
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
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
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
