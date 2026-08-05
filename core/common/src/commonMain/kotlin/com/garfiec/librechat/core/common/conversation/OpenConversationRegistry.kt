package com.garfiec.librechat.core.common.conversation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The conversation the user currently has open, or `null` when they are anywhere else.
 *
 * Exists so cache-mutating background work can leave that one conversation alone. Replacing or
 * pruning the rows behind the screen the user is reading is the worst failure available to such
 * work, and it is not otherwise detectable from `:core:data`.
 */
class OpenConversationRegistry {

    private val _openConversationId = MutableStateFlow<String?>(null)

    val openConversationId: StateFlow<String?> = _openConversationId.asStateFlow()

    fun set(conversationId: String?) {
        _openConversationId.value = conversationId
    }
}
