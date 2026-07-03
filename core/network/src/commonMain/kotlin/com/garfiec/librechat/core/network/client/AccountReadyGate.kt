package com.garfiec.librechat.core.network.client

/**
 * Completes once the account roster has been seeded and the token mirror reconciled at cold start.
 *
 * The token store seeds its cached bearer synchronously from a secure-storage mirror at construction —
 * fast, but possibly *stale* if a crash left the mirror diverged from the durable roster pointer. The
 * roster seed reconciles them (mirror-follows-roster) and drives the server URL from the active entry.
 * HTTP admission ([ServerUrlReadyPlugin]) and first-frame routing await this gate so no request flies —
 * and no auth route is chosen — against an unreconciled mirror bearer or a pre-roster server URL.
 *
 * Implemented by the account registry in `:core:data`; this interface lives here so the network layer
 * can depend on it without a `:core:data` back-edge.
 */
interface AccountReadyGate {
    suspend fun awaitReady()
}
