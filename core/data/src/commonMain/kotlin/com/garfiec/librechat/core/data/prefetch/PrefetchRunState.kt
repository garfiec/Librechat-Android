package com.garfiec.librechat.core.data.prefetch

/**
 * A run state together with the account it describes.
 *
 * The account is carried rather than assumed because this engine is a singleton that outlives any
 * one session, while [PrefetchRunState.Stopped] persists until it is explicitly cleared. Without the
 * tag, an account whose server was unreachable hands its verdict to whichever account is active next
 * — a failure report about an account that has never failed.
 */
data class PrefetchAccountRunState(
    val accountId: String?,
    val state: PrefetchRunState,
) {
    fun stateFor(forAccountId: String?): PrefetchRunState =
        if (accountId != null && accountId == forAccountId) state else PrefetchRunState.Idle
}

/**
 * What the prefetcher is doing at this instant.
 *
 * In-memory and deliberately unpersisted: it describes a pass, and a pass never outlives the
 * process. Its job is to separate the two situations an open gate otherwise renders identically —
 * a pass working through stale conversations, and a pass that found nothing to do. Without that
 * distinction "running" is indistinguishable from "broken", which is the whole reason the status
 * readout exists.
 */
sealed interface PrefetchRunState {

    /** No pass in progress: either the gate is shut, or the last pass found nothing stale. */
    data object Idle : PrefetchRunState

    /** Syncing the conversation list, which is what reveals whether anything is stale at all. */
    data object RefreshingList : PrefetchRunState

    data class WarmingMessages(val completed: Int, val total: Int) : PrefetchRunState

    /** Endpoints, models and agents — warmed once per account per process. */
    data object WarmingReferenceData : PrefetchRunState

    /** The server asked us to slow down. Not a failure; the pass resumes when [backoffMillis] elapses. */
    data class RateLimited(val backoffMillis: Long) : PrefetchRunState

    /** Dropping cached messages for conversations that aged out. Local only — no requests. */
    data object Pruning : PrefetchRunState

    /**
     * The breaker tripped: too many consecutive failures, so no further pass will start for this
     * account until it is reset. Surfaced because it is otherwise indistinguishable from an idle
     * prefetcher with nothing to do, while being permanent rather than momentary.
     */
    data object Stopped : PrefetchRunState
}
