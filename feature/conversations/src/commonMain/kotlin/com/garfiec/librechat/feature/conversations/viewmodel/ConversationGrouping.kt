package com.garfiec.librechat.feature.conversations.viewmodel

import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import com.garfiec.librechat.core.common.extensions.toInstantOrNull
import com.garfiec.librechat.core.common.extensions.toRelativeDateGroup
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.feature.conversations.components.ConversationDisplayData
import com.garfiec.librechat.feature.conversations.components.toDisplayData
import kotlin.time.Instant

/**
 * A conversation paired with its parsed `updatedAt`.
 *
 * Exists so the timestamp is parsed exactly once per conversation per emission. Bucketing needs the
 * `Instant` to pick a date group and the display mapping needs it for the row, and without carrying
 * it between the two, both ends call `Instant.parse` on the same string — on the main thread, for
 * every conversation, on every Room emission.
 */
data class DatedConversation(
    val conversation: Conversation,
    val updatedAt: Instant?,
)

/**
 * Buckets conversations by date label (Today / Yesterday / Previous 7 Days / … / month-year),
 * preserving order within each bucket.
 *
 * The drawer keeps the raw conversations and the full-screen list maps them to display rows, so the
 * two differ only in that trailing map — the bucketing itself lives here once.
 *
 * [reference] is a parameter rather than a `Clock.System.now()` read so that (a) the whole list
 * buckets against one instant instead of one per row, and (b) callers can re-run grouping from
 * `dayBoundaryReferences()` when the date changes underneath an open list.
 */
internal fun List<Conversation>.groupedByDateBucket(
    reference: RelativeTimeReference = RelativeTimeReference.current(),
): List<Pair<String, List<DatedConversation>>> {
    if (isEmpty()) return emptyList()
    return map { DatedConversation(it, it.updatedAt?.toInstantOrNull()) }
        .groupBy { it.updatedAt?.toRelativeDateGroup(reference) ?: "Unknown" }
        .toList()
}

/**
 * Flattens conversations into date-grouped display rows. Shared by the all-conversations list and
 * the project-filtered browse screen so the grouping logic isn't forked.
 */
internal fun groupConversationsByDate(
    conversations: List<Conversation>,
    endpointConfigs: Map<String, EndpointConfig>,
    reference: RelativeTimeReference = RelativeTimeReference.current(),
): List<Pair<String, List<ConversationDisplayData>>> =
    conversations.groupedByDateBucket(reference).map { (group, dated) ->
        group to dated.map { it.conversation.toDisplayData(endpointConfigs, it.updatedAt) }
    }
