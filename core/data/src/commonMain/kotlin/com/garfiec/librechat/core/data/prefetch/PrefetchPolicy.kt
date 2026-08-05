package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.data.db.dao.PrefetchCandidate

/**
 * Decides *what* the prefetcher warms. Pure — no I/O, no clock, no coroutines — because this is the
 * part whose rules are worth pinning exactly, and everything around it needs a live database or a
 * network to exercise.
 */
class PrefetchPolicy {

    /**
     * The conversations worth keeping warm: the [RECENT_LIMIT] most recently updated, plus every
     * pinned one however old.
     *
     * Pinning is the user saying "this one matters" in the only way the app offers, so a pinned
     * conversation stays warm after it has fallen out of the recent window. Archived rows are
     * excluded by the queries that produce these lists — out of sight, so warming them spends the
     * user's bandwidth on something they deliberately put away.
     */
    fun eligible(
        recent: List<PrefetchCandidate>,
        pinned: List<PrefetchCandidate>,
    ): List<PrefetchCandidate> = (recent + pinned).distinctBy { it.conversationId }

    /**
     * The subset of [eligible] that actually needs fetching, in the order to fetch it.
     *
     * Freshness is decided by the server's `updatedAt` against the watermark from the last warm, so
     * an unchanged conversation is never re-fetched — which is why this feature needs no TTL, and why
     * a pass over an unchanged account issues no message requests at all. The list refresh that feeds
     * it still runs.
     *
     * The open conversation is excluded unconditionally. Warming it would replace the rows behind
     * the screen the user is reading, and no ordering or timing makes that acceptable.
     */
    fun selectWork(
        eligible: List<PrefetchCandidate>,
        watermarks: Map<String, Long>,
        openConversationId: String?,
    ): List<PrefetchCandidate> = eligible
        .asSequence()
        .filter { it.conversationId != openConversationId }
        .filter { candidate ->
            val warmed = watermarks[candidate.conversationId]
            warmed == null || warmed < candidate.updatedAt
        }
        // Pinned first, then most recent. If the gate closes partway through — which is the normal
        // way a pass ends, not an exception — what got warmed is what the user is likeliest to open.
        .sortedWith(compareByDescending<PrefetchCandidate> { it.pinned }.thenByDescending { it.updatedAt })
        .toList()

    /**
     * The conversations whose cached messages must survive pruning: everything eligible, plus the
     * open one.
     *
     * The open conversation is named separately rather than assumed to be eligible. It usually is —
     * a conversation being read was recently updated — but "usually" is not a guarantee, and the
     * cost of being wrong is the user's messages vanishing mid-read.
     */
    fun protectedFromPruning(
        eligible: List<PrefetchCandidate>,
        openConversationId: String?,
    ): Set<String> = buildSet {
        eligible.forEach { add(it.conversationId) }
        openConversationId?.let { add(it) }
    }

    companion object {
        const val RECENT_LIMIT = 20
    }
}
