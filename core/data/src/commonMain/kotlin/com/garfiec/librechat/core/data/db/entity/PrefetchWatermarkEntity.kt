package com.garfiec.librechat.core.data.db.entity

import androidx.room.Entity

/**
 * What the background prefetcher had when it last warmed a conversation's messages.
 *
 * Change detection needs a value the server's `updatedAt` can be compared against, and
 * [ConversationEntity] cannot hold it: that row is a server mirror, overwritten on every list sync,
 * so a locally-written column there would be reset by the very sync that reports the change.
 *
 * [warmedConversationUpdatedAt] is the conversation's `updatedAt` **as it was when we warmed it** —
 * not the time we warmed it. A thread is re-warmed exactly when the server reports a newer value,
 * which is why this feature needs no TTL: an unchanged thread is never re-fetched at all.
 *
 * Must be deleted with the account by `AccountDataPurger`: otherwise a re-login finds watermarks for
 * rows the purge already removed and skips warming them.
 */
@Entity(
    tableName = "prefetch_watermarks",
    primaryKeys = ["accountId", "conversationId"],
)
data class PrefetchWatermarkEntity(
    val accountId: String,
    val conversationId: String,
    val warmedConversationUpdatedAt: Long,
    /** Wall clock of the last warm. Diagnostics only — nothing branches on it. */
    val warmedAt: Long,
)
