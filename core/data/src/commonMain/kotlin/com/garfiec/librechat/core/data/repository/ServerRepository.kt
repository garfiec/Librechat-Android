package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.network.client.ServerHeadersProvider

/**
 * Owner of the per-server gateway headers (issue #287), and the [ServerHeadersProvider] the HTTP
 * clients read them through.
 *
 * [ServerHeadersProvider.headersFor] is deliberately **non-suspend**, because it is called inside
 * `SwitchGate`'s lock and in the request pipeline's `State` phase. Persistence therefore stays behind
 * an in-memory map guarded by [ServerHeadersProvider.awaitWarm], and the storage medium is invisible
 * at the injection site.
 */
interface ServerRepository : ServerHeadersProvider {

    /**
     * The headers configured for [serverUrl], once the store has warmed up, or **null** when they
     * could not be read — either the store as a whole or this server's entry alone.
     *
     * Null is not "none". An editor that renders an unreadable store as an empty list tells the user
     * their credential is gone, and a save from that state would then really delete it — which is why
     * [setHeaders] refuses that particular write rather than trusting callers to remember.
     */
    suspend fun headersForServer(serverUrl: String): Map<String, String>?

    /**
     * Persist [headers] for [serverUrl], replacing whatever was there.
     *
     * An **empty** map is the clear, and it is refused while this server's stored value could not be
     * read ([HeaderWriteFailure.UnverifiedDelete]). A **non-empty** map is never a clear, even if
     * nothing in it survives sanitisation ([HeaderWriteFailure.NothingUsable]) — a call that asked to
     * set a credential must not end up destroying one.
     *
     * Callers must not report success on a [HeaderWriteResult.Refused]: a UI that confirms "saved"
     * and clears its dirty flag leaves the user believing a credential is stored that never reached
     * disk, with no way to retry.
     */
    suspend fun setHeaders(serverUrl: String, headers: Map<String, String>): HeaderWriteResult
}

/** Why a header write did not happen. */
enum class HeaderWriteFailure {
    /** The URL yields no server id, so there is nothing to file the headers under. */
    NoServer,

    /** The store rejected the write. The typed rows are worth keeping so it can be retried. */
    StorageUnavailable,

    /**
     * An empty write — a delete — while the stored value could not be read. Nothing was destroyed,
     * but the delete has to be re-made from an editor that actually loaded.
     */
    UnverifiedDelete,

    /**
     * Every pair in a non-empty write failed sanitisation, so honouring it would delete rather than
     * set. Both editors validate first, so this cannot come from the UI; it exists because
     * `sanitize` is the last line of defence for callers that don't — an import path, or a future
     * transport.
     */
    NothingUsable,
}

/** Outcome of [ServerRepository.setHeaders]. */
sealed interface HeaderWriteResult {
    data object Saved : HeaderWriteResult

    data class Refused(val reason: HeaderWriteFailure) : HeaderWriteResult
}
