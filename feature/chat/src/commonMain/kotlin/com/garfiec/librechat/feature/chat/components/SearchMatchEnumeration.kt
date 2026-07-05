package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactSegment
import com.garfiec.librechat.feature.chat.components.artifact.detectArtifacts

// Shared search-occurrence enumeration used by BOTH the ViewModel side
// (InConversationSearchDelegate, to build the flat match list) and the renderer side
// (MessageContentAndActions / MarkdownContent, to resolve which on-screen unit owns the
// focused occurrence). A message's occurrences are numbered by walking it in exact render
// order; any divergence between the two sides makes prev/next focus the wrong occurrence,
// so renderers must consume offsets via these same functions.
//
// Render-order contract (mirrors MessageContentAndActions → ContentPartDispatcher):
//  1. Content parts in list order; only TEXT/TEXT_DELTA (part.text) and THINK (part.think)
//     render searchable text. Messages without parts render message.text via
//     MarkdownContent directly (no artifact split).
//  2. TEXT parts split on artifact directives first (TextContentPart); only the plain
//     Text segments are enumerated — inline-artifact content is highlighted but not
//     navigable (whether it renders inline at all depends on a UI preference the
//     ViewModel cannot see).
//  3. Within a text run, parseMarkdownSegments order. LatexBlock and mermaid CodeBlocks
//     render as native/WebView content with no text layout, so they contribute nothing.
//     Table occurrences are counted per cell (headers left-to-right, then rows
//     row-major) because highlight spans cannot cross cell boundaries.

/** Occurrences contributed by one parsed [MarkdownSegment], in its render order. */
internal fun countSegmentOccurrences(segment: MarkdownSegment, query: String): Int = when (segment) {
    is MarkdownSegment.TextBlock -> countOccurrences(segment.text, query)
    is MarkdownSegment.CodeBlock ->
        if (segment.language?.lowercase() == "mermaid") 0 else countOccurrences(segment.code, query)
    is MarkdownSegment.LatexBlock -> 0
    is MarkdownSegment.InlineLatexText -> segment.segments.sumOf { inline ->
        when (inline) {
            is InlineSegment.Text -> countOccurrences(inline.text, query)
            is InlineSegment.Latex -> 0
        }
    }
    is MarkdownSegment.Table -> tableCellTexts(segment).sumOf { countOccurrences(it, query) }
}

/** Table cell texts in highlight order: headers left-to-right, then rows row-major. */
internal fun tableCellTexts(segment: MarkdownSegment.Table): List<String> =
    segment.headers + segment.rows.flatten()

/** Occurrences in one markdown text run (a whole part, or one artifact Text segment). */
internal fun countMarkdownOccurrences(text: String, query: String): Int {
    // Fast-path bail before the (relatively expensive) parse: every counted match is a substring
    // of [text], so if the query isn't present at all the message contributes nothing. This keeps
    // per-keystroke search enumeration cheap for the many messages that don't match.
    if (text.isBlank() || query.isBlank() || !text.contains(query, ignoreCase = true)) return 0
    return parseMarkdownSegments(text).sumOf { countSegmentOccurrences(it, query) }
}

/** Occurrences in a TEXT part's body, honoring the artifact split (see contract above). */
internal fun countTextPartOccurrences(text: String, query: String): Int {
    // Same fast-path bail: skip detectArtifacts + parsing when the query can't be here.
    if (text.isBlank() || query.isBlank() || !text.contains(query, ignoreCase = true)) return 0
    return detectArtifacts(text).sumOf { segment ->
        when (segment) {
            is ArtifactSegment.Text -> countMarkdownOccurrences(segment.text, query)
            is ArtifactSegment.ArtifactReference -> 0
        }
    }
}

/** Occurrences contributed by one content part, in its render order. */
internal fun countPartOccurrences(part: MessageContentPart, query: String): Int = when (part.type) {
    ContentType.TEXT, ContentType.TEXT_DELTA -> countTextPartOccurrences(part.text.orEmpty(), query)
    ContentType.THINK -> countMarkdownOccurrences(part.think.orEmpty(), query)
    else -> 0
}

/** Total searchable occurrences in a message, numbering the render walk 0-based. */
internal fun countMessageOccurrences(message: Message, query: String): Int {
    val parts = message.content
    return if (!parts.isNullOrEmpty()) {
        parts.sumOf { countPartOccurrences(it, query) }
    } else {
        countMarkdownOccurrences(message.text, query)
    }
}

/**
 * Character range of the [occurrence]-th (0-based) case-insensitive match of [query]
 * in [text], or null when out of range. Must walk matches exactly like
 * [buildHighlightedString] so the orange span and the reported rect agree.
 */
internal fun findOccurrenceRange(text: String, query: String, occurrence: Int): IntRange? {
    if (occurrence < 0 || query.isBlank() || text.isEmpty()) return null
    // Match against the original string (see [addSearchSpans]): a lowercased copy can change
    // length and shift the returned range off the actual characters.
    var startIndex = 0
    var index = 0
    while (true) {
        val foundIndex = text.indexOf(query, startIndex, ignoreCase = true)
        if (foundIndex < 0) return null
        if (index == occurrence) return foundIndex until foundIndex + query.length
        index++
        startIndex = foundIndex + query.length
    }
}
