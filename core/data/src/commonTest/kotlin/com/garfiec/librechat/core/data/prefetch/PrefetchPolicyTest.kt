package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.data.db.dao.PrefetchCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrefetchPolicyTest {

    private val policy = PrefetchPolicy()

    private fun candidate(id: String, updatedAt: Long, pinned: Boolean = false) =
        PrefetchCandidate(conversationId = id, updatedAt = updatedAt, pinned = pinned)

    @Test
    fun `eligible unions recent and pinned without duplicating the overlap`() {
        val recent = listOf(candidate("a", 30), candidate("b", 20, pinned = true))
        val pinned = listOf(candidate("b", 20, pinned = true), candidate("old", 1, pinned = true))

        val eligible = policy.eligible(recent, pinned)

        assertEquals(listOf("a", "b", "old"), eligible.map { it.conversationId })
    }

    /** The point of including pinned separately: it survives falling out of the recent window. */
    @Test
    fun `a pinned conversation stays eligible however old it is`() {
        val eligible = policy.eligible(
            recent = List(PrefetchPolicy.RECENT_LIMIT) { candidate("recent-$it", 1000L + it) },
            pinned = listOf(candidate("ancient", 1, pinned = true)),
        )

        assertTrue(eligible.any { it.conversationId == "ancient" })
    }

    @Test
    fun `work is limited to conversations the server has changed since the last warm`() {
        val eligible = listOf(
            candidate("unchanged", updatedAt = 100),
            candidate("changed", updatedAt = 200),
            candidate("never-warmed", updatedAt = 50),
        )
        val watermarks = mapOf("unchanged" to 100L, "changed" to 150L)

        val work = policy.selectWork(eligible, watermarks, openConversationId = null)

        assertEquals(setOf("changed", "never-warmed"), work.map { it.conversationId }.toSet())
    }

    /**
     * An equal watermark means "warmed against exactly this version". Treating it as stale would make
     * the prefetcher re-fetch every eligible conversation on every pass, forever.
     */
    @Test
    fun `an equal watermark is not stale`() {
        val work = policy.selectWork(
            eligible = listOf(candidate("a", updatedAt = 100)),
            watermarks = mapOf("a" to 100L),
            openConversationId = null,
        )

        assertTrue(work.isEmpty())
    }

    /** Replacing the rows behind the screen the user is reading is the worst failure available. */
    @Test
    fun `the open conversation is never warmed even when stale`() {
        val work = policy.selectWork(
            eligible = listOf(candidate("open", updatedAt = 200), candidate("other", updatedAt = 200)),
            watermarks = emptyMap(),
            openConversationId = "open",
        )

        assertEquals(listOf("other"), work.map { it.conversationId })
    }

    /**
     * A pass usually ends by the gate closing partway through, not by finishing, so the order decides
     * what actually gets warmed.
     */
    @Test
    fun `work is ordered pinned first then most recently updated`() {
        val work = policy.selectWork(
            eligible = listOf(
                candidate("old-pinned", updatedAt = 1, pinned = true),
                candidate("newest", updatedAt = 300),
                candidate("middle", updatedAt = 200),
            ),
            watermarks = emptyMap(),
            openConversationId = null,
        )

        assertEquals(listOf("old-pinned", "newest", "middle"), work.map { it.conversationId })
    }

    @Test
    fun `pruning protects everything eligible`() {
        val eligible = listOf(candidate("a", 10), candidate("b", 20))

        val protectedIds = policy.protectedFromPruning(eligible, openConversationId = null)

        assertEquals(setOf("a", "b"), protectedIds)
    }

    /**
     * Named separately rather than assumed eligible. A conversation being read is *usually* recently
     * updated, but "usually" is not a guarantee and the cost of being wrong is the user's messages
     * disappearing mid-read.
     */
    @Test
    fun `pruning protects the open conversation even when it is not eligible`() {
        val protectedIds = policy.protectedFromPruning(
            eligible = listOf(candidate("a", 10)),
            openConversationId = "open-but-ancient",
        )

        assertTrue("open-but-ancient" in protectedIds)
        assertFalse("b" in protectedIds)
    }
}
