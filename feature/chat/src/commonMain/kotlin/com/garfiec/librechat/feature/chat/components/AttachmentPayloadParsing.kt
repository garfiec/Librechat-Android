package com.garfiec.librechat.feature.chat.components

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.FileSearchSource
import com.garfiec.librechat.core.model.MemoryArtifactData
import com.garfiec.librechat.core.model.content.MessageContentPart
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Parsing for the structured pseudo-attachment payloads — `file_search` citations and `memory`
// writes. These arrive on their own key alongside `type`, rather than as files, so none of it goes
// through the file/tool-call paths in `ToolCallParsing.kt`.

private val log = Logger.withTag("AttachmentPayloadParsing")

/**
 * Collects file-search citations from a message's `file_search` attachments — the structured
 * counterpart of the digest the tool prints as its output, and the only place the `fileId` and
 * per-page relevance exist.
 *
 * Scoped to a tool call by the same rule as [collectWebSearchSources]. Several sources describe
 * the same file (one per matched chunk), so they're merged the way upstream `extractFileSources`
 * (`RetrievalCall.tsx`) merges them.
 */
internal fun collectFileSearchSources(
    attachments: List<Attachment>,
    toolCallId: String?,
): List<FileSearchCitation> {
    val searchAttachments = attachments.filter {
        it.type == ToolConstants.FILE_SEARCH && it.fileSearch != null
    }
    if (searchAttachments.isEmpty()) return emptyList()
    val scoped = if (toolCallId == null) {
        searchAttachments
    } else {
        searchAttachments.filter { it.toolCallId == toolCallId || it.toolCallId == null }
    }

    val merged = LinkedHashMap<String, FileSearchCitation>()
    scoped.forEach { att ->
        att.fileSearch?.sources.orEmpty().forEach { source ->
            val key = source.fileId ?: source.fileName ?: return@forEach
            val pages = orderedPages(source)
            val existing = merged[key]
            merged[key] = if (existing == null) {
                FileSearchCitation(
                    fileId = key,
                    fileName = source.fileName.orEmpty().ifBlank { key },
                    relevance = source.relevance ?: 0.0,
                    content = source.content.orEmpty(),
                    pages = pages,
                    fileType = source.metadata?.fileType,
                )
            } else {
                existing.copy(
                    relevance = maxOf(existing.relevance, source.relevance ?: 0.0),
                    content = listOf(existing.content, source.content.orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                    pages = (existing.pages + pages).distinct(),
                    fileType = existing.fileType ?: source.metadata?.fileType,
                )
            }
        }
    }
    return merged.values.sortedByDescending { it.relevance }
}

/** A source's pages, most relevant first when the payload says which those are. */
private fun orderedPages(source: FileSearchSource): List<Int> {
    val pages = source.pages.orEmpty()
    val relevance = source.pageRelevance ?: return pages
    return pages.sortedByDescending { relevance[it.toString()] ?: 0.0 }
}

// The storage-limit `errorType` values `set_memory` reports; each maps to its own user-facing
// sentence. Upstream `packages/api/src/agents/memory.ts`; registered in scripts/mirrors.json
// because a rename here degrades silently.
internal const val MEMORY_ERROR_ALREADY_EXCEEDED = "already_exceeded"
internal const val MEMORY_ERROR_WOULD_EXCEED = "would_exceed"

/**
 * One memory write from its attachment payload. An `error` artifact's `value` is a JSON blob
 * rather than a sentence, so it is parsed out here and never carried as [MemoryArtifact.content] —
 * anything unparseable falls back to the generic error label rather than printing the blob.
 */
internal fun memoryArtifactFrom(data: MemoryArtifactData): MemoryArtifact {
    val kind = when (data.type) {
        "delete" -> MemoryChangeKind.DELETE
        "error" -> MemoryChangeKind.ERROR
        else -> MemoryChangeKind.UPDATE
    }
    return MemoryArtifact(
        title = data.key,
        content = data.value.takeIf { kind != MemoryChangeKind.ERROR },
        key = data.key,
        kind = kind,
        error = if (kind == MemoryChangeKind.ERROR) parseMemoryError(data.value) else null,
    )
}

private fun parseMemoryError(value: String?): MemoryErrorInfo? {
    if (value.isNullOrBlank()) return null
    return try {
        val obj = toolCallJson.parseToJsonElement(value).jsonObject
        MemoryErrorInfo(
            errorType = obj["errorType"]?.jsonPrimitive?.contentOrNull,
            tokenCount = obj["tokenCount"]?.jsonPrimitive?.intOrNull,
        )
    } catch (e: Exception) {
        log.d(e) { "Memory error artifact value is not structured JSON" }
        null
    }
}

/**
 * The memory writes belonging to one tool call, for the inline `set_memory`/`delete_memory` card.
 * Scoped by the same rule as [collectWebSearchSources].
 */
internal fun collectMemoryArtifacts(
    attachments: List<Attachment>,
    toolCallId: String?,
): List<MemoryArtifact> {
    val memoryAttachments = attachments.filter {
        it.type == ToolConstants.MEMORY && it.memory != null
    }
    if (memoryAttachments.isEmpty()) return emptyList()
    val scoped = if (toolCallId == null) {
        memoryAttachments
    } else {
        memoryAttachments.filter { it.toolCallId == toolCallId || it.toolCallId == null }
    }
    return scoped.mapNotNull { it.memory?.let(::memoryArtifactFrom) }
}

/**
 * Every tool call id a message renders a card for, including the calls nested one level inside a
 * subagent trace — this must track the depth the dispatcher recurses to, or
 * [collectUnrenderedMemoryArtifacts] double-reports the nested calls' writes.
 *
 * **Not interchangeable with `outputToolCallIds`, which recurses.** That one answers "which calls'
 * output does this subtree own"; this one "which calls have a card". Nested parts render with
 * `allowSubagentCard = false`, so a call two levels down is drawn by nothing and must NOT count.
 */
internal fun renderedToolCallIds(parts: List<MessageContentPart>?): Set<String> {
    if (parts.isNullOrEmpty()) return emptySet()
    val ids = LinkedHashSet<String>()
    parts.forEach { part ->
        val call = part.toolCall ?: return@forEach
        call.id?.takeIf { it.isNotEmpty() }?.let(ids::add)
        call.subagentContent.orEmpty().forEach { nested ->
            nested.toolCall?.id?.takeIf { it.isNotEmpty() }?.let(ids::add)
        }
    }
    return ids
}

/**
 * The memory writes no tool call in this message accounts for — the background memory agent's,
 * whose sub-run never contributes content parts, so the attachment is the only sign it ran.
 *
 * Anything whose `toolCallId` matches a rendered call is left out: that one already has its own
 * inline card, and announcing it again here would double-report a single write. An attachment
 * carrying no `toolCallId` at all cannot be attributed to a call, so it belongs here.
 */
internal fun collectUnrenderedMemoryArtifacts(
    attachments: List<Attachment>,
    renderedToolCallIds: Collection<String>,
): List<MemoryArtifact> = attachments
    .filter { it.type == ToolConstants.MEMORY && it.memory != null }
    .filter { it.toolCallId !in renderedToolCallIds }
    .mapNotNull { it.memory?.let(::memoryArtifactFrom) }
