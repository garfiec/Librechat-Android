package com.garfiec.librechat.feature.chat.viewmodel

import androidx.compose.runtime.Immutable
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData

/**
 * Saved presets and available prompt-mention groups. Written only by
 * [com.garfiec.librechat.feature.chat.viewmodel.delegate.PresetPromptDelegate].
 */
@Immutable
data class PresetPromptState(
    val presets: List<PresetDisplayData> = emptyList(),
    val availablePrompts: List<PromptMentionDisplayData> = emptyList(),
)
