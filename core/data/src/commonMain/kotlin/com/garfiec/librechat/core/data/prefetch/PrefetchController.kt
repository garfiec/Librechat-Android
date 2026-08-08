package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.logging.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Starts and stops the prefetcher.
 *
 * Passes must keep running in the [com.garfiec.librechat.core.common.identity.Session]'s own scope.
 * That is what makes account attribution structural — `Session.accountId` is captured when identity
 * resolves and never changes, so no suspend point can move a warmed conversation onto another
 * account — and what cancels in-flight work on a switch, since ending a session cancels its scope.
 */
class PrefetchController(
    private val sessionManager: SessionManager,
    private val gate: PrefetchGate,
    private val engine: PrefetchEngine,
    appScope: CoroutineScope,
) {

    /**
     * Manual run requests from settings.
     *
     * No replay, and a single slot that drops the older request rather than suspending. Two
     * consequences, both wanted: a request made while the gate is shut has no subscriber at all and
     * is discarded on the spot rather than firing at whatever unrelated moment the gate next opens,
     * and repeated taps during a pass coalesce into one queued run instead of stacking up.
     */
    private val manualRuns = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        appScope.launch {
            sessionManager.current.collectLatest { session ->
                if (session == null) return@collectLatest
                session.scope.launch {
                    // collectLatest, not collect: it is the only thing that cancels an in-flight
                    // pass when the gate closes. There is no per-condition teardown anywhere else.
                    gate.isOpen().collectLatest { open ->
                        if (!open) return@collectLatest
                        Diag.d("Prefetch") { "gate opened" }

                        // The rising-edge pass and every manual one run through a single collector,
                        // subscribed before the first pass starts. Subscribing first is what makes a
                        // tap during a long or rate-limited pass land in the buffer instead of being
                        // dropped for want of a collector — which is exactly when someone reaches
                        // for the button. Running them in one coroutine keeps them serialized
                        // against an engine that has no locking of its own, and inside collectLatest
                        // so the gate closing cancels a manual pass as it cancels an automatic one.
                        merge(flowOf(false), manualRuns.map { true }).collect { manual ->
                            if (manual) {
                                Diag.d("Prefetch") { "manual run requested" }
                                engine.resetForManualRun(session.accountId)
                            }
                            engine.run(session.accountId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Asks for a pass now, clearing the breaker first.
     *
     * Honoured only while the gate is open — a request made on mobile data, in battery saver, or
     * with prefetching switched off is dropped rather than overriding the conditions the user set.
     * Callers disable the control instead of relying on that, so the drop is a backstop.
     */
    fun requestRun() {
        manualRuns.tryEmit(Unit)
    }
}
