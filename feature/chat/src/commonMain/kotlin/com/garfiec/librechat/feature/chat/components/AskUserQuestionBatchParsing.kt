package com.garfiec.librechat.feature.chat.components

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.model.AskUserQuestionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Reading a BATCHED `ask_user_question` back off its tool call — the questions from the args, the
 * answers from the output.
 *
 * Kept apart from the single-question parsing in `ToolCallParsing.kt` because the two shapes share
 * nothing at the top level: a batch carries `questions[]` and no `question`, and its output is an
 * answers map rather than a sentence, so neither parser can stand in for the other.
 */

private val log = Logger.withTag("AskUserQuestionBatchParsing")

/**
 * One question of a batched `ask_user_question`, paired with the id its answer is keyed by.
 *
 * The id is not decoration: a resolved batch stamps its answers onto the call's output as a map
 * keyed by exactly these ids, so it is the only thing that joins a question to what was said.
 */
internal data class AskUserQuestionEntry(
    val id: String,
    /** Optional short heading the agent put above the question. */
    val header: String?,
    val question: AskUserQuestionRequest,
)

/**
 * Parses the batched `ask_user_question` argument shape — `{questions: [{id, question, …}]}` —
 * which a single-question parse cannot see: it carries no top-level `question`, so
 * [parseAskUserQuestion] returns null for it and the record card would fall back to rendering the
 * answers JSON verbatim.
 *
 * Empty for a single-question call, which is what selects between the two record shapes. An item
 * without a usable id is dropped rather than rendered: its answer cannot be looked up, so it could
 * only ever read as unanswered.
 */
internal fun parseAskUserQuestionBatch(raw: JsonElement?): List<AskUserQuestionEntry> = when (raw) {
    is JsonObject -> raw.toAskUserQuestionBatch()
    is JsonPrimitive -> if (raw.isString) parseAskUserQuestionBatch(raw.content) else emptyList()
    else -> emptyList()
}

/** [parseAskUserQuestionBatch] for args held as raw text — the persisted and streaming shapes. */
internal fun parseAskUserQuestionBatch(raw: String?): List<AskUserQuestionEntry> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    // Object-only, which is also what stops the [JsonElement] overload from recursing: lenient
    // parsing turns a bare word back into a string and re-entering on that would not terminate.
    val parsed = try {
        toolCallJson.parseToJsonElement(text)
    } catch (e: Exception) {
        log.d(e) { "Failed to parse ask_user_question batch args" }
        return emptyList()
    }
    return (parsed as? JsonObject)?.toAskUserQuestionBatch().orEmpty()
}

private fun JsonObject.toAskUserQuestionBatch(): List<AskUserQuestionEntry> {
    val items = this["questions"] as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.stringField("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val question = item.toAskUserQuestion() ?: return@mapNotNull null
        AskUserQuestionEntry(id = id, header = item.stringField("header"), question = question)
    }
}

/**
 * The answers a resolved batch stamps onto the call's output: `{"answers": {id: text}}`.
 *
 * Written by the server, not by the model — but an id it names and the batch does not (or the
 * reverse) simply drops out, leaving that question to read as unanswered rather than putting one
 * question's words under another. Empty while the pause is still live, since nothing is stamped
 * until it resolves.
 */
internal fun parseAskUserAnswers(output: String?): Map<String, String> {
    val text = output?.trim().orEmpty()
    if (text.isEmpty()) return emptyMap()
    val parsed = try {
        toolCallJson.parseToJsonElement(text)
    } catch (e: Exception) {
        log.d(e) { "Failed to parse ask_user_question answers" }
        return emptyMap()
    }
    val answers = (parsed as? JsonObject)?.get("answers") as? JsonObject ?: return emptyMap()
    return answers.mapNotNull { (id, value) ->
        val answer = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        id to answer
    }.toMap()
}
