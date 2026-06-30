package com.garfiec.librechat.feature.conversations.viewmodel

import com.garfiec.librechat.core.common.extensions.toInstantOrNull
import com.garfiec.librechat.core.common.extensions.toRelativeDateGroup
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.feature.conversations.components.ConversationDisplayData
import com.garfiec.librechat.feature.conversations.components.toDisplayData

/**
 * Flattens conversations into date-grouped display rows (Today / Yesterday /
 * Previous 7 Days / … / month-year). Shared by the all-conversations list and
 * the project-filtered browse screen so the grouping logic isn't forked.
 */
internal fun groupConversationsByDate(
    conversations: List<Conversation>,
    endpointConfigs: Map<String, EndpointConfig>,
): List<Pair<String, List<ConversationDisplayData>>> {
    if (conversations.isEmpty()) return emptyList()
    return conversations
        .groupBy { conversation ->
            conversation.updatedAt
                ?.toInstantOrNull()
                ?.toRelativeDateGroup()
                ?: "Unknown"
        }
        .map { (group, convos) ->
            group to convos.map { it.toDisplayData(endpointConfigs) }
        }
}
