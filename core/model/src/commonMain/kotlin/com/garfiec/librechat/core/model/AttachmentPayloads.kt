package com.garfiec.librechat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The structured payloads a non-file attachment can carry, each nested under a key equal to the
// attachment's own `type`. Mirrors upstream `TAttachmentMetadata` (`packages/data-provider/src/
// schemas.ts`), whose four payload keys are `memory`, `ui_resources`, `web_search` and
// `file_search`; `web_search` lives in `WebSearch.kt` beside the rest of that feature's model.

/**
 * File-search citations, present only when an attachment's `type` is `file_search`. The tool's
 * own output is a flat human-readable digest ("File: … / Relevance: … / Content: …"); the
 * per-source structure — and the `fileId` that ties a citation back to a real uploaded file —
 * exists only here.
 *
 * Mirrors upstream `SearchResultData` for `Tools.file_search`, built by `processFileCitations`
 * (`api/server/services/Files/Citations/index.js`). The server already applies its own relevance
 * threshold and per-file caps before sending, so this is the *selected* set, not everything the
 * retriever matched.
 */
@Serializable
data class FileSearchData(
    val sources: List<FileSearchSource>? = null,
)

/** One retrieved passage. Several sources may share a [fileId] — one per matched chunk. */
@Serializable
data class FileSearchSource(
    val fileId: String? = null,
    val fileName: String? = null,
    /** The matched passage itself. */
    val content: String? = null,
    /** 0..1, higher is better (the server sends `1 - distance`). */
    val relevance: Double? = null,
    /** Pages the passage came from; empty for non-paginated sources. */
    val pages: List<Int>? = null,
    /** Per-page relevance, keyed by page number as a string (a JSON object key always is one). */
    val pageRelevance: Map<String, Double>? = null,
    val metadata: FileSearchSourceMetadata? = null,
)

/** File metadata the server joins in from its own records (`enhanceSourcesWithMetadata`). */
@Serializable
data class FileSearchSourceMetadata(
    val fileType: String? = null,
    val fileBytes: Long? = null,
    val storageType: String? = null,
)

/**
 * One memory write, present only when an attachment's `type` is `memory`.
 *
 * Emitted for both memory modes, and it is the *only* delivery path for the background memory
 * agent (`memory.agent.enabled`), whose tool calls run in a separate sub-run and never become
 * content parts of the reply. Mirrors upstream `MemoryArtifact` (`schemas.ts`), produced by
 * `createMemoryTool`/`createDeleteMemoryTool` (`packages/api/src/agents/memory.ts`).
 */
@Serializable
data class MemoryArtifactData(
    /** The memory's key. `system` on the storage-limit errors, which belong to no single memory. */
    val key: String? = null,
    /** The remembered text on an `update`; absent on a `delete`; a JSON error blob on an `error`. */
    val value: String? = null,
    val tokenCount: Int? = null,
    /** `update` | `delete` | `error`. */
    val type: String? = null,
    /** Set when the write targeted an agent-scoped partition rather than the shared pool. */
    val agentId: String? = null,
)

/**
 * MCP UI resources, present only when an attachment's `type` is `ui_resources`.
 *
 * Carried as raw JSON, not decoded structurally: the server forwards
 * `artifact.ui_resources.data` verbatim and it is not always an array (upstream's own tests cover
 * an index-keyed object), so a typed decode would reject the whole message's `attachments`.
 * Nothing renders it yet — upstream's renderer is `@mcp-ui/client`'s `UIResourceRenderer`.
 */
typealias UiResources = JsonElement
