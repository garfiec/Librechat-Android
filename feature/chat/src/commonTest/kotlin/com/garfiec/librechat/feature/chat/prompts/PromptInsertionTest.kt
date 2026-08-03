package com.garfiec.librechat.feature.chat.prompts

import com.garfiec.librechat.core.model.ProductionPromptEmbed
import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptInsertionTest {

    private fun group(
        productionPrompt: String? = null,
        productionId: String? = null,
        prompts: List<Prompt> = emptyList(),
    ) = PromptGroup(
        id = "g1",
        name = "Group",
        author = "a",
        authorName = "A",
        productionId = productionId,
        prompts = prompts,
        productionPrompt = productionPrompt?.let { ProductionPromptEmbed(it) },
    )

    private fun prompt(id: String, text: String) =
        Prompt(id = id, groupId = "g1", author = "a", prompt = text, type = "text")

    // ── Resolution ladder ────────────────────────────────────────────────

    @Test
    fun productionPromptWinsWhenPresent() {
        // What the list endpoints return; the `prompts` array is absent there entirely.
        val g = group(productionPrompt = "from lookup", prompts = listOf(prompt("p1", "from array")))
        assertEquals("from lookup", resolvePromptText(g))
    }

    @Test
    fun fallsBackToProductionIdWithinPromptsArray() {
        // What the detail endpoint returns.
        val g = group(
            productionId = "p2",
            prompts = listOf(prompt("p1", "first"), prompt("p2", "production")),
        )
        assertEquals("production", resolvePromptText(g))
    }

    @Test
    fun fallsBackToFirstPromptWhenProductionIdIsUnknown() {
        val g = group(productionId = "missing", prompts = listOf(prompt("p1", "first")))
        assertEquals("first", resolvePromptText(g))
    }

    @Test
    fun resolvesToNullWhenGroupCarriesNoPrompt() {
        assertNull(resolvePromptText(group()))
    }

    // ── Variables ────────────────────────────────────────────────────────

    @Test
    fun extractsVariablesInOrderWithoutDuplicates() {
        assertEquals(
            listOf("topic", "tone"),
            extractPromptVariables("Write about {{topic}} in a {{tone}} tone about {{topic}}"),
        )
    }

    @Test
    fun toleratesWhitespaceInsideBraces() {
        // A prompt authored on the web client can carry these; a `\w+` pattern misses them and
        // sends the raw placeholder.
        assertEquals(listOf("topic"), extractPromptVariables("Write about {{ topic }}"))
    }

    @Test
    fun acceptsNonWordCharactersInNames() {
        assertEquals(listOf("user-name"), extractPromptVariables("Hello {{user-name}}"))
    }

    @Test
    fun specialVariablesAreNotUserFillable() {
        assertEquals(emptyList(), extractPromptVariables("Today is {{current_date}}, {{iso_datetime}}"))
        assertFalse(hasFillableVariables("Today is {{current_date}}"))
    }

    @Test
    fun specialVariableDetectionIgnoresCaseAndSpacing() {
        assertFalse(hasFillableVariables("Today is {{ Current_Date }}"))
    }

    @Test
    fun mixedSpecialAndFillableKeepsOnlyTheFillable() {
        assertEquals(
            listOf("topic"),
            extractPromptVariables("On {{current_date}} write about {{topic}}"),
        )
        assertTrue(hasFillableVariables("On {{current_date}} write about {{topic}}"))
    }

    // ── Substitution ─────────────────────────────────────────────────────

    @Test
    fun substitutesByTrimmedNameCaseInsensitively() {
        assertEquals(
            "Write about cats",
            substitutePromptVariables("Write about {{ Topic }}", mapOf("topic" to "cats")),
        )
    }

    @Test
    fun blankOrMissingValuesLeaveThePlaceholderIntact() {
        assertEquals(
            "Write about {{topic}}",
            substitutePromptVariables("Write about {{topic}}", mapOf("topic" to "  ")),
        )
        assertEquals(
            "Write about {{topic}}",
            substitutePromptVariables("Write about {{topic}}", emptyMap()),
        )
    }

    @Test
    fun specialVariablesSurviveSubstitutionForTheServer() {
        // Freezing these client-side would send a stale date the server intended to fill itself.
        assertEquals(
            "On {{current_date}} write about cats",
            substitutePromptVariables(
                "On {{current_date}} write about {{topic}}",
                mapOf("topic" to "cats", "current_date" to "1999-01-01"),
            ),
        )
    }

    // ── Insertion outcome ────────────────────────────────────────────────

    @Test
    fun plainPromptIsReadyToInsert() {
        val result = resolvePromptInsertion(group(productionPrompt = "Summarize this"))
        assertEquals(PromptInsertion.Ready("Summarize this"), result)
    }

    @Test
    fun promptWithVariablesNeedsFillingFirst() {
        val result = resolvePromptInsertion(group(productionPrompt = "Write about {{topic}}"))
        assertEquals(
            PromptInsertion.NeedsVariables("Write about {{topic}}", listOf("topic")),
            result,
        )
    }

    @Test
    fun promptWithOnlySpecialVariablesIsReady() {
        val result = resolvePromptInsertion(group(productionPrompt = "Today is {{current_date}}"))
        assertEquals(PromptInsertion.Ready("Today is {{current_date}}"), result)
    }

    @Test
    fun groupWithNoUsableTextInsertsNothing() {
        // Must not fall back to the command word — it's a lookup shorthand, never message text.
        assertNull(resolvePromptInsertion(group()))
        assertNull(resolvePromptInsertion(group(productionPrompt = "   ")))
    }
}
