package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.logging.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
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
                        engine.run(session.accountId)
                    }
                }
            }
        }
    }
}
