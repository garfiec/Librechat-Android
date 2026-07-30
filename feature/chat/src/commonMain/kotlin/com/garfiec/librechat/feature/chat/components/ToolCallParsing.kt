package com.garfiec.librechat.feature.chat.components

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.model.AskUserQuestionOption
import com.garfiec.librechat.core.model.AskUserQuestionRequest
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.garfiec.librechat.feature.chat.util.resolveAttachmentUrl
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val log = Logger.withTag("ToolCallParsing")

internal val toolCallJson = Json { ignoreUnknownKeys = true; isLenient = true }

// --- Tool name classification ---

private val IMAGE_GEN_EXACT_NAMES = setOf(
    "image_gen_oai",
    "image_edit_oai",
    "gemini_image_gen",
)

private val IMAGE_GEN_CONTAINS = listOf(
    "dall",
    "image_gen",
    "stable-diffusion",
    "flux",
)

internal fun isImageGenToolCall(toolNameLower: String): Boolean {
    if (toolNameLower in IMAGE_GEN_EXACT_NAMES) return true
    return IMAGE_GEN_CONTAINS.any { toolNameLower.contains(it) }
}

/** Bash-flavored code tool calls, whose `code` arg is a shell command (web `BashCall`). */
private val BASH_TOOL_NAMES = setOf(
    ToolConstants.BASH_TOOL,
    ToolConstants.BASH_PROGRAMMATIC_TOOL_CALLING,
)

internal fun isBashToolCall(toolNameLower: String): Boolean = toolNameLower in BASH_TOOL_NAMES

/**
 * True for tool calls that render as the code-execution card: the code interpreter, `execute_code`,
 * the bash tool, and programmatic tool-calling (Python/bash). Matches upstream ExecuteCode/BashCall
 * routing. `run_tools_with_code`/`run_tools_with_bash` don't contain "execute", so they're matched
 * by exact name.
 */
internal fun isCodeExecutionToolCall(toolNameLower: String): Boolean {
    if (toolNameLower.contains(ToolConstants.CODE_INTERPRETER) || toolNameLower.contains("execute")) {
        return true
    }
    return toolNameLower == ToolConstants.PROGRAMMATIC_TOOL_CALLING || toolNameLower in BASH_TOOL_NAMES
}

/**
 * Web-search-style tool calls that render as the "Searched the web" sources card. `file_search`
 * and `retrieval` also contain "search" but carry their sources in an attachment payload the
 * mobile model doesn't parse yet, so they're excluded and fall through to the generic card.
 */
internal fun isWebSearchToolCall(toolNameLower: String): Boolean =
    toolNameLower != ToolConstants.FILE_SEARCH &&
        toolNameLower != ToolConstants.RETRIEVAL &&
        toolNameLower.contains("search")

// --- Parsing helpers ---

/**
 * Collects web-search sources from a message's `web_search` attachments (the shape the server
 * actually sends — `organic` + `topStories`, not a `results` array in the tool-call output).
 *
 * Takes attachments whose `toolCallId` matches this tool call, plus any that carry no id at all
 * (older payloads didn't always set one — those attach to any search call as a best effort).
 * Attachments belonging to a *different* tool call are excluded so a second search turn can't
 * duplicate its sources here. When this tool call itself has no id we can't disambiguate, so we
 * fall back to every web-search attachment (there is normally only one). During streaming the
 * server re-emits the attachment once per source processed, each a superset of the last, so we
 * dedup by link — that naturally yields the final full set. Mirrors upstream `collectSources`
 * in `WebSearch.tsx`.
 */
internal fun collectWebSearchSources(
    attachments: List<Attachment>,
    toolCallId: String?,
): List<WebSearchResult> {
    val webAttachments = attachments.filter {
        it.type == ToolConstants.WEB_SEARCH && it.webSearch != null
    }
    if (webAttachments.isEmpty()) return emptyList()
    val scoped = if (toolCallId == null) {
        webAttachments
    } else {
        webAttachments.filter { it.toolCallId == toolCallId || it.toolCallId == null }
    }

    val seen = LinkedHashSet<String>()
    val results = mutableListOf<WebSearchResult>()
    scoped.forEach { att ->
        val data = att.webSearch ?: return@forEach
        (data.organic.orEmpty() + data.topStories.orEmpty()).forEach { s ->
            val url = s.link ?: return@forEach
            if (seen.add(url)) {
                results += WebSearchResult(
                    title = s.title?.takeIf { it.isNotBlank() } ?: hostOf(url) ?: url,
                    url = url,
                    snippet = s.snippet.orEmpty(),
                    favicon = null,
                )
            }
        }
    }
    return results
}

/** Bare host of a URL (no scheme, no port, no path), or null if it can't be extracted. */
internal fun hostOf(url: String): String? =
    url.substringAfter("://", url)
        .substringBefore("/")
        .substringBefore(":")
        .removePrefix("www.")
        .takeIf { it.isNotEmpty() }

internal fun parseWebSearchResults(output: String?): List<WebSearchResult> {
    if (output.isNullOrBlank()) return emptyList()
    return try {
        val element = toolCallJson.parseToJsonElement(output)
        val resultsArray = when (element) {
            is JsonArray -> element
            is JsonObject -> element["results"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        resultsArray.mapNotNull { item ->
            try {
                val obj = item.jsonObject
                WebSearchResult(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    url = obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: obj["link"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null,
                    snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull
                        ?: obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: obj["content"]?.jsonPrimitive?.contentOrNull
                        ?: "",
                    favicon = obj["favicon"]?.jsonPrimitive?.contentOrNull,
                )
            } catch (e: Exception) {
                    log.d(e) { "Skipping malformed web search result item" }
                    null
                }
        }
    } catch (e: Exception) {
        log.d(e) { "Failed to parse web search results" }
        emptyList()
    }
}

internal fun parseCodeExecution(toolCall: AgentToolCall?): CodeExecutionResult? {
    if (toolCall == null) return null
    return try {
        val argsElement = toolCall.args
        val argsObj = argsElement?.jsonObject
        val code = argsObj?.get("code")?.jsonPrimitive?.contentOrNull
            ?: toolCall.function?.arguments?.let { argsStr ->
                try {
                    toolCallJson.parseToJsonElement(argsStr).jsonObject["code"]?.jsonPrimitive?.contentOrNull
                } catch (e: Exception) {
                    log.d(e) { "Failed to parse code execution args string" }
                    null
                }
            }
        val language = argsObj?.get("language")?.jsonPrimitive?.contentOrNull
            ?: argsObj?.get("lang")?.jsonPrimitive?.contentOrNull
            ?: toolCall.function?.arguments?.let { argsStr ->
                try {
                    val parsed = toolCallJson.parseToJsonElement(argsStr).jsonObject
                    parsed["language"]?.jsonPrimitive?.contentOrNull
                        ?: parsed["lang"]?.jsonPrimitive?.contentOrNull
                } catch (e: Exception) {
                    log.d(e) { "Failed to parse code execution language from args" }
                    null
                }
            }

        val outputStr = toolCall.output ?: toolCall.function?.output
        var outputText: String? = null
        var errorText: String? = null
        var exitCode: Int? = null

        if (!outputStr.isNullOrBlank()) {
            try {
                val outputObj = toolCallJson.parseToJsonElement(outputStr).jsonObject
                outputText = outputObj["output"]?.jsonPrimitive?.contentOrNull
                    ?: outputObj["stdout"]?.jsonPrimitive?.contentOrNull
                errorText = outputObj["error"]?.jsonPrimitive?.contentOrNull
                    ?: outputObj["stderr"]?.jsonPrimitive?.contentOrNull
                exitCode = outputObj["exit_code"]?.jsonPrimitive?.intOrNull
                    ?: outputObj["exitCode"]?.jsonPrimitive?.intOrNull
                    ?: outputObj["status"]?.jsonPrimitive?.intOrNull
            } catch (e: Exception) {
                log.d(e) { "Code execution output is not structured JSON, using raw string" }
                outputText = outputStr
            }
        }

        CodeExecutionResult(
            language = language, code = code, output = outputText,
            error = errorText, exitCode = exitCode,
        )
    } catch (e: Exception) {
        log.d(e) { "Failed to parse code execution tool call" }
        null
    }
}

internal fun parseMemoryArtifact(output: String?): MemoryArtifact? {
    if (output.isNullOrBlank()) return null
    return try {
        val obj = toolCallJson.parseToJsonElement(output).jsonObject
        MemoryArtifact(
            title = obj["title"]?.jsonPrimitive?.contentOrNull
                ?: obj["key"]?.jsonPrimitive?.contentOrNull,
            content = obj["content"]?.jsonPrimitive?.contentOrNull
                ?: obj["value"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull,
            key = obj["key"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (e: Exception) {
        log.d(e) { "Failed to parse memory artifact as JSON, using raw output" }
        MemoryArtifact(title = null, content = output)
    }
}

internal fun parseMcpResources(output: String?): List<McpResource> {
    if (output.isNullOrBlank()) return emptyList()
    return try {
        val element = toolCallJson.parseToJsonElement(output)
        val resourcesArray = when (element) {
            is JsonArray -> element
            is JsonObject -> element["resources"]?.jsonArray
                ?: element["contents"]?.jsonArray
                ?: return emptyList()
            else -> return emptyList()
        }
        resourcesArray.mapNotNull { item ->
            try {
                val obj = item.jsonObject
                McpResource(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: "Resource",
                    uri = obj["uri"]?.jsonPrimitive?.contentOrNull
                        ?: obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null,
                    preview = obj["preview"]?.jsonPrimitive?.contentOrNull
                        ?: obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: obj["text"]?.jsonPrimitive?.contentOrNull,
                )
            } catch (e: Exception) {
                    log.d(e) { "Skipping malformed MCP resource item" }
                    null
                }
        }
    } catch (e: Exception) {
        log.d(e) { "Failed to parse MCP resources" }
        emptyList()
    }
}

internal fun parseImageGenResult(
    toolCall: AgentToolCall?,
    baseUrl: String = "",
    attachments: List<Attachment> = emptyList(),
): ImageGenResult {
    if (toolCall == null) return ImageGenResult()

    val prompt = try {
        val argsElement = toolCall.args
        when {
            argsElement is JsonObject ->
                argsElement["prompt"]?.jsonPrimitive?.contentOrNull
            argsElement is JsonPrimitive && argsElement.isString ->
                toolCallJson.parseToJsonElement(argsElement.content).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull
            else -> null
        } ?: toolCall.function?.arguments?.let { argsStr ->
            try {
                toolCallJson.parseToJsonElement(argsStr).jsonObject["prompt"]?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                    log.d(e) { "Failed to parse image gen prompt from function args" }
                    null
                }
        }
    } catch (e: Exception) {
        log.d(e) { "Failed to parse image gen prompt" }
        null
    }

    val outputStr = toolCall.output ?: toolCall.function?.output
    var imageUrl: String? = null

    val toolCallId = toolCall.id
    if (toolCallId != null) {
        val attachment = attachments.firstOrNull { it.toolCallId == toolCallId }
        imageUrl = resolveAttachmentUrl(attachment, baseUrl)
    }

    if (imageUrl == null && !outputStr.isNullOrBlank()) {
        try {
            val outputObj = toolCallJson.parseToJsonElement(outputStr).jsonObject
            imageUrl = outputObj["url"]?.jsonPrimitive?.contentOrNull
                ?: outputObj["image_url"]?.jsonPrimitive?.contentOrNull
                ?: outputObj["result"]?.jsonPrimitive?.contentOrNull
            if (imageUrl == null) {
                val filepath = outputObj["filepath"]?.jsonPrimitive?.contentOrNull
                if (filepath != null) {
                    imageUrl = when {
                        filepath.startsWith("http") -> filepath
                        filepath.startsWith("/images/") && baseUrl.isNotBlank() -> "$baseUrl$filepath"
                        baseUrl.isNotBlank() -> "$baseUrl/images/$filepath"
                        else -> filepath
                    }
                }
            }
            if (imageUrl == null) {
                val fileId = outputObj["file_id"]?.jsonPrimitive?.contentOrNull
                if (fileId != null && baseUrl.isNotBlank()) {
                    imageUrl = "$baseUrl/api/files/$fileId"
                }
            }
        } catch (e: Exception) {
            log.d(e) { "Image gen output is not structured JSON, checking for raw URL" }
            if (outputStr.startsWith("http")) {
                imageUrl = outputStr.trim()
            }
        }
    }

    return ImageGenResult(
        imageUrl = imageUrl,
        prompt = prompt,
        isGenerating = outputStr.isNullOrBlank() && imageUrl == null,
    )
}

/**
 * Builds an [ImageGenResult] for an in-progress (streaming) image-gen tool call.
 *
 * Mirrors the web client: during streaming the prompt/quality are available from the
 * tool-call args immediately, while the generated image arrives later as a separate
 * `attachment` SSE event (linked by `toolCallId`). [ImageGenResult.isGenerating] stays
 * true until the matching attachment lands, at which point the placeholder swaps to the
 * real image — all before the final message reload.
 */
internal fun parseStreamingImageGenResult(
    toolCall: ActiveToolCall,
    baseUrl: String,
    attachments: List<Attachment>,
): ImageGenResult {
    val (prompt, quality) = parseImageGenArgs(toolCall.input)
    val attachment = attachments.firstOrNull { it.toolCallId == toolCall.id }
    val imageUrl = resolveAttachmentUrl(attachment, baseUrl)
    return ImageGenResult(
        imageUrl = imageUrl,
        prompt = prompt,
        isGenerating = imageUrl == null,
        quality = quality,
    )
}

/** Extracts (prompt, quality) from a raw image-gen tool-call args JSON string. */
private fun parseImageGenArgs(argsJson: String?): Pair<String?, String?> {
    if (argsJson.isNullOrBlank()) return null to null
    return try {
        val obj = toolCallJson.parseToJsonElement(argsJson).jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
        val quality = obj["quality"]?.jsonPrimitive?.contentOrNull
        prompt to quality
    } catch (e: Exception) {
        log.d(e) { "Failed to parse streaming image gen args" }
        null to null
    }
}

internal fun parseLogContent(toolCall: AgentToolCall?): LogContent {
    if (toolCall == null) return LogContent()
    return try {
        val outputStr = toolCall.output ?: toolCall.function?.output
        val toolName = toolCall.name ?: toolCall.function?.name ?: "Log Output"

        if (!outputStr.isNullOrBlank()) {
            try {
                val outputObj = toolCallJson.parseToJsonElement(outputStr).jsonObject
                val title = outputObj["title"]?.jsonPrimitive?.contentOrNull ?: toolName
                val content = outputObj["content"]?.jsonPrimitive?.contentOrNull
                    ?: outputObj["log"]?.jsonPrimitive?.contentOrNull
                    ?: outputObj["text"]?.jsonPrimitive?.contentOrNull
                    ?: outputStr
                LogContent(title = title, content = content)
            } catch (e: Exception) {
                log.d(e) { "Log output is not structured JSON, using raw string" }
                LogContent(title = toolName, content = outputStr)
            }
        } else {
            LogContent(title = toolName)
        }
    } catch (e: Exception) {
        log.d(e) { "Failed to parse log content tool call" }
        LogContent()
    }
}

// --- ask_user_question (HITL) ---

/** The literal answer the Skip button sends. Mirrors upstream's `ASK_USER_DECLINED_ANSWER`, which
 *  the web client sends for the same action — so a question skipped on either client reads as
 *  skipped on both. */
internal const val ASK_USER_DECLINED_ANSWER = "The user chose not to answer this question."

internal fun isAskUserQuestionToolCall(toolNameLower: String): Boolean =
    toolNameLower == ToolConstants.ASK_USER_QUESTION

/**
 * The streaming tool-call cards to render.
 *
 * An `ask_user_question` call is dropped until its answer lands on it. While the run is paused,
 * [PendingActionCard] *is* the question — a second card restating it under a spinner (the call
 * cannot complete until the user replies) is both a duplicate and a lie about what is running.
 * Once the answer arrives the same call renders as the durable Q&A record, so the question is
 * never on screen twice and never absent after it is settled.
 */
internal fun List<ActiveToolCall>.withoutUnansweredQuestions(): List<ActiveToolCall> =
    filterNot { isAskUserQuestionToolCall(it.name.lowercase()) && it.output.isNullOrBlank() }

/**
 * Parses `ask_user_question` arguments, which arrive as an object mid-stream and as a JSON string
 * on a persisted message.
 *
 * Every field here is model-generated, so the shape is normalized rather than trusted: a malformed
 * `options` entry is dropped and anything without a string `question` degrades to null, which the
 * card renders as a plain unlabeled record instead of throwing inside composition.
 */
internal fun parseAskUserQuestion(raw: JsonElement?): AskUserQuestionRequest? = when (raw) {
    is JsonObject -> raw.toAskUserQuestion()
    is JsonPrimitive -> if (raw.isString) parseAskUserQuestion(raw.content) else null
    else -> null
}

/**
 * The model's one-line description of what a call is about to do ("Searching for OAuth handling in
 * the callback router"), read from the call's own arguments.
 *
 * The server injects `intent` as the FIRST property of an opted-in tool's schema, so it is the
 * first key providers stream and it arrives inside `tool_call.args` verbatim — there is no
 * separate event to subscribe to and nothing to gate on. Absent whenever the capability is off or
 * the tool did not opt in, which is why every caller falls back to the tool name.
 */
internal fun parseToolIntent(raw: JsonElement?): String? = when (raw) {
    is JsonObject -> raw.intentField()
    is JsonPrimitive -> if (raw.isString) parseToolIntent(raw.content) else null
    else -> null
}

/** [parseToolIntent] for args held as raw text — the persisted shape, and the streaming `input`. */
internal fun parseToolIntent(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    // Object-only, which is also what stops the [JsonElement] overload from recursing: lenient
    // parsing turns a bare word back into a string and re-entering on that would not terminate.
    val parsed = try {
        toolCallJson.parseToJsonElement(text)
    } catch (e: Exception) {
        log.d(e) { "Failed to parse tool args for intent" }
        return null
    }
    return (parsed as? JsonObject)?.intentField()
}

/**
 * Only the FIRST key counts.
 *
 * Nothing about the opt-in is client-observable — the capability and the per-tool
 * `describe_intent` flag live server-side, and the argument is stripped before a tool that did not
 * declare it ever runs. So a plain "is there an `intent` string" test would retitle a user's own
 * MCP tool that legitimately takes a parameter by that name, on any server. Upstream injects the
 * property at position zero precisely so it streams ahead of the real arguments, and that ordering
 * is the one part of the contract this side can actually check.
 */
private fun JsonObject.intentField(): String? {
    if (keys.firstOrNull() != INTENT_KEY) return null
    return stringField(INTENT_KEY)?.trim()?.takeIf { it.isNotEmpty() }
}

/** The args as the card should display them: without the label already shown as its title. */
internal fun argsWithoutIntent(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return raw
    val parsed = try {
        toolCallJson.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        log.d(e) { "Failed to parse tool args for display" }
        null
    } ?: return raw
    if (parsed.keys.firstOrNull() != INTENT_KEY) return raw
    val rest = JsonObject(parsed.filterKeys { it != INTENT_KEY })
    return if (rest.isEmpty()) null else toolCallJson.encodeToString(JsonObject.serializer(), rest)
}

private const val INTENT_KEY = "intent"

/** [parseAskUserQuestion] for args held as raw text (the streaming path's `input`). Accepts only
 *  a JSON object, which is also what stops the [JsonElement] overload from recursing: lenient
 *  parsing turns a bare word back into a string, and re-entering on that would not terminate. */
internal fun parseAskUserQuestion(raw: String?): AskUserQuestionRequest? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val parsed = try {
        toolCallJson.parseToJsonElement(text)
    } catch (e: Exception) {
        log.d(e) { "Failed to parse ask_user_question args" }
        return null
    }
    return (parsed as? JsonObject)?.toAskUserQuestion()
}

private fun JsonObject.toAskUserQuestion(): AskUserQuestionRequest? {
    val question = stringField("question") ?: return null
    val options = (this["options"] as? JsonArray).orEmpty().mapNotNull { element ->
        val option = element as? JsonObject ?: return@mapNotNull null
        val label = option.stringField("label") ?: return@mapNotNull null
        val value = option.stringField("value") ?: return@mapNotNull null
        AskUserQuestionOption(label = label, value = value)
    }
    return AskUserQuestionRequest(
        question = question,
        description = stringField("description"),
        options = options,
        multiSelect = (this["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** How an answer reads back against the question that was asked. */
internal data class AskAnswerDisplay(
    /** The answer with option values swapped for their labels, or the raw answer. */
    val label: String,
    /** Values of the options the answer picked; empty when it is free text. */
    val selectedValues: Set<String>,
    /** True when the user dismissed the question rather than answering it. */
    val declined: Boolean,
)

/**
 * Reads an answer back against its question, preferring the picked option's label over its wire
 * value.
 *
 * Multi-select answers are option values joined by `", "`, so segments are mapped back to labels
 * **only when every one of them matches an option**: a value may legally contain `", "` itself,
 * and a partial mapping could split such a value into fragments that relabel as options the user
 * never picked. A mobile answer that qualifies a chip with free text also lands here — it has a
 * segment that matches nothing — and correctly shows raw. Ported from upstream
 * `AskUserQuestionCall`.
 */
internal fun askAnswerDisplay(
    question: AskUserQuestionRequest?,
    answer: String,
): AskAnswerDisplay {
    if (answer == ASK_USER_DECLINED_ANSWER) {
        return AskAnswerDisplay(label = answer, selectedValues = emptySet(), declined = true)
    }
    val options = question?.options.orEmpty()
    val raw = AskAnswerDisplay(label = answer, selectedValues = emptySet(), declined = false)
    if (options.isEmpty() || answer.isEmpty()) return raw

    options.firstOrNull { it.value == answer }?.let { exact ->
        return AskAnswerDisplay(
            label = exact.label.ifBlank { exact.value },
            selectedValues = setOf(exact.value),
            declined = false,
        )
    }
    if (question?.multiSelect != true) return raw

    val matched = answer.split(", ").map { segment -> options.firstOrNull { it.value == segment } }
    if (matched.any { it == null }) return raw
    val picked = matched.filterNotNull()
    return AskAnswerDisplay(
        label = picked.joinToString(", ") { it.label.ifBlank { it.value } },
        selectedValues = picked.map { it.value }.toSet(),
        declined = false,
    )
}
