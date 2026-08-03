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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import org.jetbrains.compose.resources.stringResource

// A dispatcher: each branch emits one card and returns. Wrapping it to satisfy the rule would
// add a layout node to every streaming tool call.
@Suppress("MultipleEmitters")
@Composable
fun StreamingToolCallCard(
    toolCall: ActiveToolCall,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    streamingAttachments: List<Attachment> = emptyList(),
    showImageDescriptions: Boolean = true,
) {
    // Image-gen tool calls render as an ImageGenCard placeholder (matching web): a
    // faux-progress spinner while generating, swapping to the real image the moment
    // its attachment SSE event arrives (linked by toolCallId) — before the final reload.
    if (isImageGenToolCall(toolCall.name.lowercase())) {
        val imageResult = remember(toolCall, streamingAttachments, baseUrl) {
            parseStreamingImageGenResult(toolCall, baseUrl, streamingAttachments)
        }
        ImageGenCard(
            result = imageResult,
            modifier = modifier,
            showDescription = showImageDescriptions,
        )
        return
    }

    // An answered question renders as its Q&A record, so the exchange stays on screen for the
    // rest of the run instead of reappearing only when the message finalizes. An unanswered one
    // never reaches here — `withoutUnansweredQuestions` drops it while the pause card owns it.
    if (isAskUserQuestionToolCall(toolCall.name.lowercase())) {
        val question = remember(toolCall.input) { parseAskUserQuestion(toolCall.input) }
        AskUserQuestionRecordCard(
            question = question,
            answer = toolCall.output.orEmpty(),
            modifier = modifier,
        )
        return
    }

    // Web search: as soon as `web_search` attachments arrive (streamed once per source
    // processed) render the same "Searched the web" sources card the finalized message uses,
    // so sources appear live instead of a generic spinner that only resolves on reload.
    val webSearchSources = remember(toolCall, streamingAttachments) {
        if (isWebSearchToolCall(toolCall.name.lowercase())) {
            collectWebSearchSources(streamingAttachments, toolCall.id)
        } else {
            emptyList()
        }
    }
    if (webSearchSources.isNotEmpty()) {
        WebSearchSourcesCard(results = webSearchSources, modifier = modifier)
    } else {
        GenericStreamingToolCard(
            toolCall = toolCall,
            baseUrl = baseUrl,
            streamingAttachments = streamingAttachments,
            modifier = modifier,
        )
    }
}

/** The default streaming tool-call card: name, progress/complete state, expandable output, and
 *  any files this tool call has generated so far. Used when no specialized card applies. */
@Composable
private fun GenericStreamingToolCard(
    toolCall: ActiveToolCall,
    baseUrl: String,
    streamingAttachments: List<Attachment>,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val canExpand = toolCall.isComplete && !toolCall.output.isNullOrBlank()

    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (canExpand) {
                                Modifier.clickable { isExpanded = !isExpanded }
                            } else {
                                Modifier
                            },
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = toolCall.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )

                    if (toolCall.isComplete) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(Res.string.cd_tool_complete),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (canExpand) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isExpanded && canExpand,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.tool_call_output),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = toolCall.output.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Files this tool call has generated so far (e.g. a code-interpreter PDF/PNG), routed by
        // toolCallId. Office-preview docs are rendered separately (streaming_office_previews).
        ToolCallAttachments(
            attachments = streamingAttachments,
            toolCallId = toolCall.id,
            baseUrl = baseUrl,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
