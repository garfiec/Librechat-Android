package com.garfiec.librechat.feature.chat.components.artifact

/**
 * Artifact detection.
 *
 * Upstream never parses artifacts with a regex — the format is a remark-directive container inside a
 * CommonMark document, so the real contract is "whatever micromark parses". This is a line scanner
 * ported in shape from `findArtifactClose` / `getOpeningCodeFence` in upstream's
 * `packages/api/src/artifacts/update.ts`, which is close enough to that contract to agree with the
 * web client on 22 of a 25-case differential corpus.
 *
 * The divergences are deliberate, not bugs (the first three are the corpus's divergent rows):
 *  - `::: trailing` closes the artifact here; remark-directive does not, so web swallows the rest of
 *    the message. Upstream's own backend scanner agrees with us — its two implementations disagree
 *    with each other, so "match web exactly" is not a well-defined target.
 *  - A tab-indented content fence renders here; web demotes it to an indented code block and
 *    produces garbage.
 *  - `:::artifact` with no `{…}` produces nothing here. Micromark emits a directive node, but
 *    `updateArtifact` early-returns on the all-defaults key, so no card paints on web either.
 *  - The *closing* `:::` is accepted at any indent, while the opening directive is capped at 3
 *    columns. Micromark would treat a deeply-indented close as an indented code block; upstream's
 *    backend scanner accepts it, and so do we.
 *  - An unclosed directive whose fence *did* close ends its segment at the fence, with the trailing
 *    text emitted as ordinary text. Micromark runs the container to EOF and folds that tail into the
 *    card's content instead. Both keep the tail visible — ours keeps prose out of the artifact.
 *
 * Line endings are normalised to `\n`, so [ArtifactSegment.Text] from a CRLF message loses its `\r`.
 * Harmless for markdown rendering.
 */

private const val ARTIFACT_NAME = "artifact"

/** Cheap bail token: contained by both `:::artifact` and the leaf `::artifact`, but not `:artifact`. */
private const val ARTIFACT_HINT = "::artifact"

/** CommonMark block starts allow at most 3 columns of indent; 4+ is an indented code block. */
private const val MAX_DIRECTIVE_INDENT = 3

private const val TAB_WIDTH = 4

private const val MIN_FENCE_LENGTH = 3

/** Minimum colons for a container directive (`:::`); two colons is a leaf directive. */
private const val MIN_CONTAINER_COLONS = 3

/**
 * Characters that make `micromark-extension-directive` abort the *entire* attribute block
 * (`factory-attributes.js` `valueUnquoted` returns `nok` on these), meaning the directive does not
 * parse at all and no card renders. They do not merely terminate the value. Note `{` is absent —
 * upstream consumes it into the value, so `type=a{b` parses as `a{b`.
 */
private const val ATTR_ABORT_CHARS = "\"'<=>`"

private class CodeFence(val marker: Char, val length: Int)

private class OpeningFence(val fence: CodeFence, val contentStart: Int, val info: String)

private class DirectiveLine(val colons: Int, val attrs: String)

private class ArtifactClose(val start: Int, val end: Int)

/**
 * Detects artifact markers in message text and returns a list of segments with the artifacts
 * extracted. Artifacts whose closing `:::` never arrives are returned with
 * [Artifact.isComplete]` = false` — but only when a code fence was opened, which is what
 * distinguishes a reply truncated mid-artifact from a stray `:::artifact{…}` mentioned in prose.
 * Without that guard the latter would swallow the rest of the message into a collapsed card.
 *
 * **The fence requirement has a real cost, not just a latency one.** An artifact truncated *before*
 * its fence arrives is not detected — including an unfenced artifact truncated mid-content, a form
 * upstream does render. The first two self-heal as soon as the fence streams in; the unfenced case
 * does not. That is the accepted price of the prose guard: dropping to raw directive text is
 * recoverable, silently eating a paragraph is not.
 *
 * Every character of the input ends up in exactly one segment or is deliberately trimmed whitespace.
 * No branch may leave a gap between where an artifact's content stops and where its segment ends —
 * see the `end` computation in `readArtifactAt`.
 */
fun detectArtifacts(text: String): List<ArtifactSegment> {
    // Before normalising: a line scanner has no literal-prefix fast path of its own, and this runs
    // inside a Compose `remember` and per-keystroke during in-conversation search.
    if (!text.contains(ARTIFACT_HINT)) {
        return if (text.isBlank()) emptyList() else listOf(ArtifactSegment.Text(text))
    }
    val source = if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

    val segments = mutableListOf<ArtifactSegment>()
    var lastIndex = 0
    var cursor = 0
    var enclosingFence: CodeFence? = null

    while (cursor < source.length) {
        val lineEnd = lineEndAt(source, cursor)
        val line = source.substring(cursor, lineEnd)

        if (enclosingFence != null) {
            // A `:::artifact` inside a fenced code block is literal text, not a directive. Upstream's
            // backend scanner gets this wrong (plain indexOf); micromark gets it right, and so do we.
            if (isClosingCodeFence(line, enclosingFence)) enclosingFence = null
            cursor = nextLineStart(source, lineEnd)
            continue
        }

        val artifact = readArtifactAt(source, cursor, lineEnd)
        if (artifact != null) {
            appendTextBefore(segments, source, lastIndex, cursor)
            segments.add(ArtifactSegment.ArtifactReference(artifact.artifact))
            lastIndex = artifact.end
            cursor = artifact.end
            continue
        }

        codeFenceOf(line)?.let { enclosingFence = it }
        cursor = nextLineStart(source, lineEnd)
    }

    if (lastIndex < source.length) {
        val remaining = source.substring(lastIndex).trim()
        if (remaining.isNotEmpty()) segments.add(ArtifactSegment.Text(remaining))
    }

    if (segments.isEmpty() && source.isNotBlank()) segments.add(ArtifactSegment.Text(source))

    return segments
}

/**
 * Groups artifacts by identifier and returns a map of identifier to list of
 * versioned artifacts (sorted by version number ascending).
 */
fun groupArtifactVersions(segments: List<ArtifactSegment>): Map<String, List<Artifact>> {
    return segments
        .filterIsInstance<ArtifactSegment.ArtifactReference>()
        .map { it.artifact }
        .groupBy { it.identifier }
        .mapValues { (_, artifacts) -> artifacts.sortedBy { it.version } }
}

private class ScannedArtifact(val artifact: Artifact, val end: Int)

private fun appendTextBefore(
    segments: MutableList<ArtifactSegment>,
    source: String,
    from: Int,
    to: Int,
) {
    if (to <= from) return
    val before = source.substring(from, to).trim()
    if (before.isNotEmpty()) segments.add(ArtifactSegment.Text(before))
}

/** Reads a whole artifact starting on the line at [lineStart], or null when that line isn't one. */
private fun readArtifactAt(source: String, lineStart: Int, lineEnd: Int): ScannedArtifact? {
    val directive = readDirectiveLine(source, lineStart, lineEnd) ?: return null
    val attrs = parseAttributes(directive.attrs) ?: return null

    if (directive.colons < MIN_CONTAINER_COLONS) {
        // Leaf directive `::artifact{…}` — no body, so it ends with its own line.
        val end = nextLineStart(source, lineEnd)
        return ScannedArtifact(buildArtifact(attrs, lineStart, null, "", true), end)
    }

    val close = findArtifactClose(source, lineEnd, directive.colons)
    val contentStart = nextLineStart(source, lineEnd)
    val contentEnd = close?.start ?: source.length
    val opening = openingCodeFence(source, contentStart, contentEnd)

    // Unclosed with no fence at all is ambiguous — most likely prose that merely mentions the
    // directive. Emitting would hide the rest of the message behind a collapsed card.
    if (close == null && opening == null) return null

    val fenceClose = opening?.let {
        findClosingCodeFenceStart(source, it.contentStart, contentEnd, it.fence)
    } ?: -1

    val content = if (opening == null) {
        source.substring(contentStart, contentEnd).trim()
    } else {
        val end = if (fenceClose < 0) contentEnd else fenceClose
        source.substring(opening.contentStart, end).trimEnd()
    }
    val language = opening?.info?.trim()?.takeWhile { !it.isWhitespace() }?.ifEmpty { null }

    // Where the artifact *segment* ends must never run past where its content ends, or the text in
    // between belongs to neither and vanishes from the screen entirely. That happens for an unclosed
    // directive whose fence closed normally: content stops at the fence, so the segment must too, and
    // the tail is emitted as ordinary text. `isComplete` stays false — the directive really is
    // unterminated, and treating it as done would mount a WebView on content the model may still be
    // appending to.
    val end = when {
        close != null -> close.end
        fenceClose >= 0 -> nextLineStart(source, lineEndAt(source, fenceClose))
        else -> source.length
    }

    return ScannedArtifact(
        artifact = buildArtifact(attrs, lineStart, language, content, close != null),
        end = end,
    )
}

private fun buildArtifact(
    attrs: Map<String, String>,
    directiveStart: Int,
    language: String?,
    content: String,
    isComplete: Boolean,
): Artifact = Artifact(
    // Offset into the normalised string. Must stay an offset, not a line index: this key groups
    // versions across a whole conversation, so a line index would collide across messages.
    identifier = attrs["identifier"] ?: attrs["id"] ?: "artifact-$directiveStart",
    type = attrs["type"] ?: "text/plain",
    title = attrs["title"] ?: "Artifact",
    language = language ?: attrs["language"],
    content = content,
    version = attrs["version"]?.toIntOrNull() ?: 1,
    isComplete = isComplete,
)

/** Parses `:::artifact[optional label]{attrs}`, or null when the line isn't an artifact directive. */
private fun readDirectiveLine(source: String, lineStart: Int, lineEnd: Int): DirectiveLine? {
    val line = source.substring(lineStart, lineEnd)
    val firstContent = line.indexOfFirst { !it.isWhitespace() }
    if (firstContent < 0 || indentWidth(line, firstContent) > MAX_DIRECTIVE_INDENT) return null

    var i = firstContent
    var colons = 0
    while (i < line.length && line[i] == ':') {
        colons++
        i++
    }
    // One colon is a text directive, which upstream rewrites to literal text — never an artifact.
    if (colons < 2 || !line.startsWith(ARTIFACT_NAME, i)) return null
    i += ARTIFACT_NAME.length

    // Optional label: `factory-label.js` sits between the name and the attributes upstream.
    // Upstream's extractContent folds the label into the content; we drop it.
    if (i < line.length && line[i] == '[') {
        val labelEnd = line.indexOf(']', i + 1)
        if (labelEnd < 0) return null
        i = labelEnd + 1
    }

    // No space is allowed before the brace — `:::artifact {…}` parses as zero directives upstream.
    if (i >= line.length || line[i] != '{') return null
    val braceEnd = closingBraceIndex(line, i + 1)
    if (braceEnd < 0) return null

    return DirectiveLine(colons, line.substring(i + 1, braceEnd))
}

/**
 * Index of the `}` that closes the attribute block, skipping quoted runs — a `}` inside a quoted
 * value does not end the block upstream, so `title="The {config} object"` is a valid directive.
 * A naive first-`}` scan truncates the attribute string mid-quote and voids the whole artifact.
 */
private fun closingBraceIndex(line: String, from: Int): Int {
    var i = from
    var quote: Char? = null
    while (i < line.length) {
        val c = line[i]
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '}' -> return i
        }
        i++
    }
    return -1
}

/** Visual indent width of [line] up to [index], counting a tab as [TAB_WIDTH] columns. */
private fun indentWidth(line: String, index: Int): Int {
    var width = 0
    for (i in 0 until index) width += if (line[i] == '\t') TAB_WIDTH else 1
    return width
}

/**
 * Attribute parser mirroring `micromark-extension-directive`'s `factory-attributes.js`. Returns null
 * when the attribute block is invalid upstream, in which case the whole directive does not parse.
 */
private fun parseAttributes(attrString: String): Map<String, String>? {
    val attrs = mutableMapOf<String, String>()
    var i = 0
    while (i < attrString.length) {
        val c = attrString[i]
        if (c.isWhitespace()) {
            i++
            continue
        }
        if (c == '#' || c == '.') {
            val start = i + 1
            var end = start
            // `.` and `#` terminate a shortcut rather than extending it, so `#a.b` is id=a + class=b.
            while (end < attrString.length && isAttrNameChar(attrString[end]) &&
                attrString[end] != '.' && attrString[end] != '#'
            ) {
                end++
            }
            // An empty shortcut (`{# …}`) is `nok` upstream — it voids the entire block.
            if (end == start) return null
            attrs[if (c == '#') "id" else "class"] = attrString.substring(start, end)
            i = end
            continue
        }

        val nameStart = i
        if (!isAttrNameStart(c)) return null
        while (i < attrString.length && isAttrNameChar(attrString[i])) i++
        val name = attrString.substring(nameStart, i)

        var j = i
        while (j < attrString.length && attrString[j].isWhitespace()) j++
        if (j >= attrString.length || attrString[j] != '=') {
            attrs[name] = ""
            i = j
            continue
        }
        j++
        while (j < attrString.length && attrString[j].isWhitespace()) j++

        if (j < attrString.length && (attrString[j] == '"' || attrString[j] == '\'')) {
            val quote = attrString[j]
            val valueStart = j + 1
            val valueEnd = attrString.indexOf(quote, valueStart)
            if (valueEnd < 0) return null
            attrs[name] = attrString.substring(valueStart, valueEnd)
            i = valueEnd + 1
        } else {
            var end = j
            // Unquoted values terminate ONLY on whitespace or `}`; `{` is consumed into the value.
            while (end < attrString.length && !attrString[end].isWhitespace() && attrString[end] != '}') {
                if (attrString[end] in ATTR_ABORT_CHARS) return null
                end++
            }
            attrs[name] = attrString.substring(j, end)
            i = end
        }
    }
    return attrs
}

private fun isAttrNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'

private fun isAttrNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '.' || c == ':' || c == '_'

private fun lineEndAt(text: String, start: Int): Int {
    val index = text.indexOf('\n', start)
    return if (index < 0) text.length else index
}

private fun nextLineStart(text: String, lineEnd: Int): Int =
    if (lineEnd >= text.length) text.length else lineEnd + 1

private fun codeFenceOf(line: String): CodeFence? {
    val trimmed = line.trimStart()
    val marker = trimmed.firstOrNull() ?: return null
    if (marker != '`' && marker != '~') return null
    var length = 0
    while (length < trimmed.length && trimmed[length] == marker) length++
    return if (length < MIN_FENCE_LENGTH) null else CodeFence(marker, length)
}

/** CommonMark closing fences must be *at least* as long as the opening one, not exactly equal. */
private fun isClosingCodeFence(line: String, opening: CodeFence): Boolean {
    val trimmed = line.trim()
    var count = 0
    while (count < trimmed.length && trimmed[count] == opening.marker) count++
    if (count < opening.length) return false
    return trimmed.drop(count).all { it.isWhitespace() }
}

/**
 * The `:::` that closes the artifact. Mirrors upstream's `findArtifactClose`, including the fallback:
 * a `:::` seen while inside a code fence is remembered rather than accepted, and a matching closing
 * fence discards it. That is what lets a 4-open/3-close artifact end at its `:::` with the stray
 * fence kept as content.
 */
private fun findArtifactClose(source: String, directiveLineEnd: Int, minColons: Int): ArtifactClose? {
    var cursor = nextLineStart(source, directiveLineEnd)
    var codeFence: CodeFence? = null
    var fallback: ArtifactClose? = null

    while (cursor < source.length) {
        val lineEnd = lineEndAt(source, cursor)
        val line = source.substring(cursor, lineEnd)

        val close = closeRangeAt(source, cursor, lineEnd, minColons)
        if (close != null) {
            if (codeFence == null) return close
            if (fallback == null) fallback = close
        }

        val fence = codeFenceOf(line)
        if (fence != null && codeFence == null) {
            codeFence = fence
        } else if (codeFence != null && isClosingCodeFence(line, codeFence)) {
            codeFence = null
            fallback = null
        }

        cursor = nextLineStart(source, lineEnd)
    }

    return if (codeFence != null) fallback else null
}

/**
 * A closing `:::` run of at least [minColons] — micromark requires the close to be no shorter than
 * the open, so `::::artifact` is not closed by `:::`. Upstream's backend scanner accepts the shorter
 * run; we follow micromark here. Trailing text after the run still closes (see the class KDoc).
 */
private fun closeRangeAt(source: String, lineStart: Int, lineEnd: Int, minColons: Int): ArtifactClose? {
    val line = source.substring(lineStart, lineEnd)
    val firstContent = line.indexOfFirst { !it.isWhitespace() }
    if (firstContent < 0) return null

    var i = firstContent
    var colons = 0
    while (i < line.length && line[i] == ':') {
        colons++
        i++
    }
    if (colons < minColons || line.startsWith(ARTIFACT_NAME, i)) return null

    return ArtifactClose(lineStart + firstContent, lineStart + i)
}

private fun openingCodeFence(source: String, contentStart: Int, contentEnd: Int): OpeningFence? {
    if (contentStart >= contentEnd) return null
    val content = source.substring(contentStart, contentEnd)
    val firstContent = content.indexOfFirst { !it.isWhitespace() }
    if (firstContent < 0) return null

    val fenceStart = contentStart + firstContent
    val lineEnd = lineEndAt(source, fenceStart)
    val line = source.substring(fenceStart, lineEnd)
    val fence = codeFenceOf(line) ?: return null

    return OpeningFence(
        fence = fence,
        contentStart = nextLineStart(source, lineEnd),
        info = line.trimStart().drop(fence.length),
    )
}

private fun findClosingCodeFenceStart(
    source: String,
    start: Int,
    end: Int,
    opening: CodeFence,
): Int {
    var cursor = start
    while (cursor < end) {
        val lineEnd = lineEndAt(source, cursor)
        if (isClosingCodeFence(source.substring(cursor, lineEnd), opening)) return cursor
        cursor = nextLineStart(source, lineEnd)
    }
    return -1
}
