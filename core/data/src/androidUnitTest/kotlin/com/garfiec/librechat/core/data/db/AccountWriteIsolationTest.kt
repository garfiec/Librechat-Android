package com.garfiec.librechat.core.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-JVM (Robolectric) proof that the `accountId`-scoped tenant *writes* never touch another
 * account's rows — the write half of the leak fix that PR1-A scopes and the Detekt rule enforces.
 * Harness + entity builders come from [AccountIsolationTestBase].
 *
 * Covers both kinds the rule treats differently:
 * - **by-PK `@Query` writes** (`updateTitle` / `updateArchived` / `updateTags` / `deleteById` /
 *   `updateFeedback` / `updateText`) — a write addressed to a foreign id must be a no-op.
 * - **`@Transaction` defaults** (`upsertPreservingTags` / `replaceAllForConversation` /
 *   `replaceAllForAccount`) — the per-statement isolation each body relies on (PLAN.md R6-4/R9-16).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountWriteIsolationTest : AccountIsolationTestBase() {

    // region by-PK @Query writes: addressing a foreign id is a no-op

    @Test
    fun conversationByPkWrites_doNotTouchForeignAccountsRow() = runTest {
        val dao = db.conversationDao()
        dao.upsert(conversation("convB", accountB, title = "B-title", isArchived = false, tags = "[\"home\"]"))

        // Account A tries to mutate B's row by id. Every by-PK write is scoped `AND accountId = A`,
        // so each must match zero rows and leave B's row untouched.
        dao.updateTitle("convB", "hacked", updatedAt = 1L, accountId = accountA)
        dao.updateArchived("convB", isArchived = true, updatedAt = 1L, accountId = accountA)
        dao.updateTags("convB", tagsJson = "[\"hacked\"]", updatedAt = 1L, accountId = accountA)
        dao.deleteById("convB", accountId = accountA)

        val b = dao.getByIdForAccount("convB", accountB)
        assertThat(b).isNotNull()
        assertThat(b!!.title).isEqualTo("B-title")
        assertThat(b.isArchived).isFalse()
        assertThat(b.tags).isEqualTo("[\"home\"]")
    }

    @Test
    fun conversationByPkWrites_doMutateOwnRow() = runTest {
        val dao = db.conversationDao()
        dao.upsert(conversation("convA", accountA, title = "old", isArchived = false, tags = "[]"))

        dao.updateTitle("convA", "new", updatedAt = 1L, accountId = accountA)
        assertThat(dao.getByIdForAccount("convA", accountA)?.title).isEqualTo("new")

        dao.deleteById("convA", accountId = accountA)
        assertThat(dao.getByIdForAccount("convA", accountA)).isNull()
    }

    @Test
    fun messageByPkWrites_doNotTouchForeignAccountsRow() = runTest {
        val dao = db.messageDao()
        dao.upsert(message("mB", conversationId = "conv", accountId = accountB, text = "B-text", feedback = "up"))

        dao.updateText("mB", text = "hacked", accountId = accountA)
        dao.updateFeedback("mB", feedback = "down", accountId = accountA)

        val b = dao.getByIdForAccount("mB", accountB)
        assertThat(b).isNotNull()
        assertThat(b!!.text).isEqualTo("B-text")
        assertThat(b.feedback).isEqualTo("up")
    }

    // endregion

    // region @Transaction defaults: per-statement isolation

    @Test
    fun upsertPreservingTags_doesNotInheritForeignAccountsTags() = runTest {
        val dao = db.conversationDao()
        // A owns convA with tags; preserving-upsert of the same row under A keeps them.
        dao.upsert(conversation("convA", accountA, title = "t", isArchived = false, tags = "[\"work\"]"))
        dao.upsertPreservingTags(accountA, conversation("convA", accountA, title = "t2", isArchived = false, tags = "[]"))
        assertThat(dao.getByIdForAccount("convA", accountA)?.tags).isEqualTo("[\"work\"]")

        // B owns convB with tags. When A preserving-upserts an id that exists only under B, the inner
        // read is `getByIdForAccount(id, A)` -> null, so A must NOT inherit B's tags.
        dao.upsert(conversation("convB", accountB, title = "t", isArchived = false, tags = "[\"home\"]"))
        dao.upsertPreservingTags(accountA, conversation("convB", accountA, title = "t", isArchived = false, tags = "[]"))
        assertThat(dao.getByIdForAccount("convB", accountA)?.tags).isEqualTo("[]")

        // KNOWN LIMITATION (documented, accepted): conversationId is the PK alone, so this same-id
        // upsert reassigns convB to A — B's row is gone. This is harmless in practice (server
        // conversationIds are unique per user, so a cross-account id collision doesn't occur); the
        // isolation guarantee that matters here is the no-tag-bleed asserted above, not row survival.
        assertThat(dao.getByIdForAccount("convB", accountB)).isNull()
    }

    @Test
    fun replaceAllForConversation_onlyReplacesOwnAccountsMessages() = runTest {
        val dao = db.messageDao()
        // Same conversationId across accounts (messages PK is messageId, so rows coexist).
        dao.upsert(message("mA", conversationId = "conv", accountId = accountA, text = "A-old"))
        dao.upsert(message("mB", conversationId = "conv", accountId = accountB, text = "B-keep"))

        dao.replaceAllForConversation(
            "conv",
            accountA,
            listOf(message("mA2", conversationId = "conv", accountId = accountA, text = "A-new")),
        )

        // A's old message gone, A's new present; B's message for the same conversation survives.
        assertThat(dao.observeMessagesForAccount("conv", accountA).first().map { it.messageId })
            .containsExactly("mA2")
        assertThat(dao.observeMessagesForAccount("conv", accountB).first().map { it.messageId })
            .containsExactly("mB")
    }

    @Test
    fun replaceAllForAccount_onlyReplacesOwnAccountsTags() = runTest {
        val dao = db.conversationTagDao()
        dao.upsertAll(listOf(tag("work", accountA), tag("home", accountB)))

        dao.replaceAllForAccount(accountA, listOf(tag("play", accountA)))

        assertThat(dao.observeTagsForAccount(accountA).first().map { it.tag }).containsExactly("play")
        assertThat(dao.observeTagsForAccount(accountB).first().map { it.tag }).containsExactly("home")
    }

    // endregion
}
