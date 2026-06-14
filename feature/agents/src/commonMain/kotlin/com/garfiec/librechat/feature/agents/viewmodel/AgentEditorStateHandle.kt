package com.garfiec.librechat.feature.agents.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared state accessor passed to all AgentEditorViewModel delegates.
 * Provides read/write access to the editor UI state and a coroutine scope.
 * Mirrors the chat feature's `ChatStateHandle` convention.
 */
class AgentEditorStateHandle(
    val stateFlow: MutableStateFlow<AgentEditorUiState>,
    val scope: CoroutineScope,
) {
    val state: AgentEditorUiState get() = stateFlow.value

    fun update(transform: AgentEditorUiState.() -> AgentEditorUiState) {
        stateFlow.update { it.transform() }
    }
}
