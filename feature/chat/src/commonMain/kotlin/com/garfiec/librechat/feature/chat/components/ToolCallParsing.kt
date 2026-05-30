package com.garfiec.librechat.feature.chat.components

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

// --- Parsing helpers ---

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

/** Resolves an attachment's `filepath`/`fileId` to a fully-qualified image URL. */
private fun resolveAttachmentUrl(attachment: Attachment?, baseUrl: String): String? {
    if (attachment == null) return null
    val filepath = attachment.filepath
    if (filepath != null) {
        return when {
            filepath.startsWith("http") -> filepath
            filepath.startsWith("/") && baseUrl.isNotBlank() -> "$baseUrl$filepath"
            baseUrl.isNotBlank() -> "$baseUrl/$filepath"
            else -> filepath
        }
    }
    val fileId = attachment.fileId
    if (fileId != null && baseUrl.isNotBlank()) {
        return "$baseUrl/api/files/$fileId"
    }
    return null
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
