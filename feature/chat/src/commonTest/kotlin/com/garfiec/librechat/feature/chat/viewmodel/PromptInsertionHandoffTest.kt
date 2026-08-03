package com.garfiec.librechat.feature.chat.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptInsertionHandoffTest {

    @Test
    fun takeReturnsStagedTextAndClearsIt() {
        val handoff = PromptInsertionHandoff()
        handoff.put("Summarize this")

        assertEquals("Summarize this", handoff.take())
        // Single-slot: a second take must not re-insert the same prompt on the next resume.
        assertNull(handoff.take())
    }

    @Test
    fun takeOnAnEmptySlotIsNull() {
        assertNull(PromptInsertionHandoff().take())
    }

    @Test
    fun stagingTwiceKeepsOnlyTheLatest() {
        val handoff = PromptInsertionHandoff()
        handoff.put("first")
        handoff.put("second")

        assertEquals("second", handoff.take())
        assertNull(handoff.take())
    }
}
