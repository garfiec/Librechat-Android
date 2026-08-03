package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData

/**
 * A prompt the user picked that can't be inserted until its `{{variables}}` are filled in.
 * [groupId] is retained so the usage ping fires against the right group on confirm.
 */
@Immutable
data class PendingVariablePrompt(
    val groupId: String,
    val template: String,
    val variables: List<String>,
)

/**
 * Saved presets and available prompt groups. Written only by
 * [com.garfiec.librechat.feature.chat.viewmodel.delegate.PresetPromptDelegate].
 */
@Immutable
data class PresetPromptState(
    val presets: List<PresetDisplayData> = emptyList(),
    val availablePrompts: List<PromptMentionDisplayData> = emptyList(),
    val pendingVariablePrompt: PendingVariablePrompt? = null,
)
