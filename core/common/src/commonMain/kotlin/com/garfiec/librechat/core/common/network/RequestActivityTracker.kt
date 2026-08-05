package com.garfiec.librechat.core.common.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Counts the HTTP work the user is waiting on, so deferred work can stay out of its way.
 *
 * "User-initiated" must stay defined by exclusion — everything counts unless it carries
 * [PrefetchMarker]. Inverted to opt-in, a caller that forgot to register would silently starve the
 * user's request rather than merely delay background work.
 */
class RequestActivityTracker {

    private val _userInFlight = MutableStateFlow(0)

    val userInFlight: StateFlow<Int> = _userInFlight.asStateFlow()

    fun begin() {
        _userInFlight.update { it + 1 }
    }

    /**
     * Floors at zero. An unbalanced [end] is a bug, but the failure mode of letting the count go
     * negative is that the app looks permanently idle and background work runs during a live
     * request — the exact thing this class exists to prevent.
     */
    fun end() {
        _userInFlight.update { (it - 1).coerceAtLeast(0) }
    }

    /** Runs [block] counted as user-initiated work, releasing on completion, failure or cancellation. */
    suspend fun <T> counted(block: suspend () -> T): T {
        begin()
        return try {
            block()
        } finally {
            end()
        }
    }
}
