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
import androidx.compose.runtime.saveable.rememberSaveable
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

// A dispatcher: each branch emits one card and returns. Wrapping it to satisfy the rule would
// add a layout node to every tool call in every message.
@Suppress("MultipleEmitters")
@Composable
internal fun ToolCallDispatcher(
    part: MessageContentPart,
    baseUrl: String,
    attachments: List<Attachment>,
    showImageDescriptions: Boolean,
    modifier: Modifier = Modifier,
    // Scopes every expand state below this point. A tool call's own id when it has one — the card
    // then keeps its state across a reorder — falling back to the caller's per-part key.
    stateKey: String = "",
    allowSubagentCard: Boolean = true,
    // True while this call renders inside an activity group, which hoists its tool calls' files out.
    hideAttachments: Boolean = false,
) {
    val toolCall = part.toolCall
    val cardKey = toolCall?.id?.takeIf { it.isNotEmpty() } ?: stateKey
    val toolName = toolCall?.name ?: toolCall?.function?.name ?: "Tool Call"
    val toolNameLower = toolName.lowercase()
    val output = toolCall?.output ?: toolCall?.function?.output

    // Subagent tool_call → collapsible trace card (v0.8.6). Live progress comes
    // from LocalSubagentProgress (keyed by the parent tool_call id); persisted
    // `subagentContent` (reload) takes precedence inside the card. Depth-1:
    // nested parts pass allowSubagentCard=false so this never recurses. Nested parts
    // render their own attachments, so this branch keeps its pass-through return.
    // hideAttachments is deliberately NOT forwarded: a group only collects the ids of its OWN
    // parts, so a subagent's nested calls are never hoisted and suppressing them here would
    // render them nowhere.
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
            stateKey = cardKey,
        )
        return
    }

    // A settled `ask_user_question` call is a Q&A exchange, not a tool run — render the record
    // rather than the call. Its arguments carry the question and options, its output the answer.
    if (isAskUserQuestionToolCall(toolNameLower)) {
        val question = remember(toolCall) {
            parseAskUserQuestion(toolCall?.args) ?: parseAskUserQuestion(toolCall?.function?.arguments)
        }
        AskUserQuestionRecordCard(
            question = question,
            answer = output.orEmpty(),
            modifier = modifier,
            failed = toolCall?.inputValidationError == true,
        )
        return
    }

    // Shipped ahead of upstream's own presentation, which it scopes as a follow-up slice — kept to
    // swapping the label so reworking it stays a one-line change.
    val intent = remember(toolCall) {
        parseToolIntent(toolCall?.args) ?: parseToolIntent(toolCall?.function?.arguments)
    }
    val displayName = intent ?: toolName
    // Once it is the card's title, leaving it in the expanded argument dump prints it twice.
    val displayArgs = remember(toolCall, intent) {
        if (intent == null) toolCall?.function?.arguments else argsWithoutIntent(toolCall?.function?.arguments)
    }

    val isImageGen = isImageGenToolCall(toolNameLower)

    // The card, then (for every non-image-gen tool) the files this tool call generated. Web passes
    // each tool call's attachments to every tool component; ToolCallAttachments skips the non-file
    // pseudo-types. Image-gen already consumes its attachment as the generated image.
    Column(modifier = modifier) {
        val cardModifier = Modifier.fillMaxWidth()
        when {
            toolNameLower.startsWith(ToolConstants.LC_TRANSFER_TO_PREFIX) -> {
                val target = toolName.substring(ToolConstants.LC_TRANSFER_TO_PREFIX.length).ifBlank { toolName }
                AgentHandoffCard(
                    handoff = AgentHandoff(fromAgent = null, toAgent = target, reason = null),
                    modifier = cardModifier,
                )
            }
            isWebSearchToolCall(toolNameLower) -> {
                // The real sources arrive as `web_search` attachments (organic + topStories),
                // not in the tool-call output — prefer them, falling back to output parsing.
                val results = remember(attachments, toolCall?.id, output) {
                    collectWebSearchSources(attachments, toolCall?.id)
                        .ifEmpty { parseWebSearchResults(output) }
                }
                if (results.isNotEmpty()) {
                    WebSearchSourcesCard(results = results, modifier = cardModifier)
                } else {
                    GenericToolCallCard(displayName, displayArgs, output, cardModifier, cardKey)
                }
            }
            isCodeExecutionToolCall(toolNameLower) -> {
                val result = remember(toolCall, toolNameLower) {
                    parseCodeExecution(toolCall)?.let { r ->
                        if (r.language == null && isBashToolCall(toolNameLower)) r.copy(language = "bash") else r
                    }
                }
                if (result != null) {
                    CodeExecutionCard(result = result, modifier = cardModifier)
                } else {
                    GenericToolCallCard(displayName, displayArgs, output, cardModifier, cardKey)
                }
            }
            toolNameLower.contains("memory") -> {
                val artifact = remember(output) { parseMemoryArtifact(output) }
                if (artifact != null) {
                    MemoryArtifactCard(artifact = artifact, modifier = cardModifier)
                } else {
                    GenericToolCallCard(displayName, displayArgs, output, cardModifier, cardKey)
                }
            }
            toolNameLower.contains("mcp") -> {
                val resources = remember(output) { parseMcpResources(output) }
                if (resources.isNotEmpty()) {
                    McpResourceCarousel(resources = resources, modifier = cardModifier)
                } else {
                    GenericToolCallCard(displayName, displayArgs, output, cardModifier, cardKey)
                }
            }
            isImageGen -> {
                val imageResult = remember(toolCall, baseUrl, attachments) {
                    parseImageGenResult(toolCall, baseUrl, attachments)
                }
                ImageGenCard(
                    result = imageResult,
                    showDescription = showImageDescriptions,
                    hideImages = hideAttachments,
                    modifier = cardModifier,
                )
            }
            toolNameLower.contains("log") -> {
                val logContent = remember(toolCall) { parseLogContent(toolCall) }
                LogContentCard(log = logContent, modifier = cardModifier)
            }
            else -> {
                GenericToolCallCard(displayName, displayArgs, output, cardModifier, cardKey)
            }
        }

        if (!isImageGen && !hideAttachments) {
            ToolCallAttachments(
                attachments = attachments,
                toolCallId = toolCall?.id,
                baseUrl = baseUrl,
                modifier = Modifier.padding(top = 8.dp),
            )
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
    stateKey: String = "",
) {
    // Saveable: this card is inside a LazyColumn item, so plain `remember` state is disposed when
    // the message scrolls out of the viewport and an expanded call silently re-collapses.
    var isExpanded by rememberSaveable(key = "toolcall:$stateKey") { mutableStateOf(false) }

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
