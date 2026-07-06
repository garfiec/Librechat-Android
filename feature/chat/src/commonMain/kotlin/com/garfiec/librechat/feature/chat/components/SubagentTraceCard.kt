package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_collapse
import com.garfiec.librechat.feature.chat.resources.cd_collapse_subagent
import com.garfiec.librechat.feature.chat.resources.cd_expand
import com.garfiec.librechat.feature.chat.resources.cd_expand_subagent
import com.garfiec.librechat.feature.chat.resources.label_subagent
import com.garfiec.librechat.feature.chat.viewmodel.SubagentTrace
import org.jetbrains.compose.resources.stringResource

/**
 * Collapsible card rendering a child agent's run (v0.8.6 subagents). Mirrors the
 * thinking/summary cards: a tappable header with the subagent's name + a live
 * ticker (phase), an in-progress spinner until the run resolves, and an
 * expandable body that renders the child's nested content parts (reasoning /
 * tool calls / text) using the shared [ContentPartDispatcher].
 *
 * Two sources, in precedence order:
 *  - [persistedParts]: the authoritative `AgentToolCall.subagentContent` from a
 *    reloaded message. Always preferred when present.
 *  - [liveTrace]: the live buffer folded from `on_subagent_update` SSE events
 *    while streaming. Used only when no persisted content exists yet.
 *
 * Depth is capped at 1: nested parts are rendered with `allowSubagentCard=false`
 * so a subagent tool_call inside a subagent never recurses into another trace
 * card (it falls back to the generic tool-call card).
 */
@Composable
internal fun SubagentTraceCard(
    persistedParts: List<MessageContentPart>?,
    liveTrace: SubagentTrace?,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    attachments: List<Attachment> = emptyList(),
    showImageDescriptions: Boolean = true,
) {
    // Reload precedence: persisted content is authoritative over any live buffer.
    val parts = persistedParts?.takeIf { it.isNotEmpty() } ?: liveTrace?.parts.orEmpty()
    val isComplete = persistedParts != null || (liveTrace?.isComplete ?: true)
    val title = liveTrace?.subagentType?.takeIf { it.isNotBlank() }
        ?: liveTrace?.label?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.label_subagent)

    var isExpanded by remember { mutableStateOf(false) }
    val toggleCd =
        stringResource(if (isExpanded) Res.string.cd_collapse_subagent else Res.string.cd_expand_subagent, title)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clickable { isExpanded = !isExpanded }
                    .padding(12.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = toggleCd
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (!isComplete) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    stringResource(if (isExpanded) Res.string.cd_collapse else Res.string.cd_expand),
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    parts.forEach { part ->
                        Spacer(modifier = Modifier.padding(top = 6.dp))
                        ContentPartDispatcher(
                            part = part,
                            baseUrl = baseUrl,
                            attachments = attachments,
                            showImageDescriptions = showImageDescriptions,
                            // Depth-1 guard: a nested subagent renders flat, never another card.
                            allowSubagentCard = false,
                        )
                    }
                }
            }
        }
    }
}
