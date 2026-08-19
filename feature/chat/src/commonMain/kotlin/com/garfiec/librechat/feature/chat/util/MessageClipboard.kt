package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.ToolCallType
import com.garfiec.librechat.core.model.content.AgentToolCall
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serializes a whole message for the clipboard, mirroring web `serializeMessageForClipboard`
 * (`client/src/hooks/Messages/useCopyToClipboard.ts` + `formatMessageContent` in
 * `client/src/hooks/Conversations/format.ts`, upstream d920328bfa53).
 *
 * EVERY part serializes, as a `label:\nvalue` block (label-less for plain text) joined by single
 * newlines like web's clipboard path — a text-parts-only copy reduces an agent turn whose
 * substance is tool calls to a fragment, or to nothing at all. The labels
 * are web's own English export strings; mobile ViewModel-layer copy has no localization channel,
 * matching the rest of this layer's user-facing strings.
 *
 * This is the CLIPBOARD serialization only. `ChatViewModel.getMessageText` keeps its text-parts
 * extraction: it also feeds TTS (reading a JSON dump aloud) and the edit prefill (which must
 * round-trip into `updateMessageText`), where serialized tool calls would be corruption.
 */
fun serializeMessageForClipboard(message: Message): String {
    val parts = message.content
    if (parts.isNullOrEmpty()) return message.text

    return parts
        .mapNotNull { part -> formatPartForClipboard(part) }
        .map { (label, value) -> if (label.isEmpty()) value else "$label:\n$value" }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

/** Web export label strings (`com_ui_export_*` / `com_endpoint_thinking`, en). */
private object ClipboardLabels {
    const val THINKING = "Thinking"
    const val TOOL = "Tool"
    const val RUN_CODE = "Run Code"
    const val RETRIEVAL = "Retrieval"
    const val FILE_SEARCH = "File Search"
    const val IMAGE = "Image"
    const val AUDIO = "Audio"
    const val VIDEO = "Video"
    const val AGENT_UPDATE = "Agent Update"
    const val SUMMARY = "Summary"
    const val STEER = "You (steered)"
    const val ACTIVITY = "Activity"
}

private val clipboardJson = Json { encodeDefaults = false }

/** `label to value`, empty label meaning "value alone" (plain text); null meaning "skip". */
private fun formatPartForClipboard(part: MessageContentPart): Pair<String, String>? = when (part.type) {
    ContentType.ERROR -> "" to (part.error ?: part.text.orEmpty())

    ContentType.TEXT, ContentType.TEXT_DELTA ->
        part.text?.takeIf { it.isNotBlank() }?.let { "" to it }

    ContentType.THINK -> part.think
        ?.trim()
        ?.removePrefix("<think>")
        ?.removeSuffix("</think>")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { ClipboardLabels.THINKING to it }

    ContentType.TOOL_CALL -> {
        val call = part.toolCall
        val label = when (call?.type) {
            ToolCallType.CODE_INTERPRETER -> ClipboardLabels.RUN_CODE
            ToolCallType.RETRIEVAL -> ClipboardLabels.RETRIEVAL
            ToolCallType.FILE_SEARCH -> ClipboardLabels.FILE_SEARCH
            else -> ClipboardLabels.TOOL
        }
        val value = call?.let {
            runCatching { clipboardJson.encodeToString(AgentToolCall.serializer(), it) }
                .getOrDefault("null")
        } ?: "null"
        label to value
    }

    ContentType.IMAGE_FILE -> ClipboardLabels.IMAGE to part.imageFile.jsonOrNull()

    ContentType.IMAGE_URL -> ClipboardLabels.IMAGE to (part.imageUrl?.url ?: part.imageUrl.jsonOrNull())

    ContentType.VIDEO_URL -> ClipboardLabels.VIDEO to (part.videoUrl?.url ?: part.videoUrl.jsonOrNull())

    ContentType.INPUT_AUDIO -> ClipboardLabels.AUDIO to part.inputAudio.jsonOrNull()

    ContentType.AGENT_UPDATE -> ClipboardLabels.AGENT_UPDATE to part.agentUpdate.jsonOrNull()

    ContentType.SUMMARY -> ClipboardLabels.SUMMARY to summaryText(part)

    ContentType.STEER ->
        part.steerText()?.let { ClipboardLabels.STEER to it }

    ContentType.ACTIVITY_LABEL ->
        part.activityLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { ClipboardLabels.ACTIVITY to it }
}

/** The summary body: an array of `{type, text}` blocks, a raw string, or the legacy `text`. */
private fun summaryText(part: MessageContentPart): String {
    when (val content = part.content) {
        is JsonArray -> return content.joinToString("") { block ->
            ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
        }
        is JsonPrimitive -> if (content.isString) return content.content
        else -> Unit
    }
    return part.text.orEmpty()
}

private inline fun <reified T : Any> T?.jsonOrNull(): String = when (this) {
    null -> "null"
    else -> runCatching { clipboardJson.encodeToString(kotlinx.serialization.serializer<T>(), this) }
        .getOrDefault(toString())
}
