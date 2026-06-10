package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.ui.media.MediaItem
import com.garfiec.librechat.feature.chat.components.artifact.Artifact
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactSegment
import com.garfiec.librechat.feature.chat.components.artifact.detectArtifacts

/**
 * A non-image attachment surfaced in the conversation-media gallery's **Files** tab.
 * [fileId] is required for the on-tap download; [bytes] drives the size label when known.
 */
data class ConversationFile(
    val fileId: String,
    val filename: String,
    val type: String?,
    val bytes: Long?,
)

/**
 * A URL extracted from a message's text for the gallery's **Links** tab.
 * [host] is the display label (the bare domain); [url] is the full link to open.
 */
data class ConversationLink(
    val url: String,
    val host: String,
)

/**
 * Pulls every viewable image across [messages] for the **Media** tab. Reuses
 * [collectMessageMedia] (the same per-message collector the in-chat viewer uses), so the gallery
 * resolves the exact URLs the messages rendered. Deduped by URL, first occurrence wins — the same
 * contract [MediaItem] requires for use as a pager key.
 */
fun extractConversationMedia(messages: List<Message>, baseUrl: String): List<MediaItem> =
    messages.flatMap { collectMessageMedia(it, baseUrl) }.distinctBy { it.url }

/**
 * Pulls non-image attachments across [messages] for the **Files** tab: user-attached [Message.files]
 * and generated [Message.attachments] whose MIME type is not in the image family. Deduped by file id.
 */
fun extractConversationFiles(messages: List<Message>): List<ConversationFile> {
    val files = mutableListOf<ConversationFile>()
    for (message in messages) {
        message.files?.forEach { file -> file.toConversationFile()?.let(files::add) }
        message.attachments?.forEach { attachment -> attachment.toConversationFile()?.let(files::add) }
    }
    return files.distinctBy { it.fileId }
}

private fun FileReference.toConversationFile(): ConversationFile? {
    if (type?.startsWith("image/") == true) return null
    val id = fileId ?: return null
    return ConversationFile(fileId = id, filename = filename ?: id, type = type, bytes = bytes)
}

private fun Attachment.toConversationFile(): ConversationFile? {
    if (type?.startsWith("image/") == true) return null
    val id = fileId ?: return null
    return ConversationFile(fileId = id, filename = filename ?: id, type = type, bytes = null)
}

// Matches http(s) URLs, stopping before trailing punctuation that's usually prose, not part of the
// link (e.g. a sentence-ending period or a closing paren/bracket/quote/angle).
private val URL_REGEX = Regex("""https?://[^\s<>()\[\]"']+""")

/**
 * Extracts http(s) links from the text of [messages] for the **Links** tab. Reads each message's
 * content-part text (falling back to [Message.text]), the same way `InConversationSearchDelegate`
 * derives searchable text. Deduped by URL.
 */
fun extractConversationLinks(messages: List<Message>): List<ConversationLink> {
    val links = mutableListOf<ConversationLink>()
    for (message in messages) {
        val text = message.searchableText()
        if (text.isBlank()) continue
        URL_REGEX.findAll(text).forEach { match ->
            val url = match.value.trimEnd('.', ',', ';', ':', '!', '?')
            if (url.isNotBlank()) links += ConversationLink(url = url, host = url.host())
        }
    }
    return links.distinctBy { it.url }
}

/**
 * Extracts artifacts (`:::artifact{...}` directives) embedded in the text of [messages] for the
 * **Artifacts** tab, via the same [detectArtifacts] parser the in-chat renderer uses. Grouped by
 * identifier in first-seen order; each group is the version history sorted ascending (so the tile
 * shows the latest version + count, and tapping opens the panel with all versions). Mirrors
 * `groupArtifactVersions` but spans the whole conversation rather than one message.
 */
fun extractConversationArtifacts(messages: List<Message>): List<List<Artifact>> {
    val all = mutableListOf<Artifact>()
    for (message in messages) {
        val text = message.searchableText()
        if (text.isBlank()) continue
        detectArtifacts(text).forEach { segment ->
            if (segment is ArtifactSegment.ArtifactReference) all += segment.artifact
        }
    }
    // groupBy yields a LinkedHashMap, preserving first-seen identifier order.
    return all.groupBy { it.identifier }.map { (_, versions) -> versions.sortedBy { it.version } }
}

private fun Message.searchableText(): String {
    val parts = content
    return if (!parts.isNullOrEmpty()) {
        parts.mapNotNull { it.text ?: it.think }.joinToString("\n")
    } else {
        text
    }
}

/** Bare host for a link's display label, e.g. `https://a.example.com/x?y` → `a.example.com`. */
private fun String.host(): String =
    substringAfter("://").substringBefore('/').substringBefore('?').ifBlank { this }
