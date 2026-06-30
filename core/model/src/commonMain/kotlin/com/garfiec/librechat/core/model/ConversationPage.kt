package com.garfiec.librechat.core.model

/**
 * A single cursor page of conversations returned directly to a caller that
 * holds the list itself rather than observing Room (e.g. the project-filtered
 * views, which are network-direct by design rather than a Room-filtered query).
 */
data class ConversationPage(
    val conversations: List<Conversation>,
    val nextCursor: String?,
)
