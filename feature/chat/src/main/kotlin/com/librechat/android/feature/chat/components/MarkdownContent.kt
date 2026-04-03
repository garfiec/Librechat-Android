package com.librechat.android.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.librechat.android.feature.chat.R
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography

/**
 * Markdown content rendering with full CommonMark support. Uses a hybrid approach:
 * - 3-phase extraction preserves code blocks, LaTeX blocks, and inline LaTeX
 * - Custom CodeBlock.kt syntax highlighting and MermaidDiagram WebView for fenced code
 * - KaTeX (WebView) for LaTeX rendering (LatexBlock / LatexInline)
 * - multiplatform-markdown-renderer-m3 for rich text segments (headings, tables,
 *   lists, blockquotes, links, strikethrough, horizontal rules, etc.)
 * - Citation detection preserved for annotated source references
 *
 * @param searchQuery When non-null and non-blank, all occurrences of this string
 *   are highlighted in yellow within text segments. During active search, text
 *   segments use a plain [Text] composable instead of the full markdown renderer
 *   to ensure accurate highlighting.
 * @param searchFocusedOccurrence When >= 0, the occurrence at this index (counted
 *   across all text segments in this message, 0-based) is highlighted in orange
 *   instead of yellow to indicate the currently focused search result.
 * @param onFocusedOccurrencePositioned Callback invoked with the [LayoutCoordinates]
 *   of the text segment containing the focused search occurrence, after it has been
 *   laid out. Used by the parent to fine-tune scroll position within a long message.
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePositioned: ((LayoutCoordinates) -> Unit)? = null,
) {
    val segments = remember(text) { parseMarkdownSegments(text) }
    val isSearchActive = !searchQuery.isNullOrBlank()

    val mdColors = markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        codeText = MaterialTheme.colorScheme.onSurface,
        linkText = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
    )
    val bodyLarge = MaterialTheme.typography.bodyLarge
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val mdTypography = markdownTypography(
        h1 = MaterialTheme.typography.headlineLarge.scaleFontSize(fontSizeMultiplier),
        h2 = MaterialTheme.typography.headlineMedium.scaleFontSize(fontSizeMultiplier),
        h3 = MaterialTheme.typography.headlineSmall.scaleFontSize(fontSizeMultiplier),
        h4 = MaterialTheme.typography.titleLarge.scaleFontSize(fontSizeMultiplier),
        h5 = MaterialTheme.typography.titleMedium.scaleFontSize(fontSizeMultiplier),
        h6 = MaterialTheme.typography.titleSmall.scaleFontSize(fontSizeMultiplier),
        text = bodyLarge.scaleFontSize(fontSizeMultiplier),
        paragraph = bodyLarge.scaleFontSize(fontSizeMultiplier),
        quote = bodyLarge.copy(fontStyle = FontStyle.Italic).scaleFontSize(fontSizeMultiplier),
        code = bodyMedium.copy(fontFamily = FontFamily.Monospace).scaleFontSize(fontSizeMultiplier),
        inlineCode = bodyLarge.copy(fontFamily = FontFamily.Monospace).scaleFontSize(fontSizeMultiplier),
        ordered = bodyLarge.scaleFontSize(fontSizeMultiplier),
        bullet = bodyLarge.scaleFontSize(fontSizeMultiplier),
        list = bodyLarge.scaleFontSize(fontSizeMultiplier),
    )

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        // Track occurrence offset across segments for focused highlighting
        var occurrenceOffset = 0

        segments.forEachIndexed { index, segment ->
            key(index) {
                when (segment) {
                    is MarkdownSegment.CodeBlock -> {
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        if (segment.language?.lowercase() == "mermaid") {
                            MermaidDiagram(
                                code = segment.code,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        } else {
                            CodeBlock(
                                code = segment.code,
                                language = segment.language,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        if (isSearchActive) {
                            occurrenceOffset += countOccurrences(segment.code, searchQuery!!)
                        }
                        if (index < segments.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                    }
                    is MarkdownSegment.LatexBlock -> {
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        LatexBlock(
                            latex = segment.latex,
                            modifier = Modifier.padding(vertical = 4.dp),
                            useKatex = useKatex,
                        )
                        if (index < segments.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                    }
                    is MarkdownSegment.InlineLatexText -> {
                        Column {
                            segment.segments.forEach { inlineSegment ->
                                when (inlineSegment) {
                                    is InlineSegment.Text -> {
                                        if (inlineSegment.text.isNotBlank()) {
                                            if (isSearchActive) {
                                                val segmentOccurrences = countOccurrences(inlineSegment.text, searchQuery!!)
                                                val focusedInSegment = searchFocusedOccurrence - occurrenceOffset
                                                val hasFocus = focusedInSegment in 0 until segmentOccurrences
                                                HighlightedTextSegment(
                                                    content = inlineSegment.text,
                                                    searchQuery = searchQuery,
                                                    focusedOccurrence = focusedInSegment,
                                                    fontSizeMultiplier = fontSizeMultiplier,
                                                    onPositioned = if (hasFocus) onFocusedOccurrencePositioned else null,
                                                )
                                                occurrenceOffset += segmentOccurrences
                                            } else {
                                                MarkdownTextSegment(
                                                    content = inlineSegment.text,
                                                    colors = mdColors,
                                                    typography = mdTypography,
                                                )
                                            }
                                        }
                                    }
                                    is InlineSegment.Latex -> {
                                        LatexInline(
                                            latex = inlineSegment.latex,
                                            useKatex = useKatex,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is MarkdownSegment.Table -> {
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        MarkdownTableWithFullscreen(
                            headers = segment.headers,
                            alignments = segment.alignments,
                            rows = segment.rows,
                            fontSizeMultiplier = fontSizeMultiplier,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        if (isSearchActive) {
                            val tableText = (segment.headers + segment.rows.flatten()).joinToString(" ")
                            occurrenceOffset += countOccurrences(tableText, searchQuery!!)
                        }
                        if (index < segments.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                    }
                    is MarkdownSegment.TextBlock -> {
                        if (isSearchActive) {
                            val segmentOccurrences = countOccurrences(segment.text, searchQuery!!)
                            val focusedInSegment = searchFocusedOccurrence - occurrenceOffset
                            val hasFocus = focusedInSegment in 0 until segmentOccurrences
                            HighlightedTextSegment(
                                content = segment.text,
                                searchQuery = searchQuery,
                                focusedOccurrence = focusedInSegment,
                                fontSizeMultiplier = fontSizeMultiplier,
                                onPositioned = if (hasFocus) onFocusedOccurrencePositioned else null,
                            )
                            occurrenceOffset += segmentOccurrences
                        } else {
                            val hasCitations = remember(segment.text) {
                                segment.text.contains(CITATION_REGEX)
                            }
                            if (hasCitations) {
                                CitationText(
                                    text = segment.text,
                                    fontSizeMultiplier = fontSizeMultiplier,
                                )
                            } else {
                                MarkdownTextSegment(
                                    content = segment.text,
                                    colors = mdColors,
                                    typography = mdTypography,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders a text segment with search highlight spans. When [onPositioned] is non-null
 * (meaning this segment contains the focused search occurrence), attaches an
 * [onGloballyPositioned] modifier so the parent can fine-tune scroll position.
 */
@Composable
private fun HighlightedTextSegment(
    content: String,
    searchQuery: String,
    modifier: Modifier = Modifier,
    focusedOccurrence: Int = -1,
    fontSizeMultiplier: Float = 1.0f,
    onPositioned: ((LayoutCoordinates) -> Unit)? = null,
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val scaledStyle = bodyStyle.scaleFontSize(fontSizeMultiplier)
    val isDarkTheme = isSystemInDarkTheme()
    val highlighted = remember(content, searchQuery, focusedOccurrence, isDarkTheme) {
        buildHighlightedString(content, searchQuery, focusedOccurrence, isDarkTheme)
    }

    val positionedModifier = if (onPositioned != null) {
        Modifier.onGloballyPositioned { coordinates ->
            onPositioned(coordinates)
        }
    } else {
        Modifier
    }

    Text(
        text = highlighted,
        style = scaledStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .then(positionedModifier),
    )
}

/**
 * Scales both fontSize and lineHeight of a TextStyle by the given multiplier.
 * Uses explicit .sp conversion to avoid TextUnit.Unspecified edge cases.
 */
internal fun TextStyle.scaleFontSize(multiplier: Float): TextStyle {
    if (multiplier == 1.0f) return this
    val scaledFontSize = if (fontSize.isSpecified) (fontSize.value * multiplier).sp else fontSize
    val scaledLineHeight = if (lineHeight.isSpecified) (lineHeight.value * multiplier).sp else lineHeight
    return copy(fontSize = scaledFontSize, lineHeight = scaledLineHeight)
}

private val MARKDOWN_SYNTAX = Regex("[#*_~`\\[>|]|\\d+\\. |^-\\s", RegexOption.MULTILINE)

/**
 * Renders a text segment. When the text contains markdown syntax (headings, bold,
 * lists, links, etc.), delegates to the full mikepenz [Markdown] renderer.
 * For plain text without any markdown indicators, renders a lightweight [Text]
 * composable directly -- skipping the expensive AST parse entirely.
 *
 * [colors] and [typography] are pre-computed by the parent [MarkdownContent]
 * so they're allocated once per message rather than once per segment.
 */
@Composable
private fun MarkdownTextSegment(
    content: String,
    colors: MarkdownColors,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier,
) {
    val needsMarkdown = remember(content) { content.contains(MARKDOWN_SYNTAX) }
    if (needsMarkdown) {
        Markdown(
            content = content,
            colors = colors,
            typography = typography,
            modifier = modifier
                .fillMaxWidth(),
        )
    } else {
        Text(
            text = content,
            style = typography.text,
            color = colors.text,
            modifier = modifier
                .fillMaxWidth(),
        )
    }
}

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

/** Column alignment parsed from the separator row of a markdown table. */
internal enum class TableCellAlignment {
    LEFT, CENTER, RIGHT,
}

internal sealed interface InlineSegment {
    data class Text(val text: String) : InlineSegment
    data class Latex(val latex: String) : InlineSegment
}

internal sealed interface InlineSpan {
    val text: String

    data class Plain(override val text: String) : InlineSpan
    data class Bold(override val text: String) : InlineSpan
    data class Italic(override val text: String) : InlineSpan
    data class InlineCode(override val text: String) : InlineSpan
}

// Pre-compiled regex patterns for markdown/LaTeX parsing (avoid recreating on every call)
private val CODE_BLOCK_REGEX = Regex("```(\\w*)[^\\S\\n]*\\n([\\s\\S]*?)```")
private val BLOCK_LATEX_REGEX = Regex("\\$\\$([\\s\\S]+?)\\$\\$|\\\\\\[([\\s\\S]+?)\\\\\\]")
private val INLINE_LATEX_REGEX = Regex("(?<!\\$)\\$(?!\\$)(.+?)(?<!\\$)\\$(?!\\$)|\\\\\\((.+?)\\\\\\)")
private val INLINE_MARKDOWN_REGEX = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|\\*(.+?)\\*")
private val CITATION_REGEX = Regex("""\[\d+]|\u3010\d+\u2020""")

/**
 * Matches a GFM-style markdown table separator row. The separator must contain only
 * pipes, dashes, colons (for alignment), and whitespace.
 */
private val TABLE_SEPARATOR_REGEX = Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?$")

/**
 * HTML tag names that indicate a raw HTML block when found at the start of a TextBlock.
 * These are block-level elements that LLMs commonly produce when generating web pages.
 */
private val HTML_BLOCK_TAG_NAMES = setOf(
    "html", "head", "body", "div", "section", "article", "main", "header", "footer",
    "nav", "aside", "table", "form", "fieldset", "details", "dialog", "figure",
    "figcaption", "template", "canvas", "svg", "video", "audio", "iframe",
    "p", "ul", "ol", "li", "dl", "dt", "dd", "pre", "blockquote", "hr",
)

/**
 * Matches an opening HTML tag at the start of a string (after optional whitespace).
 * Group 1 captures the tag name. Case-insensitive.
 */
private val HTML_OPENING_TAG_REGEX = Regex(
    "^\\s*<(!DOCTYPE\\s+html|\\w+)",
    RegexOption.IGNORE_CASE,
)

/** Characters/keywords that indicate LaTeX content rather than a dollar amount. */
private val LATEX_INDICATORS = setOf('\\', '^', '_', '{', '}')
private val LATEX_KEYWORDS = listOf("frac", "sqrt", "sum", "int", "lim", "infty", "alpha", "beta")

/** Returns true if the content looks like LaTeX rather than a dollar amount. */
private fun looksLikeLatex(content: String): Boolean {
    if (content.any { it in LATEX_INDICATORS }) return true
    return LATEX_KEYWORDS.any { keyword -> content.contains(keyword) }
}

/**
 * Returns true if the text appears to be a raw HTML block that should be rendered
 * as a code block rather than passed to the markdown renderer (which would garble it).
 *
 * Heuristic: starts with an HTML tag whose name is in [HTML_BLOCK_TAG_NAMES] or is
 * a DOCTYPE declaration, AND contains at least one closing tag or is self-contained.
 */
private fun looksLikeHtmlBlock(text: String): Boolean {
    val match = HTML_OPENING_TAG_REGEX.find(text) ?: return false
    val tagName = match.groupValues[1].lowercase().let {
        // Normalize "!doctype html" to "html"
        if (it.startsWith("!doctype")) "html" else it
    }
    if (tagName !in HTML_BLOCK_TAG_NAMES) return false
    // Require a closing tag or self-closing indicator to avoid false positives
    // on lines that just happen to start with "<p" etc.
    val hasClosingTag = text.contains("</$tagName", ignoreCase = true) ||
        text.contains("/>")
    return hasClosingTag
}

/**
 * Scans TextBlock segments for raw HTML blocks and converts them to CodeBlock
 * segments with language="html". This prevents the mikepenz markdown renderer
 * from silently dropping HTML tags.
 *
 * A TextBlock is treated as a raw HTML block if [looksLikeHtmlBlock] returns true.
 * Mixed content (text + HTML) is split: lines before the HTML become a TextBlock,
 * the HTML block becomes a CodeBlock, and lines after become a TextBlock.
 */
private fun extractHtmlBlocks(segments: List<MarkdownSegment>): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()

    for (segment in segments) {
        if (segment !is MarkdownSegment.TextBlock) {
            result.add(segment)
            continue
        }

        val text = segment.text
        if (looksLikeHtmlBlock(text)) {
            // Entire block is HTML -- render as a code block
            result.add(MarkdownSegment.CodeBlock(text.trim(), "html"))
            continue
        }

        // Check if the block contains an embedded HTML section starting on its own line.
        // Split into lines and find the first line that opens an HTML block.
        val lines = text.split('\n')
        var htmlStart = -1
        for (i in lines.indices) {
            val lineText = lines.subList(i, lines.size).joinToString("\n")
            if (looksLikeHtmlBlock(lineText)) {
                htmlStart = i
                break
            }
        }

        if (htmlStart < 0) {
            // No HTML found; keep as-is
            result.add(segment)
            continue
        }

        // Flush preceding text lines
        if (htmlStart > 0) {
            val preceding = lines.subList(0, htmlStart).joinToString("\n").trim()
            if (preceding.isNotEmpty()) {
                result.add(MarkdownSegment.TextBlock(preceding))
            }
        }

        // The rest from htmlStart onward is the HTML block
        val htmlContent = lines.subList(htmlStart, lines.size).joinToString("\n").trim()
        result.add(MarkdownSegment.CodeBlock(htmlContent, "html"))
    }

    return result
}

/**
 * Splits raw text into code-block, LaTeX-block, table, inline-LaTeX, and text-block segments.
 *
 * Parsing order:
 * 1. Extract fenced code blocks (``` ... ```)
 * 2. Detect raw HTML blocks in remaining TextBlocks and convert to CodeBlocks
 * 3. Within non-code segments, extract block LaTeX (`$$...$$`)
 * 4. Within remaining text blocks, extract GFM-style tables
 * 5. Within remaining text, detect inline LaTeX (`$...$`) and split accordingly
 */
private val segmentCache = object : LinkedHashMap<String, List<MarkdownSegment>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MarkdownSegment>>): Boolean {
        return size > 128
    }
}

internal fun parseMarkdownSegments(text: String): List<MarkdownSegment> {
    segmentCache[text]?.let { return it }
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
            // Treat latex/tex/math code blocks as text so LaTeX extraction
            // passes handle $...$ and $$...$$ delimiters properly
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
            // No block LaTeX found; keep original segment
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
                // \(...\) is always LaTeX; $...$ needs heuristic check
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

        // Build mixed inline segments
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

    segmentCache[text] = finalSegments
    return finalSegments
}

/**
 * Extracts markdown tables from a text block, splitting it into alternating
 * TextBlock and Table segments. A valid table requires:
 * - A header row with pipe-delimited cells
 * - A separator row matching [TABLE_SEPARATOR_REGEX]
 * - At least one data row
 */
private fun extractTables(text: String): List<MarkdownSegment> {
    val lines = text.split('\n')
    val result = mutableListOf<MarkdownSegment>()
    val buffer = mutableListOf<String>()
    var i = 0

    while (i < lines.size) {
        // Check if lines[i] could be a table header row and lines[i+1] a separator
        if (i + 2 < lines.size && isTableRow(lines[i]) && isTableSeparator(lines[i + 1])) {
            // Flush any buffered text before the table
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
                // Pad or truncate to match header column count
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
                // Not a valid table (no data rows); treat header+separator as text
                buffer.add(lines[i])
                buffer.add(lines[i + 1])
                i += 2
            }
        } else {
            buffer.add(lines[i])
            i++
        }
    }

    // Flush remaining buffered text
    if (buffer.isNotEmpty()) {
        val remaining = buffer.joinToString("\n").trim()
        if (remaining.isNotEmpty()) {
            result.add(MarkdownSegment.TextBlock(remaining))
        }
    }

    return result
}

/**
 * Returns true if the line looks like a pipe-delimited table row.
 * Requires either leading/trailing pipes or at least two pipe characters,
 * to avoid false positives on prose that contains a single `|`.
 */
private fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.startsWith('|') || trimmed.endsWith('|') || trimmed.count { it == '|' } >= 2
}

/** Returns true if the line matches a GFM table separator pattern. */
private fun isTableSeparator(line: String): Boolean {
    return TABLE_SEPARATOR_REGEX.matches(line.trim())
}

/** Splits a pipe-delimited table row into trimmed cell values. */
private fun parseTableRow(line: String): List<String> {
    val trimmed = line.trim()
    // Remove leading and trailing pipes if present
    val inner = trimmed.removePrefix("|").removeSuffix("|")
    return inner.split('|').map { it.trim() }
}

/**
 * Parses alignment indicators from a separator row.
 * `:---` = LEFT, `:---:` = CENTER, `---:` = RIGHT, `---` = LEFT (default)
 */
private fun parseAlignments(separatorLine: String, columnCount: Int): List<TableCellAlignment> {
    val cells = parseTableRow(separatorLine)
    return List(columnCount) { col ->
        val cell = cells.getOrElse(col) { "---" }.trim()
        val startsColon = cell.startsWith(':')
        val endsColon = cell.endsWith(':')
        when {
            startsColon && endsColon -> TableCellAlignment.CENTER
            endsColon -> TableCellAlignment.RIGHT
            else -> TableCellAlignment.LEFT
        }
    }
}

/**
 * Wraps a [MarkdownTable] in a [Box] with a fullscreen expand button overlaid on
 * the top-right corner. Tapping the button opens a [FullscreenTableDialog].
 */
@Composable
private fun MarkdownTableWithFullscreen(
    headers: List<String>,
    alignments: List<TableCellAlignment>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
) {
    var showFullscreen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        MarkdownTable(
            headers = headers,
            alignments = alignments,
            rows = rows,
            fontSizeMultiplier = fontSizeMultiplier,
        )

        IconButton(
            onClick = { showFullscreen = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Fullscreen,
                contentDescription = stringResource(R.string.cd_expand_table),
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showFullscreen) {
        FullscreenTableDialog(
            headers = headers,
            alignments = alignments,
            rows = rows,
            fontSizeMultiplier = fontSizeMultiplier,
            onDismiss = { showFullscreen = false },
        )
    }
}

/**
 * Fullscreen dialog that renders a markdown table inside a scrollable container
 * (both horizontal and vertical) with a [TopAppBar] for the title and close button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenTableDialog(
    headers: List<String>,
    alignments: List<TableCellAlignment>,
    rows: List<List<String>>,
    fontSizeMultiplier: Float,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            TopAppBar(
                title = { Text(stringResource(R.string.dialog_table)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            val verticalScrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
                    .padding(16.dp),
            ) {
                MarkdownTable(
                    headers = headers,
                    alignments = alignments,
                    rows = rows,
                    fontSizeMultiplier = fontSizeMultiplier,
                )
            }
        }
    }
}

/**
 * Renders a markdown table with a header row and data rows. The table supports
 * horizontal scrolling for wide tables with many columns. Header cells are bold
 * with a bottom divider. Subtle row dividers and column dividers provide structure.
 *
 * Column widths are computed from the text content so that all cells in the same
 * column share a uniform width, giving a proper grid appearance.
 */
@Composable
private fun MarkdownTable(
    headers: List<String>,
    alignments: List<TableCellAlignment>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val bodyStyle = MaterialTheme.typography.bodyMedium.scaleFontSize(fontSizeMultiplier)
    val headerStyle = bodyStyle.copy(fontWeight = FontWeight.Bold)
    val cellPaddingH = 12.dp
    val cellPaddingV = 8.dp

    // Measure column widths from text content using TextMeasurer
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columnCount = headers.size

    val columnWidths = remember(headers, rows, fontSizeMultiplier) {
        val cellPaddingPx = with(density) { (cellPaddingH * 2).roundToPx() }
        List(columnCount) { col ->
            var maxWidth = textMeasurer.measure(
                text = headers[col],
                style = headerStyle,
            ).size.width
            for (row in rows) {
                val cellText = row.getOrElse(col) { "" }
                val measured = textMeasurer.measure(
                    text = cellText,
                    style = bodyStyle,
                ).size.width
                if (measured > maxWidth) maxWidth = measured
            }
            with(density) { (maxWidth + cellPaddingPx).toDp() }
        }
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .clip(MaterialTheme.shapes.small),
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .background(headerBackground),
            ) {
                headers.forEachIndexed { colIndex, header ->
                    if (colIndex > 0) {
                        VerticalDivider(
                            color = dividerColor,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                    TableCell(
                        text = header,
                        style = headerStyle,
                        color = textColor,
                        alignment = alignments.getOrElse(colIndex) { TableCellAlignment.LEFT },
                        cellWidth = columnWidths[colIndex],
                        paddingH = cellPaddingH,
                        paddingV = cellPaddingV,
                    )
                }
            }

            HorizontalDivider(color = dividerColor, thickness = 1.dp)

            // Data rows
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                ) {
                    row.forEachIndexed { colIndex, cell ->
                        if (colIndex > 0) {
                            VerticalDivider(
                                color = dividerColor,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                        TableCell(
                            text = cell,
                            style = bodyStyle,
                            color = textColor,
                            alignment = alignments.getOrElse(colIndex) { TableCellAlignment.LEFT },
                            cellWidth = columnWidths.getOrElse(colIndex) { columnWidths.last() },
                            paddingH = cellPaddingH,
                            paddingV = cellPaddingV,
                        )
                    }
                }
                if (rowIndex < rows.lastIndex) {
                    HorizontalDivider(color = dividerColor, thickness = Dp.Hairline)
                }
            }
        }
    }
}

/**
 * A single table cell with fixed [cellWidth] for uniform column alignment.
 * Text is aligned according to the column's [alignment] setting.
 */
@Composable
private fun TableCell(
    text: String,
    style: TextStyle,
    color: Color,
    alignment: TableCellAlignment,
    cellWidth: Dp,
    paddingH: Dp,
    paddingV: Dp,
    modifier: Modifier = Modifier,
) {
    val textAlign = when (alignment) {
        TableCellAlignment.LEFT -> TextAlign.Start
        TableCellAlignment.CENTER -> TextAlign.Center
        TableCellAlignment.RIGHT -> TextAlign.End
    }

    Box(
        modifier = modifier
            .width(cellWidth)
            .padding(horizontal = paddingH, vertical = paddingV),
        contentAlignment = when (alignment) {
            TableCellAlignment.LEFT -> Alignment.CenterStart
            TableCellAlignment.CENTER -> Alignment.Center
            TableCellAlignment.RIGHT -> Alignment.CenterEnd
        },
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = 10,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Parses inline markdown (bold, italic, inline code) within a text block.
 * Calls [onSpan] for each discovered span.
 */
internal fun parseInlineMarkdown(text: String, onSpan: (InlineSpan) -> Unit) {
    var lastIndex = 0

    INLINE_MARKDOWN_REGEX.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            onSpan(InlineSpan.Plain(text.substring(lastIndex, match.range.first)))
        }
        when {
            match.groupValues[1].isNotEmpty() -> {
                onSpan(InlineSpan.InlineCode(match.groupValues[1]))
            }
            match.groupValues[2].isNotEmpty() -> {
                onSpan(InlineSpan.Bold(match.groupValues[2]))
            }
            match.groupValues[3].isNotEmpty() -> {
                onSpan(InlineSpan.Italic(match.groupValues[3]))
            }
        }
        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) {
        onSpan(InlineSpan.Plain(text.substring(lastIndex)))
    }
}
