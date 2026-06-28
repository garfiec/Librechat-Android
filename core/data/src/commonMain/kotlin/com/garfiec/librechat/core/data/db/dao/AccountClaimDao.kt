package com.garfiec.librechat.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * One-time *claim* of legacy (pre-row-tenancy) data for the first resolved account.
 *
 * Rows written before the 4→5 migration have `accountId IS NULL`. Because logout never cleared the
 * cache, a NULL row could belong to *any* user who was ever signed in on this device (the leak this
 * feature closes). So the claim attributes NULL rows by the identity they already carry
 * — the `user` (Mongo id) column on `conversations`/`conversation_tags`, and transitively the owning
 * conversation for `messages`/`drafts` (which have no `user` column). Whatever is still NULL after
 * stamping is another user's commingled leftover or un-attributable conv-less data: it is **deleted**,
 * not claimed — fail-safe, never leak; the rows are re-fetchable from the server.
 *
 * The whole claim runs in one [Transaction] and is **idempotent** (re-running stamps nothing new and
 * deletes nothing that survived), so the persisted "claim done" marker is only an optimization, not a
 * correctness dependency.
 *
 * Not a tenant DAO by name, so the AccountScopedDao Detekt rule doesn't force `@CrossAccount` on the
 * transaction default; the individual statements all carry an `accountId IS NULL` predicate and pass.
 */
@Dao
interface AccountClaimDao {

    @Query("UPDATE conversations SET accountId = :accountId WHERE accountId IS NULL AND user = :userKey")
    suspend fun stampConversations(accountId: String, userKey: String)

    @Query("UPDATE conversation_tags SET accountId = :accountId WHERE accountId IS NULL AND user = :userKey")
    suspend fun stampConversationTags(accountId: String, userKey: String)

    @Query(
        "UPDATE messages SET accountId = :accountId WHERE accountId IS NULL AND conversationId IN " +
            "(SELECT conversationId FROM conversations WHERE accountId = :accountId)",
    )
    suspend fun stampMessagesOfClaimedConversations(accountId: String)

    @Query(
        "UPDATE drafts SET accountId = :accountId WHERE accountId IS NULL AND conversation_id IN " +
            "(SELECT conversationId FROM conversations WHERE accountId = :accountId)",
    )
    suspend fun stampDraftsOfClaimedConversations(accountId: String)

    /**
     * Claims the conv-less new-chat compose-box draft (singleton row, PK = the new-chat sentinel key).
     * It has no owning conversation, so the transitive draft stamp can't reach it and the unclaimed
     * sweep would otherwise delete the upgrading user's unsent text. Attributing the single sentinel
     * row to the first post-upgrade account preserves it; isolation still holds afterward because every
     * read is account-filtered. Bounded leak surface: one local compose box on a shared device.
     */
    @Query("UPDATE drafts SET accountId = :accountId WHERE accountId IS NULL AND conversation_id = :newChatKey")
    suspend fun stampNewChatDraft(accountId: String, newChatKey: String)

    @Query("DELETE FROM messages WHERE accountId IS NULL")
    suspend fun deleteUnclaimedMessages()

    @Query("DELETE FROM drafts WHERE accountId IS NULL")
    suspend fun deleteUnclaimedDrafts()

    @Query("DELETE FROM conversations WHERE accountId IS NULL")
    suspend fun deleteUnclaimedConversations()

    @Query("DELETE FROM conversation_tags WHERE accountId IS NULL")
    suspend fun deleteUnclaimedConversationTags()

    /**
     * Claims [accountId]'s legacy rows by [userKey], then deletes everything still unattributed.
     * Stamp before delete so the current owner's messages/drafts are attributed via their conversation
     * (and the conv-less new-chat draft via [newChatKey]) before the sweep removes the remaining NULL
     * rows.
     */
    @Transaction
    suspend fun claimLegacyRows(accountId: String, userKey: String, newChatKey: String) {
        stampConversations(accountId, userKey)
        stampConversationTags(accountId, userKey)
        stampMessagesOfClaimedConversations(accountId)
        stampDraftsOfClaimedConversations(accountId)
        stampNewChatDraft(accountId, newChatKey)

        deleteUnclaimedMessages()
        deleteUnclaimedDrafts()
        deleteUnclaimedConversations()
        deleteUnclaimedConversationTags()
    }
}
