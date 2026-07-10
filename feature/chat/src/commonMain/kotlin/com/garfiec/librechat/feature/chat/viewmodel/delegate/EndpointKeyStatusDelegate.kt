package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.endpoint.KeyInvalidation
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.model.endpoint.resolveProviderKeyName
import com.garfiec.librechat.feature.chat.viewmodel.EndpointKeyHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks per-endpoint user-provided-key state for the chat-side model selector
 * so groups whose key is missing/expired can render a "Set API Key" CTA instead
 * of letting the user pick a model that will fail at send time.
 */
class EndpointKeyStatusDelegate(
    private val handle: EndpointKeyHandle,
    private val keyRepository: KeyRepository,
) {

    private var lastKnownConfigs: Map<String, EndpointConfig> = emptyMap()
    private val mutex = Mutex()
    private var currentRecomputeJob: Job? = null

    init {
        handle.scope.launch {
            keyRepository.keyInvalidations.collect { invalidation ->
                if (shouldRespondTo(invalidation)) {
                    launchFanOut(lastKnownConfigs, updateLastKnown = false)
                }
            }
        }
    }

    /**
     * Re-fan-out the key-state fetch for every endpoint that requires a user-provided
     * key. Caches [configs] so a later invalidation-driven recompute can reuse them.
     *
     * Launches the fan-out on [handle.scope] so a slow `getKeyExpiry` GET
     * does not block the upstream `configRepository.endpointConfigs.collect`.
     */
    fun recomputeFor(configs: Map<String, EndpointConfig>) {
        launchFanOut(configs, updateLastKnown = true)
    }

    private fun shouldRespondTo(invalidation: KeyInvalidation): Boolean = when (invalidation) {
        is KeyInvalidation.All -> true
        is KeyInvalidation.ByName -> lastKnownConfigs.any { (endpointName, cfg) ->
            (cfg.userProvide == true || cfg.userProvideURL == true) &&
                resolveProviderKeyName(endpointName, cfg) == invalidation.name
        }
    }

    /**
     * Unified entry point for both `recomputeFor` and invalidation-driven re-runs.
     * Cancels any in-flight fan-out before launching a new one so a fresh invalidation
     * during a slow `getKeyExpiry` GET preempts the stale fan-out instead of serializing
     * behind it.
     */
    private fun launchFanOut(configs: Map<String, EndpointConfig>, updateLastKnown: Boolean) {
        currentRecomputeJob?.cancel()
        currentRecomputeJob = handle.scope.launch {
            mutex.withLock {
                if (updateLastKnown) lastKnownConfigs = configs
                runFanOut(configs)
            }
        }
    }

    private suspend fun runFanOut(configs: Map<String, EndpointConfig>) {
        val gated = configs.filter { (_, cfg) ->
            cfg.userProvide == true || cfg.userProvideURL == true
        }

        if (gated.isEmpty()) {
            if (handle.state.endpointKeyStates.isNotEmpty()) {
                handle.update { endpointKeyStates = emptyMap() }
            }
            return
        }

        // Snapshot last-known states BEFORE the optimistic Loading push so
        // resolveKeyState can preserve them when a transient error (e.g. a 401
        // mid-auth-token-refresh) breaks the per-endpoint fetch. Without this,
        // a single failed GET would demote every user-provide endpoint to greyed
        // even when the user has valid keys on file.
        val prior = handle.state.endpointKeyStates

        // Optimistic Loading push so the model sheet doesn't render an endpoint
        // as "active → greyed" while the fan-out is in flight. Already-resolved
        // values are preserved to avoid flicker on unrelated config tweaks.
        handle.update {
            endpointKeyStates = gated.mapValues { (name, _) ->
                prior[name]?.takeIf { it != KeyState.Loading } ?: KeyState.Loading
            }
        }

        val resolved: Map<String, KeyState> = coroutineScope {
            gated
                .map { (name, cfg) ->
                    async {
                        name to resolveKeyState(name, cfg, prior[name])
                    }
                }
                .map { it.await() }
                .toMap()
        }

        handle.update { endpointKeyStates = resolved }
    }

    private suspend fun resolveKeyState(
        endpointName: String,
        config: EndpointConfig,
        priorState: KeyState?,
    ): KeyState {
        val keyName = resolveProviderKeyName(endpointName, config)
        return when (val state = keyRepository.fetchKeyState(keyName)) {
            is Result.Success -> state.data
            is Result.Error -> {
                Logger.w(throwable = state.exception) {
                    "fetchKeyState failed for $keyName, preserving prior state"
                }
                // Preserve the previously-resolved value so a transient network
                // blip does not flicker a known-good row to greyed. Brand-new
                // endpoints (no prior) and stale Loading entries fall through to
                // Unset so the CTA still surfaces for genuinely unknown rows.
                priorState?.takeIf { it != KeyState.Loading } ?: KeyState.Unset
            }
            Result.Loading -> priorState?.takeIf { it != KeyState.Loading } ?: KeyState.Unset
        }
    }
}
