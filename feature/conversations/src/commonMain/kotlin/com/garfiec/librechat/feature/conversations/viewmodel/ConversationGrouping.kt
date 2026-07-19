package com.garfiec.librechat.feature.conversations.viewmodel

import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import com.garfiec.librechat.core.common.extensions.toInstantOrNull
import com.garfiec.librechat.core.common.extensions.toRelativeDateGroup
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.feature.conversations.components.ConversationDisplayData
import com.garfiec.librechat.feature.conversations.components.toDisplayData

/**
 * Buckets conversations by date label (Today / Yesterday / Previous 7 Days / … / month-year),
 * preserving order within each bucket.
 *
 * The drawer keeps the raw [Conversation]s and the full-screen list maps them to display rows, so
 * the two differ only in that trailing map — the bucketing itself lives here once. Resolves the
 * clock and timezone a single time for the whole list rather than once per row.
 */
internal fun List<Conversation>.groupedByDateBucket(): List<Pair<String, List<Conversation>>> {
    if (isEmpty()) return emptyList()
    val reference = RelativeTimeReference.current()
    return groupBy { conversation ->
        conversation.updatedAt
            ?.toInstantOrNull()
            ?.toRelativeDateGroup(reference)
            ?: "Unknown"
    }.toList()
}

/**
 * Flattens conversations into date-grouped display rows. Shared by the all-conversations list and
 * the project-filtered browse screen so the grouping logic isn't forked.
 */
internal fun groupConversationsByDate(
    conversations: List<Conversation>,
    endpointConfigs: Map<String, EndpointConfig>,
): List<Pair<String, List<ConversationDisplayData>>> =
    conversations.groupedByDateBucket().map { (group, convos) ->
        group to convos.map { it.toDisplayData(endpointConfigs) }
    }
