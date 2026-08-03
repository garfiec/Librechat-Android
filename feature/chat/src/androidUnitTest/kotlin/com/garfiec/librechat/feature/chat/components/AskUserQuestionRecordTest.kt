package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.core.model.AskUserQuestionOption
import com.garfiec.librechat.core.model.AskUserQuestionRequest
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The data behind the `ask_user_question` record card: reading the question back out of the tool
 * call's arguments, the answer back out of its output, and keeping the question off screen while
 * the interactive pause card is the one asking it.
 */
class AskUserQuestionRecordTest {

    private val question = AskUserQuestionRequest(
        question = "Which database?",
        options = listOf(
            AskUserQuestionOption(label = "Postgres", value = "pg"),
            AskUserQuestionOption(label = "SQLite", value = "sqlite"),
        ),
    )

    // ── args parsing ────────────────────────────────────────────────

    @Test
    fun `parses an args object`() {
        val parsed = parseAskUserQuestion(
            """{"question":"Which database?","description":"Affects the migration.",
               "options":[{"label":"Postgres","value":"pg"}],"multiSelect":true}""",
        )

        assertThat(parsed?.question).isEqualTo("Which database?")
        assertThat(parsed?.description).isEqualTo("Affects the migration.")
        assertThat(parsed?.options).containsExactly(AskUserQuestionOption("Postgres", "pg"))
        assertThat(parsed?.multiSelect).isTrue()
    }

    /** Persisted messages carry the args as a JSON *string* inside the part, not as an object. */
    @Test
    fun `parses args held as a json string`() {
        val encoded = Json.parseToJsonElement("\"{\\\"question\\\":\\\"Which database?\\\"}\"")

        assertThat(parseAskUserQuestion(encoded)?.question).isEqualTo("Which database?")
    }

    /**
     * Every field is model-generated. A shape the schema forbids must degrade to a plain record,
     * never throw — this runs inside composition.
     */
    @Test
    fun `malformed args degrade instead of throwing`() {
        assertThat(parseAskUserQuestion("not json at all")).isNull()
        assertThat(parseAskUserQuestion("""{"description":"no question here"}""")).isNull()
        assertThat(parseAskUserQuestion(null as String?)).isNull()
        assertThat(parseAskUserQuestion("   ")).isNull()

        val partialOptions = parseAskUserQuestion(
            """{"question":"Pick","options":[{"label":"ok","value":"v"},{"label":7},"nope"]}""",
        )
        assertThat(partialOptions?.options).containsExactly(AskUserQuestionOption("ok", "v"))
    }

    /** Lenient parsing turns a bare word back into a string; unwrapping must still terminate. */
    @Test
    fun `a self-reparsing primitive terminates`() {
        assertThat(parseAskUserQuestion(Json.parseToJsonElement("\"bareword\""))).isNull()
    }

    // ── answer mapping ──────────────────────────────────────────────

    @Test
    fun `an answer matching an option shows that option's label`() {
        val display = askAnswerDisplay(question, "pg")

        assertThat(display.label).isEqualTo("Postgres")
        assertThat(display.selectedValues).containsExactly("pg")
    }

    @Test
    fun `a multi-select answer maps every segment back to its label`() {
        val display = askAnswerDisplay(question.copy(multiSelect = true), "pg, sqlite")

        assertThat(display.label).isEqualTo("Postgres, SQLite")
        assertThat(display.selectedValues).containsExactly("pg", "sqlite")
    }

    /**
     * All-or-nothing: an option value may legally contain ", " itself, so a partial mapping could
     * split one value into fragments and relabel them as options the user never picked. A chip
     * qualified with free text — what this client's composer produces — lands here too.
     */
    @Test
    fun `a partly-matching multi-select answer is shown raw`() {
        val display = askAnswerDisplay(question.copy(multiSelect = true), "pg, and also something else")

        assertThat(display.label).isEqualTo("pg, and also something else")
        assertThat(display.selectedValues).isEmpty()
    }

    @Test
    fun `free text is shown as typed`() {
        val display = askAnswerDisplay(question, "whichever is cheapest")

        assertThat(display.label).isEqualTo("whichever is cheapest")
        assertThat(display.selectedValues).isEmpty()
        assertThat(display.declined).isFalse()
    }

    /** Skip posts a sentinel answer so the run resumes; echoing that sentence back at the user
     *  reads as something they typed. */
    @Test
    fun `the skip sentinel reads as skipped`() {
        val display = askAnswerDisplay(question, ASK_USER_DECLINED_ANSWER)

        assertThat(display.declined).isTrue()
    }

    @Test
    fun `an answer to a question with no options survives`() {
        assertThat(askAnswerDisplay(null, "42").label).isEqualTo("42")
        assertThat(askAnswerDisplay(question.copy(options = emptyList()), "42").label).isEqualTo("42")
    }

    // ── streaming suppression ───────────────────────────────────────

    /**
     * While the run is paused, the pause card IS the question, and the same question also sits in
     * `activeToolCalls` as a call that cannot complete — rendering both asks the user the same
     * thing twice, once under a spinner.
     */
    @Test
    fun `an unanswered question is not rendered as a tool card`() {
        val calls = listOf(
            ActiveToolCall(id = "t1", name = "web_search", isComplete = true, output = "{}"),
            ActiveToolCall(id = "t2", name = "ask_user_question", input = """{"question":"Which?"}"""),
        )

        assertThat(calls.withoutUnansweredQuestions().map { it.id }).containsExactly("t1")
    }

    @Test
    fun `an answered question is rendered as its record`() {
        val calls = listOf(
            ActiveToolCall(
                id = "t2",
                name = "ask_user_question",
                isComplete = true,
                output = "pg",
                input = """{"question":"Which?"}""",
            ),
        )

        assertThat(calls.withoutUnansweredQuestions()).isEqualTo(calls)
    }

    @Test
    fun `other tool calls are untouched`() {
        val calls = listOf(ActiveToolCall(id = "t1", name = "execute_code"))

        assertThat(calls.withoutUnansweredQuestions()).isEqualTo(calls)
    }
}
