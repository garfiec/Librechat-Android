package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable

/**
 * Inline edit-field UI state for editing a message's text in place. Owned by
 * [com.garfiec.librechat.feature.chat.viewmodel.delegate.MessageEditingDelegate].
 */
@Immutable
data class MessageEditingState(
    val editingMessageId: String? = null,
    val editingText: String = "",
)
