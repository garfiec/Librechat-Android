package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.ui.media.MediaItem
import com.garfiec.librechat.feature.chat.components.isImageGenToolCall
import com.garfiec.librechat.feature.chat.components.parseImageGenResult
import com.garfiec.librechat.feature.chat.components.parseStreamingImageGenResult
import com.garfiec.librechat.feature.chat.viewmodel.ActiveToolCall

/**
 * Collects every viewable image in the currently-displayed conversation branch, in render order,
 * for the full-screen [com.garfiec.librechat.core.ui.media.ZoomableMediaPager].
 *
 * Mirrors exactly what the message renderers show (see `MessageContentAndActions` /
 * `ContentPartRenderer`): per message, attached image files render above the text, then content
 * parts render in order. Four media sources are covered:
 *  - attached image files ([FileReference] with an image MIME type)
 *  - inline `image_file` / `image_url` content parts
 *  - **persisted** generated images (image-gen tool calls → [parseImageGenResult])
 *  - the **in-flight** generated image still streaming ([activeToolCalls] + [streamingAttachments]
 *    → [parseStreamingImageGenResult]); this never lives in [displayMessages] (the streamed reply
 *    renders as a trailing bubble outside the tree), so it's appended last.
 *
 * Deduplicated by resolved URL (first occurrence wins). This is a pure snapshot — the caller
 * computes it once per open, never continuously, so it never touches Room or `activeBranches`.
 */
internal fun extractBranchMedia(
    displayMessages: List<MessageNode>,
    activeToolCalls: List<ActiveToolCall>,
    streamingAttachments: List<Attachment>,
    baseUrl: String,
): List<MediaItem> {
    val items = mutableListOf<MediaItem>()

    for (node in displayMessages) {
        items += collectMessageMedia(node.message, baseUrl)
    }

    // In-flight generated image (streamed, not yet persisted to a message).
    activeToolCalls.forEach { toolCall ->
        if (isImageGenToolCall(toolCall.name.lowercase())) {
            val result = parseStreamingImageGenResult(toolCall, baseUrl, streamingAttachments)
            val url = result.imageUrl
            if (!url.isNullOrBlank()) {
                items += MediaItem(url, contentDescription = result.prompt.orEmpty())
            }
        }
    }

    return items.distinctBy { it.url }
}

/**
 * Collects the viewable images carried by a single [message], in render order: attached image
 * files first (rendered above the text), then inline `image_file` / `image_url` content parts and
 * persisted image-gen tool-call results in content order. Shared by [extractBranchMedia] (active
 * branch + streaming) and the conversation-wide gallery
 * (`com.garfiec.librechat.feature.chat.util.extractConversationMedia`) so both resolve the exact
 * same URLs as the message renderers. Not deduped — callers dedupe across messages.
 */
internal fun collectMessageMedia(message: Message, baseUrl: String): List<MediaItem> {
    val items = mutableListOf<MediaItem>()

    message.files?.forEach { file ->
        if (file.type?.startsWith("image/") == true) {
            val url = resolveFileReferenceUrl(file, baseUrl)
            if (!url.isNullOrBlank()) {
                items += MediaItem(
                    url = url,
                    contentDescription = file.filename.orEmpty(),
                    filename = file.filename,
                )
            }
        }
    }

    val attachments = message.attachments.orEmpty()
    message.content?.forEach { part ->
        when (part.type) {
            ContentType.IMAGE_FILE -> {
                val url = resolveImageFilePartUrl(part, baseUrl)
                if (!url.isNullOrBlank()) items += MediaItem(url, contentDescription = "")
            }
            ContentType.IMAGE_URL -> {
                val url = part.imageUrl?.url
                if (!url.isNullOrBlank()) items += MediaItem(url, contentDescription = "")
            }
            ContentType.TOOL_CALL -> {
                val toolCall = part.toolCall
                val name = (toolCall?.name ?: toolCall?.function?.name).orEmpty().lowercase()
                if (isImageGenToolCall(name)) {
                    val result = parseImageGenResult(toolCall, baseUrl, attachments)
                    val url = result.imageUrl
                    if (!url.isNullOrBlank()) {
                        items += MediaItem(url, contentDescription = result.prompt.orEmpty())
                    }
                }
            }
            else -> Unit
        }
    }

    return items
}
