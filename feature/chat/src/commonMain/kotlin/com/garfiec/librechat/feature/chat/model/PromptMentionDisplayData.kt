package com.garfiec.librechat.feature.chat.model

import androidx.compose.runtime.Immutable

/**
 * A prompt group as shown in the composer's `/` picker.
 *
 * [id] is what the selection handler looks the group up by — matching on name + command collides
 * whenever two groups share a name, firing the usage ping against whichever is found first.
 */
@Immutable
data class PromptMentionDisplayData(
    val id: String,
    val name: String,
    val command: String?,
    val oneliner: String?,
    val category: String?,
    /**
     * Production prompt text, used for the search label so a query can match a prompt's body the way
     * the web client's does. Null when the group carries no prompt.
     */
    val promptText: String?,
)
