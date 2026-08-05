package com.garfiec.librechat.core.common.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is currently on screen.
 *
 * Starts `false`: nothing may treat "not yet reported" as foreground, since the first report only
 * arrives once the UI composes and background work registered at Koin start would otherwise run
 * during cold start — the worst possible moment for it.
 */
class ForegroundSignal {

    private val _isForeground = MutableStateFlow(false)

    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun set(foreground: Boolean) {
        _isForeground.value = foreground
    }
}
