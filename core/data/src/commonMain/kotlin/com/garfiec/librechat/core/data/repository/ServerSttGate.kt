package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result

/**
 * Loads "is server STT enabled" once and latches the first success, so a transient fetch failure
 * reads as *unknown* (retry on the next user action) rather than a definitive "disabled" that would
 * hide the External engine for the whole session.
 *
 * Extracted so the chat mic path ([VoiceInputDelegate]) and the settings STT dialog
 * ([SpeechSettingsDelegate]) share one retry/latch policy instead of each re-deriving the
 * Success/Error/Loading decision — the two speech surfaces would otherwise drift on when External is
 * offered. Not thread-safe: confine to a single (Main-dispatched) scope like its callers do.
 */
class ServerSttGate(private val speechRepository: SpeechRepository) {

    /** True once a fetch has succeeded at least once — until then a `false` value is "unknown". */
    var loaded: Boolean = false
        private set

    /**
     * Fetch the server STT flag. Returns the definitive `true`/`false` on success (and latches
     * [loaded]); `null` when the fetch couldn't determine it (transient error / still loading) so the
     * caller keeps treating it as unknown and retries later rather than reporting STT disabled.
     */
    suspend fun refresh(): Boolean? = when (val result = speechRepository.isServerSttEnabled()) {
        is Result.Success -> {
            loaded = true
            result.data
        }
        is Result.Error -> null
        is Result.Loading -> null
    }
}
