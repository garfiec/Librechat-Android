package com.garfiec.librechat.feature.chat.navigation

import com.garfiec.librechat.core.model.ModelRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-slot hand-off for a home-screen model shortcut / quick-action tap into the shared
 * navigation host. iOS has no deep-link path, so the Swift quick-action handler pushes the tapped
 * (endpoint, model) here (via `IosKoinAccessor.requestModelShortcut`); the shared `LibreChatNavHost`
 * observes [pending] and opens a `NewChat` pre-selected on that model, then [consume]s it. Android
 * routes the same intent through its `librechat://model` deep link instead, so it never sets this bus.
 */
class ModelShortcutBus {
    private val _pending = MutableStateFlow<ModelRef?>(null)
    val pending: StateFlow<ModelRef?> = _pending.asStateFlow()

    fun request(endpoint: String, model: String) {
        if (endpoint.isBlank() || model.isBlank()) return
        _pending.value = ModelRef(endpoint, model)
    }

    fun consume() {
        _pending.value = null
    }
}
