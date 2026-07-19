package com.garfiec.librechat.feature.conversations.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.common.extensions.toRelativeTimeString
import com.garfiec.librechat.core.model.EModelEndpoint
import com.garfiec.librechat.core.ui.components.EndpointIcon
import com.garfiec.librechat.feature.conversations.resources.*
import com.garfiec.librechat.feature.conversations.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConversationItem(
    data: ConversationDisplayData,
    onClick: () -> Unit,
    onActionsClick: () -> Unit,
    modifier: Modifier = Modifier,
    bookmarksEnabled: Boolean = true,
) {
    // Keyed on the reference as well as the row: the reference is what advances with the wall clock,
    // so without it in the key this memo would outlive the label's correctness (see
    // LocalRelativeTimeReference).
    val reference = LocalRelativeTimeReference.current
    val relativeTime = remember(data.updatedAt, reference) {
        data.updatedAt?.toRelativeTimeString(reference) ?: ""
    }

    val endpointLabel = remember(data.endpoint) {
        data.endpoint?.toDisplayLabel() ?: "Chat"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EndpointIcon(
            endpointName = data.endpoint,
            iconUrl = data.endpointIconUrl,
            size = 24.dp,
            contentDescription = endpointLabel,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = data.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = endpointLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val modelName = data.model
                if (modelName != null) {
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                if (relativeTime.isNotEmpty()) {
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        if (bookmarksEnabled && data.isBookmarked) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = stringResource(Res.string.cd_bookmarked),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        IconButton(onClick = onActionsClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(Res.string.cd_conversation_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun String.toDisplayLabel(): String = when (EModelEndpoint.fromName(this)) {
    EModelEndpoint.OPENAI -> "OpenAI"
    EModelEndpoint.AZURE_OPENAI -> "Azure"
    EModelEndpoint.GOOGLE -> "Google"
    EModelEndpoint.ANTHROPIC -> "Anthropic"
    EModelEndpoint.ASSISTANTS -> "Assistants"
    EModelEndpoint.AZURE_ASSISTANTS -> "Azure Assistants"
    EModelEndpoint.AGENTS -> "Agents"
    EModelEndpoint.CUSTOM -> "Custom"
    EModelEndpoint.BEDROCK -> "Bedrock"
    null -> this
}
