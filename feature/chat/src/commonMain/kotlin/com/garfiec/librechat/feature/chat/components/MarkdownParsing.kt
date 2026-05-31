package com.garfiec.librechat.feature.chat.components

// Shared markdown + LaTeX parsing logic used by both Android and iOS MarkdownContent.

internal sealed interface MarkdownSegment {
    data class TextBlock(val text: String) : MarkdownSegment
    data class CodeBlock(val code: String, val language: String?) : MarkdownSegment
    data class LatexBlock(val latex: String) : MarkdownSegment
    data class InlineLatexText(val segments: List<InlineSegment>) : MarkdownSegment
    data class Table(
        val headers: List<String>,
        val alignments: List<TableCellAlignment>,
        val rows: List<List<String>>,
    ) : MarkdownSegment
}

internal enum class TableCellAlignment {
    LEFT, CENTER, RIGHT,
}

internal sealed interface InlineSegment {
    data class Text(val text: String) : InlineSegment
    data class Latex(val latex: String) : InlineSegment
}

// Pre-compiled regex patterns
private val CODE_BLOCK_REGEX = Regex("```(\\w*)[^\\S\\n]*\\n([\\s\\S]*?)```")
private val BLOCK_LATEX_REGEX = Regex("\\$\\$([\\s\\S]+?)\\$\\$|\\\\\\[([\\s\\S]+?)\\\\\\]")
private val INLINE_LATEX_REGEX = Regex("(?<!\\$)\\$(?!\\$)(.+?)(?<!\\$)\\$(?!\\$)|\\\\\\((.+?)\\\\\\)")
internal val CITATION_DETECT_REGEX = Regex("""\[\d+]|\u3010\d+\u2020""")
private val TABLE_SEPARATOR_REGEX = Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?$")

private val HTML_BLOCK_TAG_NAMES = setOf(
    "html", "head", "body", "div", "section", "article", "main", "header", "footer",
    "nav", "aside", "table", "form", "fieldset", "details", "dialog", "figure",
    "figcaption", "template", "canvas", "svg", "video", "audio", "iframe",
    "p", "ul", "ol", "li", "dl", "dt", "dd", "pre", "blockquote", "hr",
)

private val HTML_OPENING_TAG_REGEX = Regex(
    "^\\s*<(!DOCTYPE\\s+html|\\w+)",
    RegexOption.IGNORE_CASE,
)

private val LATEX_INDICATORS = setOf('\\', '^', '_', '{', '}')
private val LATEX_KEYWORDS = listOf("frac", "sqrt", "sum", "int", "lim", "infty", "alpha", "beta")

internal fun looksLikeLatex(content: String): Boolean {
    if (content.any { it in LATEX_INDICATORS }) return true
    return LATEX_KEYWORDS.any { keyword -> content.contains(keyword) }
}

private fun looksLikeHtmlBlock(text: String): Boolean {
    val tagName = htmlOpeningTagName(text) ?: return false
    return hasHtmlClosingTag(text, tagName)
}

/**
 * Returns the normalized block-level HTML tag name that [text] opens with (the regex is
 * anchored at `^\s*<`, so leading whitespace/newlines are skipped), or null if [text] does
 * not begin with a recognized block tag. Split out from [looksLikeHtmlBlock] so callers can
 * test the opening tag against a single line without scanning a whole joined suffix.
 */
private fun htmlOpeningTagName(text: String): String? {
    val match = HTML_OPENING_TAG_REGEX.find(text) ?: return null
    val tagName = match.groupValues[1].lowercase().let {
        if (it.startsWith("!doctype")) "html" else it
    }
    return tagName.takeIf { it in HTML_BLOCK_TAG_NAMES }
}

private fun hasHtmlClosingTag(text: String, tagName: String): Boolean =
    text.contains("</$tagName", ignoreCase = true) || text.contains("/>")

private fun extractHtmlBlocks(segments: List<MarkdownSegment>): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()

    for (segment in segments) {
        if (segment !is MarkdownSegment.TextBlock) {
            result.add(segment)
            continue
        }

        val text = segment.text
        if (looksLikeHtmlBlock(text)) {
            result.add(MarkdownSegment.CodeBlock(text.trim(), "html"))
            continue
        }

        val lines = text.split('\n')
        // The original probed looksLikeHtmlBlock on the entire joined tail for every line
        // (an O(N^2) re-join). Equivalent, cheaper pass: the opening-tag regex is anchored
        // with `^\s*<`, so a suffix opens a block iff its first non-blank line opens one.
        // Walk the non-blank lines testing only that single line for an opening tag (no
        // join); a non-blank line that is not an opening tag can still be followed by one,
        // so keep scanning. Only when a line opens a recognized tag do we materialize the
        // tail once (folding in any contiguous leading blank run, which `^\s*` collapses) to
        // run the closing-tag check, then stop at that first match.
        var htmlStart = -1
        var lineIdx = 0
        while (lineIdx < lines.size) {
            val line = lines[lineIdx]
            if (line.isBlank()) {
                lineIdx++
                continue
            }
            val tagName = htmlOpeningTagName(line)
            if (tagName != null) {
                var start = lineIdx
                while (start > 0 && lines[start - 1].isBlank()) start--
                val suffix = lines.subList(start, lines.size).joinToString("\n")
                if (hasHtmlClosingTag(suffix, tagName)) {
                    htmlStart = start
                    break
                }
            }
            lineIdx++
        }

        if (htmlStart < 0) {
            result.add(segment)
            continue
        }

        if (htmlStart > 0) {
            val preceding = lines.subList(0, htmlStart).joinToString("\n").trim()
            if (preceding.isNotEmpty()) {
                result.add(MarkdownSegment.TextBlock(preceding))
            }
        }

        val htmlContent = lines.subList(htmlStart, lines.size).joinToString("\n").trim()
        result.add(MarkdownSegment.CodeBlock(htmlContent, "html"))
    }

    return result
}

/**
 * 5-phase markdown parser that extracts code blocks, HTML blocks, block LaTeX,
 * tables, and inline LaTeX from raw text.
 */
internal fun parseMarkdownSegments(text: String): List<MarkdownSegment> {
    // --- Pass 1: split on fenced code blocks ---
    val afterCodeBlocks = mutableListOf<MarkdownSegment>()
    var lastIndex = 0

    CODE_BLOCK_REGEX.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            val textBefore = text.substring(lastIndex, match.range.first).trim()
            if (textBefore.isNotEmpty()) {
                afterCodeBlocks.add(MarkdownSegment.TextBlock(textBefore))
            }
        }
        val language = match.groupValues[1].ifEmpty { null }
        val code = match.groupValues[2].trimEnd()
        val langLower = language?.lowercase()
        if (langLower == "latex" || langLower == "tex" || langLower == "math") {
            afterCodeBlocks.add(MarkdownSegment.TextBlock(code))
        } else {
            afterCodeBlocks.add(MarkdownSegment.CodeBlock(code, language))
        }
        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) {
        val remaining = text.substring(lastIndex).trim()
        if (remaining.isNotEmpty()) {
            afterCodeBlocks.add(MarkdownSegment.TextBlock(remaining))
        }
    }

    if (afterCodeBlocks.isEmpty() && text.isNotBlank()) {
        afterCodeBlocks.add(MarkdownSegment.TextBlock(text))
    }

    // --- Pass 2: detect raw HTML blocks and convert to CodeBlocks ---
    val afterHtmlBlocks = extractHtmlBlocks(afterCodeBlocks)

    // --- Pass 3: split TextBlocks on block LaTeX ($$...$$ or \[...\]) ---
    val afterBlockLatex = mutableListOf<MarkdownSegment>()

    for (segment in afterHtmlBlocks) {
        if (segment !is MarkdownSegment.TextBlock) {
            afterBlockLatex.add(segment)
            continue
        }
        var segLastIndex = 0
        val segText = segment.text

        BLOCK_LATEX_REGEX.findAll(segText).forEach { match ->
            if (match.range.first > segLastIndex) {
                val before = segText.substring(segLastIndex, match.range.first).trim()
                if (before.isNotEmpty()) {
                    afterBlockLatex.add(MarkdownSegment.TextBlock(before))
                }
            }
            val latexContent = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            if (latexContent.isNotEmpty()) {
                afterBlockLatex.add(MarkdownSegment.LatexBlock(latexContent))
            }
            segLastIndex = match.range.last + 1
        }

        if (segLastIndex < segText.length) {
            val remaining = segText.substring(segLastIndex).trim()
            if (remaining.isNotEmpty()) {
                afterBlockLatex.add(MarkdownSegment.TextBlock(remaining))
            }
        } else if (segLastIndex == 0) {
            afterBlockLatex.add(segment)
        }
    }

    // --- Pass 4: extract markdown tables from TextBlocks ---
    val afterTables = mutableListOf<MarkdownSegment>()

    for (segment in afterBlockLatex) {
        if (segment !is MarkdownSegment.TextBlock) {
            afterTables.add(segment)
            continue
        }
        afterTables.addAll(extractTables(segment.text))
    }

    // --- Pass 5: detect inline LaTeX ($...$ or \(...\)) within remaining TextBlocks ---
    val finalSegments = mutableListOf<MarkdownSegment>()

    for (segment in afterTables) {
        if (segment !is MarkdownSegment.TextBlock) {
            finalSegments.add(segment)
            continue
        }

        val matches = INLINE_LATEX_REGEX.findAll(segment.text)
            .filter { match ->
                val dollarContent = match.groupValues[1]
                val parenContent = match.groupValues[2]
                if (parenContent.isNotBlank()) {
                    true
                } else {
                    dollarContent.isNotBlank() && looksLikeLatex(dollarContent)
                }
            }
            .toList()

        if (matches.isEmpty()) {
            finalSegments.add(segment)
            continue
        }

        val inlineSegments = mutableListOf<InlineSegment>()
        var segLastIndex = 0
        val segText = segment.text

        for (match in matches) {
            if (match.range.first > segLastIndex) {
                val before = segText.substring(segLastIndex, match.range.first)
                if (before.isNotEmpty()) {
                    inlineSegments.add(InlineSegment.Text(before))
                }
            }
            val content = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            inlineSegments.add(InlineSegment.Latex(content))
            segLastIndex = match.range.last + 1
        }

        if (segLastIndex < segText.length) {
            val remaining = segText.substring(segLastIndex)
            if (remaining.isNotEmpty()) {
                inlineSegments.add(InlineSegment.Text(remaining))
            }
        }

        finalSegments.add(MarkdownSegment.InlineLatexText(inlineSegments))
    }

    return finalSegments
}

private fun extractTables(text: String): List<MarkdownSegment> {
    val lines = text.split('\n')
    val result = mutableListOf<MarkdownSegment>()
    val buffer = mutableListOf<String>()
    var i = 0

    while (i < lines.size) {
        if (i + 2 < lines.size && isTableRow(lines[i]) && isTableSeparator(lines[i + 1])) {
            if (buffer.isNotEmpty()) {
                val preceding = buffer.joinToString("\n").trim()
                if (preceding.isNotEmpty()) {
                    result.add(MarkdownSegment.TextBlock(preceding))
                }
                buffer.clear()
            }

            val headerCells = parseTableRow(lines[i])
            val alignments = parseAlignments(lines[i + 1], headerCells.size)
            val dataRows = mutableListOf<List<String>>()
            var j = i + 2

            while (j < lines.size && isTableRow(lines[j])) {
                val rowCells = parseTableRow(lines[j])
                val normalized = List(headerCells.size) { col ->
                    rowCells.getOrElse(col) { "" }
                }
                dataRows.add(normalized)
                j++
            }

            if (dataRows.isNotEmpty()) {
                result.add(MarkdownSegment.Table(headerCells, alignments, dataRows))
                i = j
            } else {
                buffer.add(lines[i])
                buffer.add(lines[i + 1])
                i += 2
            }
        } else {
            buffer.add(lines[i])
            i++
        }
    }

    if (buffer.isNotEmpty()) {
        val remaining = buffer.joinToString("\n").trim()
        if (remaining.isNotEmpty()) {
            result.add(MarkdownSegment.TextBlock(remaining))
        }
    }

    return result
}

private fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.startsWith('|') || trimmed.endsWith('|') || trimmed.count { it == '|' } >= 2
}

private fun isTableSeparator(line: String): Boolean = TABLE_SEPARATOR_REGEX.matches(line.trim())

internal fun parseTableRow(line: String): List<String> {
    val inner = line.trim().removePrefix("|").removeSuffix("|")
    return inner.split('|').map { it.trim() }
}

internal fun parseAlignments(separatorLine: String, columnCount: Int): List<TableCellAlignment> {
    val cells = parseTableRow(separatorLine)
    return List(columnCount) { col ->
        val cell = cells.getOrElse(col) { "---" }.trim()
        when {
            cell.startsWith(':') && cell.endsWith(':') -> TableCellAlignment.CENTER
            cell.endsWith(':') -> TableCellAlignment.RIGHT
            else -> TableCellAlignment.LEFT
        }
    }
}
