package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatInputParsingTest {

    // ── Trigger boundaries ───────────────────────────────────────────────

    @Test
    fun bareSlashOpensWithAnEmptyQuery() {
        assertEquals("", parseSlashQuery("/"))
    }

    @Test
    fun textAfterSlashIsTheQuery() {
        assertEquals("summ", parseSlashQuery("/summ"))
    }

    @Test
    fun spaceClosesThePicker() {
        // How the user dismisses without deleting what they typed.
        assertNull(parseSlashQuery("/summ arize"))
        assertNull(parseSlashQuery("/ "))
    }

    @Test
    fun slashMustLeadTheInput() {
        // Otherwise dates, paths and fractions would all open the picker mid-sentence.
        assertNull(parseSlashQuery("what is 1/2"))
        assertNull(parseSlashQuery("see docs/readme"))
        assertNull(parseSlashQuery(" /summ"))
    }

    @Test
    fun emptyInputDoesNotTrigger() {
        assertNull(parseSlashQuery(""))
    }

    // ── Filtering ────────────────────────────────────────────────────────

    private fun group(
        id: String,
        name: String,
        oneliner: String? = null,
        command: String? = null,
        promptText: String? = null,
    ) = PromptMentionDisplayData(
        id = id,
        name = name,
        command = command,
        oneliner = oneliner,
        category = null,
        promptText = promptText,
    )

    @Test
    fun groupsWithoutACommandStillMatch() {
        // The server's list projection omits `command`; requiring one leaves the picker
        // permanently empty.
        val groups = listOf(group("1", "Summarize Question", command = null))
        assertEquals(listOf("1"), filterMatchingSlashCommands("summ", groups).map { it.id })
    }

    @Test
    fun matchesOnOneliner() {
        val groups = listOf(group("1", "Untitled", oneliner = "turns notes into a haiku"))
        assertEquals(listOf("1"), filterMatchingSlashCommands("haiku", groups).map { it.id })
    }

    @Test
    fun matchesOnPromptBodyWhenThereIsNoOneliner() {
        val groups = listOf(group("1", "Untitled", promptText = "rewrite this as a haiku"))
        assertEquals(listOf("1"), filterMatchingSlashCommands("haiku", groups).map { it.id })
    }

    @Test
    fun onelinerIsPreferredOverBodyForTheLabel() {
        val groups = listOf(
            group("1", "Untitled", oneliner = "short summary", promptText = "a haiku about cats"),
        )
        // The body isn't part of the label when a oneliner exists, mirroring the web client.
        assertEquals(emptyList(), filterMatchingSlashCommands("haiku", groups).map { it.id })
    }

    @Test
    fun anExactNameOutranksAPromptThatMerelyStartsWithIt() {
        // Web's second key is `command ?? name`; the label key can only reach STARTS_WITH, so
        // without the name fallback these two tie.
        val groups = listOf(
            group("longer", "Summarize Everything", oneliner = "the long one"),
            group("exact", "Summarize", oneliner = "the short one"),
        )
        assertEquals(
            listOf("exact", "longer"),
            filterMatchingSlashCommands("Summarize", groups).map { it.id },
        )
    }

    @Test
    fun emptyQueryShowsEverything() {
        val groups = listOf(group("1", "Alpha"), group("2", "Beta"))
        assertEquals(listOf("1", "2"), filterMatchingSlashCommands("", groups).map { it.id })
    }

    @Test
    fun nonMatchingQueryReturnsNothing() {
        val groups = listOf(group("1", "Alpha"))
        assertEquals(emptyList(), filterMatchingSlashCommands("zzzz", groups).map { it.id })
    }

    @Test
    fun emptyLibraryReturnsNothing() {
        assertEquals(emptyList(), filterMatchingSlashCommands("summ", emptyList()))
    }

    @Test
    fun resultsAreCappedSoTheListCannotSwallowTheScreen() {
        val groups = (1..30).map { group(it.toString(), "Prompt $it") }
        assertEquals(8, filterMatchingSlashCommands("prompt", groups).size)
    }
}
