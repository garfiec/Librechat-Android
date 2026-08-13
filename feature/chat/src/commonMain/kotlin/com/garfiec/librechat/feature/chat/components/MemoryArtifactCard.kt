package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * One memory write, from the call's `memory` attachment payload when the server sent one and from
 * the tool-call output otherwise. The fallback carries the tool's readable sentence as [content]
 * with no [title] — servers predating the payload, and any call whose artifact was dropped, still
 * render a card that way.
 */
data class MemoryArtifact(
    val title: String?,
    val content: String?,
    val key: String? = null,
    val kind: MemoryChangeKind = MemoryChangeKind.UPDATE,
    /** Set only when [kind] is [MemoryChangeKind.ERROR] and the failure blob parsed. */
    val error: MemoryErrorInfo? = null,
)

/**
 * The inline card for one memory write. Tertiary container colours, switching to the error
 * container when the write was refused (upstream restyles its disclosure the same way).
 */
@Composable
fun MemoryArtifactCard(
    artifact: MemoryArtifact,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }

    val isError = artifact.kind == MemoryChangeKind.ERROR
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    // A delete carries no value, so the card would otherwise be a bare key; an error's value is a
    // JSON blob, which `memoryErrorMessage` turns into the sentence upstream shows.
    val body = when (artifact.kind) {
        MemoryChangeKind.ERROR -> memoryErrorMessage(artifact.error)
        MemoryChangeKind.DELETE -> artifact.content ?: stringResource(Res.string.memory_deleted)
        MemoryChangeKind.UPDATE -> artifact.content
    }

    val memoryCd =
        stringResource(Res.string.cd_memory_artifact, artifact.title ?: stringResource(Res.string.memory_artifact_untitled))
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = memoryCd
            },
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Memory badge
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = if (isError) {
                                stringResource(Res.string.memory_error)
                            } else {
                                stringResource(Res.string.label_memory)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.height(24.dp),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (isError) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                        },
                        labelColor = contentColor,
                    ),
                )

                if (!artifact.title.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = artifact.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Content preview
            if (!body.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        isExpanded = !isExpanded
                    },
                )
            }
        }
    }
}
