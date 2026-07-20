package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.StreamEvent
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the exact-abort-contract helpers, using frames shaped as the server actually emits
 * them ([AbortFrameFixtures]). [parseTextParts] and [abortPersistedServerSide] mirror server
 * logic verbatim (parsers.ts / the abort route's save gate) — behavior changes here should
 * trace back to an upstream diff, not local preference.
 */
class FinalMessagesTest {

    // ---- parseTextParts ----

    @Test
    fun `joins text parts with the server's space rule`() {
        val parts = listOf(
            MessageContentPart(type = ContentType.TEXT, text = "Half an"),
            MessageContentPart(type = ContentType.TEXT, text = "answer"),
        )
        assertThat(parseTextParts(parts)).isEqualTo("Half an answer")
    }

    @Test
    fun `includes THINK parts like the server's skipReasoning=false call`() {
        // Stopping a reasoning model mid-think: the server persists the reasoning as `text`
        // (parseTextParts is called without skipReasoning on the abort path).
        val parts = listOf(
            MessageContentPart(type = ContentType.THINK, think = "reasoning so far"),
            MessageContentPart(type = ContentType.TEXT, text = "and a sentence"),
        )
        assertThat(parseTextParts(parts)).isEqualTo("reasoning so far and a sentence")
    }

    @Test
    fun `the space rule compares against a literal space not whitespace`() {
        // A chunk ending in a newline still gets the separator — the server checks `!= ' '`,
        // not isWhitespace(). The old client port used isWhitespace() and drifted by one space.
        val parts = listOf(
            MessageContentPart(type = ContentType.TEXT, text = "line one\n"),
            MessageContentPart(type = ContentType.TEXT, text = "line two"),
        )
        assertThat(parseTextParts(parts)).isEqualTo("line one\n line two")
    }

    @Test
    fun `skips non-text parts and empty values without inserting separators`() {
        val parts = listOf(
            MessageContentPart(type = ContentType.TEXT, text = "start"),
            MessageContentPart(type = ContentType.TOOL_CALL),
            MessageContentPart(type = ContentType.TEXT, text = ""),
            MessageContentPart(type = ContentType.TEXT, text = "end"),
        )
        assertThat(parseTextParts(parts)).isEqualTo("start end")
    }

    // ---- abortPersistedServerSide: the 4-way save gate ----

    @Test
    fun `a stopped turn with content and a real response id counts as persisted`() {
        assertThat(AbortFrameFixtures.persistedAbortFrame().abortPersistedServerSide()).isTrue()
    }

    @Test
    fun `a synthesized response id means the server skipped the save`() {
        // The `${userMessageId}_` fallback id is the frame's tell that jobData.responseMessageId
        // was null — one of the four gate conditions — even though content is present.
        assertThat(AbortFrameFixtures.synthesizedIdAbortFrame().abortPersistedServerSide()).isFalse()
    }

    @Test
    fun `a stopped turn with no content parts was not persisted`() {
        assertThat(AbortFrameFixtures.contentlessAbortFrame().abortPersistedServerSide()).isFalse()
    }

    @Test
    fun `a missing request message fails the gate`() {
        val frame = AbortFrameFixtures.persistedAbortFrame().copy(requestMessage = null)
        assertThat(frame.abortPersistedServerSide()).isFalse()
    }

    @Test
    fun `an early abort was never persisted`() {
        assertThat(AbortFrameFixtures.earlyAbortFrame().abortPersistedServerSide()).isFalse()
    }

    // ---- applyAbortContract ----

    @Test
    fun `a persisted response gets its text rebuilt from the content parts`() {
        val normalized = AbortFrameFixtures.persistedAbortFrame().applyAbortContract()

        // Matches the server's own parseTextParts row, so the cached copy equals what a later
        // fetch returns.
        assertThat(normalized.responseMessage?.text).isEqualTo("partial answer")
        assertThat(normalized.requestMessage).isNotNull()
    }

    @Test
    fun `an unpersisted response is dropped from both slots`() {
        val normalized = AbortFrameFixtures.contentlessAbortFrame().applyAbortContract()

        // The server saved no response row: merging one would mint a phantom leaf that the next
        // send uses as a parentMessageId the server has never heard of.
        assertThat(normalized.responseMessage).isNull()
        assertThat(normalized.message).isNull()
        // The user turn IS persisted on a non-early abort — it must survive for caching.
        assertThat(normalized.requestMessage).isNotNull()
    }

    @Test
    fun `a synthesized-id response is dropped even with content present`() {
        val normalized = AbortFrameFixtures.synthesizedIdAbortFrame().applyAbortContract()

        assertThat(normalized.responseMessage).isNull()
    }

    @Test
    fun `an existing text is left alone`() {
        val frame = AbortFrameFixtures.persistedAbortFrame()
        val withText = frame.copy(responseMessage = frame.responseMessage?.copy(text = "already here"))

        assertThat(withText.applyAbortContract().responseMessage?.text).isEqualTo("already here")
    }

    @Test
    fun `the rebuild lands in the legacy message slot when the backend used it`() {
        val frame = AbortFrameFixtures.persistedAbortFrame()
        val legacy = frame.copy(message = frame.responseMessage, responseMessage = null)

        val normalized = legacy.applyAbortContract()

        assertThat(normalized.message?.text).isEqualTo("partial answer")
        assertThat(normalized.responseMessage).isNull()
    }

    @Test
    fun `an unaborted frame passes through untouched`() {
        val frame = StreamEvent.Final(responseMessage = AbortFrameFixtures.abortedResponse())
        assertThat(frame.applyAbortContract()).isEqualTo(frame)
    }
}
