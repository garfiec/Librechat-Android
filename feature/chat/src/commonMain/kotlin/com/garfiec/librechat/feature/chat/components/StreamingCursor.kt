package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import org.intellij.markdown.ast.ASTNode
import org.jetbrains.compose.resources.stringResource

/*
 * The live insertion point of a streaming reply, and the "nothing has arrived yet" state that
 * precedes it.
 *
 * The cursor sits *inside* the text flow, as inline content rather than a sibling composable, so it
 * trails the last word and wraps with the line. The markdown handed to the renderer carries
 * STREAMING_CURSOR_SENTINEL, and the renderer's annotator hook (streamingCursorAnnotate) swaps it for
 * an InlineTextContent placeholder — going through inline content rather than a baked-in glyph is
 * what keeps the cursor a real composable, so it can animate.
 *
 * Tails this module renders itself (code block, table, LaTeX block, artifact card) cannot host inline
 * content and fall back to StandaloneStreamingCursor on the line below; canHostInlineCursor is the
 * decision. See feature/chat/CLAUDE.md → Streaming.
 */

/**
 * Marks where the cursor belongs in the markdown source. U+2063 INVISIBLE SEPARATOR is a formatting
 * character the lexer treats as ordinary text, so if a frame's sentinel is ever left unclaimed (see
 * the leaf guard in [streamingCursorAnnotate]) it renders as nothing rather than as tofu.
 */
internal const val STREAMING_CURSOR_SENTINEL = "⁣"

/** Inline-content id the annotator emits and [rememberStreamingCursorInlineContent] resolves. */
internal const val STREAMING_CURSOR_INLINE_ID = "librechat:streaming_cursor"

private const val CURSOR_PULSE_MS = 620
private const val CURSOR_MIN_ALPHA = 0.25f
private const val CURSOR_HEIGHT_FRACTION = 0.86f
private val CURSOR_BAR_WIDTH = 2.5.dp

private const val WAIT_DOT_COUNT = 3
private const val WAIT_DOT_PERIOD_MS = 560
private const val WAIT_DOT_STAGGER_MS = 150
private const val WAIT_DOT_MIN_SCALE = 0.6f
private const val WAIT_DOT_MIN_ALPHA = 0.3f
private val WAIT_DOT_SIZE = 6.dp
private val WAIT_DOT_GAP = 5.dp

private val FALLBACK_TEXT_SIZE = 16.sp

private val CursorPulseEasing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/**
 * Appends the cursor sentinel to [text], collapsing trailing whitespace so it trails the last visible
 * character. Without the trim, a delta ending in a newline puts the cursor on the next line — or, for
 * a blank line, in a new paragraph.
 */
internal fun withStreamingCursor(text: String): String =
    text.trimEnd() + STREAMING_CURSOR_SENTINEL

/**
 * Whether the markdown library renders [segment]'s tail as annotated text, and can therefore host
 * the inline cursor.
 *
 * Two text blocks are excluded even though the library does render them:
 * - a citation-bearing one, because androidMain routes it to `CitationText`, which builds its own
 *   [androidx.compose.ui.text.AnnotatedString] and never sees the annotator;
 * - one holding an unclosed code fence, because `parseMarkdownSegments` only lifts *closed* fences
 *   out into [MarkdownSegment.CodeBlock] — so mid-write code is still a text block, and the library
 *   renders it through its own code-fence component rather than as annotated text. Without this the
 *   whole of a long code reply would stream with no cursor at all.
 */
internal fun canHostInlineCursor(segment: MarkdownSegment): Boolean = when (segment) {
    is MarkdownSegment.TextBlock ->
        segment.text.isNotBlank() &&
            !segment.text.contains(CITATION_DETECT_REGEX) &&
            !hasUnclosedCodeFence(segment.text)
    is MarkdownSegment.InlineLatexText ->
        (segment.segments.lastOrNull() as? InlineSegment.Text)?.text?.isNotBlank() == true
    is MarkdownSegment.CodeBlock,
    is MarkdownSegment.LatexBlock,
    is MarkdownSegment.Table,
    -> false
}

/** True when [text] ends inside a fenced code block. */
internal fun hasUnclosedCodeFence(text: String): Boolean =
    text.lineSequence().count { it.trimStart().startsWith("```") } % 2 == 1

/**
 * The renderer's per-node hook. It is invoked for every child of an inline container and returning
 * `true` suppresses the library's own handling of that child, so this claims exactly the one leaf
 * holding the sentinel and re-emits it as text + placeholder + text.
 *
 * Composite children are deliberately left alone: their source text carries markup the library is
 * mid-way through interpreting, and re-appending it verbatim would print the markup itself. The
 * sentinel is always the last character of the content, so in practice it lands in a leaf; the guard
 * covers the cases where a half-written construct makes it not.
 *
 * Exposed as a plain lambda so `StreamingCursorAnnotateTest` can run it against a real parse — an
 * annotator that claims nothing is indistinguishable from a working one at compile time.
 */
internal val streamingCursorAnnotate: AnnotatedString.Builder.(String, ASTNode) -> Boolean =
    { content, child ->
        val start = child.startOffset
        val end = child.endOffset
        val claimable = child.children.isEmpty() &&
            start in 0..<end &&
            end <= content.length
        val at = if (claimable) {
            content.substring(start, end).indexOf(STREAMING_CURSOR_SENTINEL)
        } else {
            -1
        }
        if (at < 0) {
            false
        } else {
            val raw = content.substring(start, end)
            append(raw.substring(0, at))
            // A space, not the default U+FFFD: the placeholder replaces it visually either way, but
            // the alternate text is what a screen reader announces and a copy carries.
            appendInlineContent(STREAMING_CURSOR_INLINE_ID, " ")
            append(raw.substring(at + STREAMING_CURSOR_SENTINEL.length))
            true
        }
    }

/** One process-wide instance: the renderer keys internal `remember`s on it. */
internal val StreamingCursorAnnotator: MarkdownAnnotator =
    markdownAnnotator(annotate = streamingCursorAnnotate)

/**
 * Resolves [STREAMING_CURSOR_INLINE_ID] to the animated cursor. The placeholder is sized in `em` so
 * the cursor tracks the surrounding run's font size — the user's font-size setting and a heading the
 * reply is halfway through writing both come out right without being plumbed through.
 *
 * One process-wide instance: the renderer keys an internal `remember` on this map, so a fresh one per
 * composition would rebuild it on every delta of every streaming reply.
 */
private val StreamingCursorInlineContent: Map<String, InlineTextContent> = mapOf(
    STREAMING_CURSOR_INLINE_ID to InlineTextContent(
        Placeholder(
            width = 0.42.em,
            height = 1.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        StreamingCursor(modifier = Modifier.fillMaxSize())
    },
)

@Composable
internal fun rememberStreamingCursorInlineContent(): MarkdownInlineContent =
    markdownInlineContent(StreamingCursorInlineContent)

/** The renderer's no-op default, hoisted so the non-cursor path allocates nothing per composition. */
internal val NoOpMarkdownAnnotator: MarkdownAnnotator = markdownAnnotator()

/** The pulsing bar itself, drawn to whatever box it is given. */
@Composable
internal fun StreamingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "streaming_cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = CURSOR_MIN_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CURSOR_PULSE_MS, easing = CursorPulseEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor_pulse",
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val barWidth = CURSOR_BAR_WIDTH.toPx().coerceAtMost(size.width)
        val barHeight = size.height * CURSOR_HEIGHT_FRACTION
        drawRoundRect(
            color = color,
            alpha = alpha,
            topLeft = Offset(0f, (size.height - barHeight) / 2f),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f),
        )
    }
}

/**
 * Block-level cursor for the tails the markdown library does not render (see [canHostInlineCursor]).
 * Sized from the body text so it matches the line it follows.
 */
@Composable
internal fun StandaloneStreamingCursor(
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
) {
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize
        .takeIf { it.isSpecified } ?: FALLBACK_TEXT_SIZE
    val lineHeight = with(LocalDensity.current) { (fontSize * fontSizeMultiplier).toDp() }
    StreamingCursor(
        modifier = modifier
            .padding(top = 2.dp)
            .width(CURSOR_BAR_WIDTH)
            .height(lineHeight),
    )
}

/**
 * Shown while a run has produced no text yet — a cursor is an insertion point, and before the first
 * delta there is nothing for it to be positioned after.
 */
@Composable
internal fun StreamingWaitIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "streaming_wait")
    val color = MaterialTheme.colorScheme.primary
    val generatingCd = stringResource(Res.string.cd_generating_response)

    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
            .semantics { contentDescription = generatingCd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(WAIT_DOT_COUNT) { index ->
            if (index > 0) Spacer(modifier = Modifier.width(WAIT_DOT_GAP))
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = WAIT_DOT_PERIOD_MS,
                        easing = CursorPulseEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * WAIT_DOT_STAGGER_MS),
                ),
                label = "wait_dot_$index",
            )
            Spacer(
                modifier = Modifier
                    .size(WAIT_DOT_SIZE)
                    .graphicsLayer {
                        val scale = lerp(WAIT_DOT_MIN_SCALE, 1f, progress)
                        scaleX = scale
                        scaleY = scale
                        this.alpha = lerp(WAIT_DOT_MIN_ALPHA, 1f, progress)
                    }
                    .background(color = color, shape = CircleShape),
            )
        }
    }
}
