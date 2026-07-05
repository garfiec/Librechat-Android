package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

// --- Light mode highlight colors ---
private val SearchHighlightYellowLight = Color(0xFFFFEB3B)
private val SearchHighlightOrangeLight = Color(0xFFFF9800)

// --- Dark mode highlight colors (muted, for readable contrast with light text) ---
private val SearchHighlightYellowDark = Color(0xFF5C4800)
private val SearchHighlightOrangeDark = Color(0xFF804D00)

/**
 * Background color for all search matches (non-focused), adapted to theme.
 */
val SearchHighlightYellow: Color
    @Composable get() = if (isSystemInDarkTheme()) SearchHighlightYellowDark else SearchHighlightYellowLight

/**
 * Background color for the currently focused search match, adapted to theme.
 */
val SearchHighlightOrange: Color
    @Composable get() = if (isSystemInDarkTheme()) SearchHighlightOrangeDark else SearchHighlightOrangeLight

/**
 * Returns the non-focused search highlight color for the given theme.
 */
fun searchHighlightYellow(isDarkTheme: Boolean): Color =
    if (isDarkTheme) SearchHighlightYellowDark else SearchHighlightYellowLight

/**
 * Returns the focused search highlight color for the given theme.
 */
fun searchHighlightOrange(isDarkTheme: Boolean): Color =
    if (isDarkTheme) SearchHighlightOrangeDark else SearchHighlightOrangeLight

/**
 * Builds an [AnnotatedString] from [text] with background spans applied to all
 * case-insensitive occurrences of [query].
 *
 * All matches get a yellow background. If [focusedOccurrence] >= 0,
 * that specific occurrence (0-based within this text) gets an orange background
 * instead to indicate the currently focused search result.
 *
 * @param isDarkTheme When true, uses muted dark-mode highlight colors that maintain
 *   contrast with light text. When false, uses bright highlight colors for light mode.
 */
fun buildHighlightedString(
    text: String,
    query: String,
    focusedOccurrence: Int = -1,
    isDarkTheme: Boolean = false,
): AnnotatedString = addSearchSpans(AnnotatedString(text), query, focusedOccurrence, isDarkTheme)

/**
 * Overlays search-highlight background spans onto an existing [AnnotatedString],
 * preserving its current spans (e.g. code-block syntax coloring). Same yellow/orange
 * semantics as [buildHighlightedString].
 */
fun addSearchSpans(
    base: AnnotatedString,
    query: String,
    focusedOccurrence: Int = -1,
    isDarkTheme: Boolean = false,
): AnnotatedString {
    if (query.isBlank()) return base

    val highlightColor = searchHighlightYellow(isDarkTheme)
    val focusedColor = searchHighlightOrange(isDarkTheme)

    val builder = AnnotatedString.Builder(base)
    var startIndex = 0
    var occurrence = 0

    // Match case-insensitively against the original string (not a lowercased copy): some
    // characters change length when lowercased (e.g. Turkish 'İ' → "i̇"), which would shift
    // every subsequent index and mis-place spans/rects. `indexOf(ignoreCase)` compares
    // char-by-char and returns original-string offsets, so `foundIndex + query.length` stays valid.
    while (true) {
        val foundIndex = base.text.indexOf(query, startIndex, ignoreCase = true)
        if (foundIndex < 0) break

        val bgColor = if (occurrence == focusedOccurrence) {
            focusedColor
        } else {
            highlightColor
        }

        builder.addStyle(
            SpanStyle(background = bgColor),
            start = foundIndex,
            end = foundIndex + query.length,
        )

        occurrence++
        startIndex = foundIndex + query.length
    }
    return builder.toAnnotatedString()
}

/**
 * Renders a text run with search-highlight spans during active in-conversation search.
 * Used by both platforms' MarkdownContent in place of the markdown renderer (plain text
 * keeps occurrence offsets and [TextLayoutResult] character rects tractable).
 *
 * When [onFocusedMatchPosition] is non-null and [focusedOccurrence] falls inside this
 * run, every layout/position pass reports the segment's [LayoutCoordinates] plus the
 * focused match's bounding [Rect] *within* the segment (from [TextLayoutResult]), letting
 * the message list scroll precisely to the match rather than to the whole segment.
 */
@Composable
internal fun HighlightedTextSegment(
    content: String,
    searchQuery: String,
    modifier: Modifier = Modifier,
    focusedOccurrence: Int = -1,
    fontSizeMultiplier: Float = 1.0f,
    onFocusedMatchPosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val scaledStyle = bodyStyle.scaleFontSize(fontSizeMultiplier)
    val isDarkTheme = isSystemInDarkTheme()
    val highlighted = remember(content, searchQuery, focusedOccurrence, isDarkTheme) {
        buildHighlightedString(content, searchQuery, focusedOccurrence, isDarkTheme)
    }
    val focusedRange = remember(content, searchQuery, focusedOccurrence) {
        findOccurrenceRange(content, searchQuery, focusedOccurrence)
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = highlighted,
        style = scaledStyle,
        color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layoutResult = it },
        modifier = modifier
            .fillMaxWidth()
            .reportFocusedMatchPosition(layoutResult, focusedRange, onFocusedMatchPosition),
    )
}

/**
 * Reports the focused search match's position for scroll targeting. When both
 * [onFocusedMatchPosition] and [focusedRange] are non-null, every layout/position pass reports
 * the reporting node's [LayoutCoordinates] plus the match's bounding [Rect] within it (computed
 * from [layout] via [matchBoundingRect]); a no-op otherwise. Shared by every search-highlightable
 * [Text] (plain segments, code blocks, table cells) so the wiring lives in exactly one place.
 */
internal fun Modifier.reportFocusedMatchPosition(
    layout: TextLayoutResult?,
    focusedRange: IntRange?,
    onFocusedMatchPosition: ((LayoutCoordinates, Rect) -> Unit)?,
): Modifier = if (onFocusedMatchPosition != null && focusedRange != null) {
    onGloballyPositioned { coordinates ->
        val laidOut = layout ?: return@onGloballyPositioned
        onFocusedMatchPosition(coordinates, matchBoundingRect(laidOut, focusedRange))
    }
} else {
    this
}

/**
 * Bounding rect of [range] within a laid-out text, in the text node's local coordinates.
 * For matches that wrap lines, spans from the first character's top to the last
 * character's bottom (the full-width union is irrelevant for vertical scrolling).
 */
internal fun matchBoundingRect(layout: TextLayoutResult, range: IntRange): Rect {
    val lastOffset = layout.layoutInput.text.length - 1
    if (lastOffset < 0) return Rect.Zero
    val start = range.first.coerceIn(0, lastOffset)
    val end = range.last.coerceIn(start, lastOffset)
    val startBox = layout.getBoundingBox(start)
    val endBox = layout.getBoundingBox(end)
    return Rect(
        left = min(startBox.left, endBox.left),
        top = min(startBox.top, endBox.top),
        right = max(startBox.right, endBox.right),
        bottom = max(startBox.bottom, endBox.bottom),
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

/**
 * Counts the number of case-insensitive occurrences of [query] in [text].
 */
fun countOccurrences(text: String, query: String): Int {
    if (query.isBlank() || text.isBlank()) return 0
    var count = 0
    var startIndex = 0
    while (true) {
        val foundIndex = text.indexOf(query, startIndex, ignoreCase = true)
        if (foundIndex < 0) break
        count++
        startIndex = foundIndex + query.length
    }
    return count
}
