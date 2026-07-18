package com.garfiec.librechat.feature.chat.viewmodel

/**
 * [this] with the per-token streaming churn reset to at-rest values, letting the screen chrome
 * collect `map { it.neutralizeStreamingChurn() }.distinctUntilChanged()`. Chrome must not read
 * these fields — they are always empty there; if one is needed, drop it from this function.
 */
internal fun ChatUiState.neutralizeStreamingChurn(): ChatUiState = copy(
    content = content.copy(
        streamingContent = "",
        activeToolCalls = emptyList(),
        streamingAttachments = emptyList(),
    ),
    comparisonState = comparisonState.copy(
        primaryStreamingContent = "",
        secondaryStreamingContent = "",
        primaryActiveToolCalls = emptyList(),
        secondaryActiveToolCalls = emptyList(),
    ),
)
