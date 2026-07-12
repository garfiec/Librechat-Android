package com.garfiec.librechat.feature.conversations.drawer

import com.garfiec.librechat.core.common.extensions.formatMonthAbbrev
import com.garfiec.librechat.core.common.extensions.toInstantOrNull
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.SAVED_TAG
import com.garfiec.librechat.core.model.resolveEndpointIconUrl
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

internal data class DrawerDataSnapshot(
    val grouped: List<Pair<String, List<Conversation>>>,
    val activeId: String?,
    val favConvos: List<Conversation>,
    val pinnedConvos: List<Conversation>,
    val query: String,
)

internal fun Conversation.toDrawerDisplayData(
    activeConversationId: String?,
    endpointConfigs: Map<String, EndpointConfig>,
): DrawerConversationDisplayData {
    val convId = conversationId ?: ""
    return DrawerConversationDisplayData(
        conversationId = convId,
        title = title ?: "New Chat",
        model = model,
        endpoint = endpoint,
        relativeTime = updatedAt?.toInstantOrNull()?.toRelativeTimeString() ?: "",
        isActive = convId == activeConversationId,
        isFavorite = SAVED_TAG in tags,
        isPinned = pinned == true,
        chatProjectId = chatProjectId,
        tags = tags,
        endpointIconUrl = resolveEndpointIconUrl(endpointConfigs),
    )
}

internal fun Instant.toRelativeTimeString(): String {
    val now = Clock.System.now()
    val duration = now - this
    val minutes = duration.inWholeMinutes
    val hours = duration.inWholeHours
    val days = duration.inWholeDays

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val date = toLocalDateTime(TimeZone.currentSystemDefault()).date
            "${formatMonthAbbrev(date.monthNumber)} ${date.dayOfMonth}"
        }
    }
}
