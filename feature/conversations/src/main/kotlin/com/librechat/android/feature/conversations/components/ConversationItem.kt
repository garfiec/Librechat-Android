package com.librechat.android.feature.conversations.components

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
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librechat.android.core.common.extensions.toInstantOrNull
import com.librechat.android.core.model.Conversation
import com.librechat.android.core.ui.components.endpointIconRes
import com.librechat.android.core.ui.components.isMonochromeEndpointIcon
import com.librechat.android.feature.conversations.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Lightweight snapshot of the fields ConversationItem actually renders.
 * Avoids passing the full 28-field Conversation data class through composition,
 * letting Compose skip recomposition when only irrelevant fields change.
 */
@Immutable
data class ConversationDisplayData(
    val conversationId: String,
    val title: String,
    val endpoint: String?,
    val model: String?,
    val updatedAt: String?,
    val isBookmarked: Boolean,
)

fun Conversation.toDisplayData(bookmarkedIds: Set<String>) = ConversationDisplayData(
    conversationId = conversationId ?: "",
    title = title ?: "New Chat",
    endpoint = endpoint,
    model = model,
    updatedAt = updatedAt,
    isBookmarked = conversationId in bookmarkedIds,
)

@Composable
fun ConversationItem(
    data: ConversationDisplayData,
    onClick: () -> Unit,
    onActionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val relativeTime = remember(data.updatedAt) {
        data.updatedAt?.toInstantOrNull()?.toRelativeTimeString() ?: ""
    }

    val endpointLabel = remember(data.endpoint) {
        endpointWireDisplayLabel(data.endpoint)
    }

    val endpointDrawable = remember(data.endpoint) {
        endpointIconRes(data.endpoint)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (endpointDrawable != null) {
            val isMonochrome = isMonochromeEndpointIcon(data.endpoint)
            Icon(
                painter = painterResource(id = endpointDrawable),
                contentDescription = endpointLabel,
                modifier = Modifier.size(24.dp),
                tint = if (isMonochrome) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.Unspecified
                },
            )
        } else {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = endpointLabel,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                        text = " \u00B7 ",
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
                        text = " \u00B7 ",
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

        if (data.isBookmarked) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = stringResource(R.string.cd_bookmarked),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        IconButton(onClick = onActionsClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_conversation_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Instant.toRelativeTimeString(): String {
    val now = Instant.now()
    val minutes = ChronoUnit.MINUTES.between(this, now)
    val hours = ChronoUnit.HOURS.between(this, now)
    val days = ChronoUnit.DAYS.between(this, now)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

private fun endpointWireDisplayLabel(wire: String?): String {
    if (wire.isNullOrBlank()) return "Chat"
    return when (wire) {
        "openAI" -> "OpenAI"
        "azureOpenAI" -> "Azure"
        "google" -> "Google"
        "anthropic" -> "Anthropic"
        "assistants" -> "Assistants"
        "azureAssistants" -> "Azure Assistants"
        "agents" -> "Agents"
        "custom" -> "Custom"
        "bedrock" -> "Bedrock"
        else -> wire
    }
}
