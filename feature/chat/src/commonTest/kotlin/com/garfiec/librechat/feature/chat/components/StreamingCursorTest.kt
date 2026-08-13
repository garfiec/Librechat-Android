package com.garfiec.librechat.feature.chat.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The placement decisions behind the streaming cursor. This module has no unit-level Compose
 * harness, so the cursor's two branches are pure functions and tested here: [withStreamingCursor]
 * (where the sentinel lands in the markdown) and [canHostInlineCursor] (whether the trailing segment
 * reaches the renderer's annotator at all, or needs the block-level fallback).
 */
class StreamingCursorTest {

    @Test
    fun `sentinel trails the last visible character`() {
        assertEquals("Hello$STREAMING_CURSOR_SENTINEL", withStreamingCursor("Hello"))
    }

    @Test
    fun `trailing whitespace is collapsed before the sentinel`() {
        // A delta that ends with a newline would otherwise put the cursor on the next line, and one
        // ending with a blank line would start a new paragraph for it.
        assertEquals("Hello$STREAMING_CURSOR_SENTINEL", withStreamingCursor("Hello\n"))
        assertEquals("Hello$STREAMING_CURSOR_SENTINEL", withStreamingCursor("Hello\n\n"))
        assertEquals("Hello$STREAMING_CURSOR_SENTINEL", withStreamingCursor("Hello   "))
    }

    @Test
    fun `interior whitespace is untouched`() {
        assertEquals(
            "# Title\n\nBody text$STREAMING_CURSOR_SENTINEL",
            withStreamingCursor("# Title\n\nBody text"),
        )
    }

    @Test
    fun `sentinel is a single invisible character`() {
        // U+2063 INVISIBLE SEPARATOR: if a frame's sentinel is ever left unclaimed by the annotator
        // it must render as nothing, not as a tofu box.
        assertEquals(1, STREAMING_CURSOR_SENTINEL.length)
        assertEquals('⁣', STREAMING_CURSOR_SENTINEL[0])
    }

    @Test
    fun `prose text block hosts the cursor inline`() {
        assertTrue(canHostInlineCursor(MarkdownSegment.TextBlock("Some streamed prose")))
    }

    @Test
    fun `blank text block cannot host the cursor`() {
        assertFalse(canHostInlineCursor(MarkdownSegment.TextBlock("   ")))
    }

    @Test
    fun `citation-bearing text block falls back to the block cursor`() {
        // androidMain routes these to CitationText, which never sees the annotator.
        assertFalse(canHostInlineCursor(MarkdownSegment.TextBlock("As reported [1] earlier")))
    }

    @Test
    fun `text block inside an unclosed code fence falls back to the block cursor`() {
        val midWrite = "Here is the fix:\n\n```kotlin\nval x = 1"
        assertTrue(hasUnclosedCodeFence(midWrite))
        assertFalse(canHostInlineCursor(MarkdownSegment.TextBlock(midWrite)))
    }

    @Test
    fun `text block after a closed fence hosts the cursor inline`() {
        // parseMarkdownSegments lifts closed fences into CodeBlock segments, so a text block should
        // only look unclosed while the fence is genuinely still being written.
        val closed = "```kotlin\nval x = 1\n```\nThat's it"
        assertFalse(hasUnclosedCodeFence(closed))
        assertTrue(canHostInlineCursor(MarkdownSegment.TextBlock(closed)))
    }

    @Test
    fun `module-rendered blocks cannot host the cursor`() {
        assertFalse(canHostInlineCursor(MarkdownSegment.CodeBlock("val x = 1", "kotlin")))
        assertFalse(canHostInlineCursor(MarkdownSegment.LatexBlock("x^2")))
        assertFalse(
            canHostInlineCursor(
                MarkdownSegment.Table(
                    headers = listOf("a", "b"),
                    alignments = listOf(TableCellAlignment.LEFT, TableCellAlignment.LEFT),
                    rows = listOf(listOf("1", "2")),
                ),
            ),
        )
    }

    @Test
    fun `inline-latex segment hosts the cursor only when it ends in text`() {
        assertTrue(
            canHostInlineCursor(
                MarkdownSegment.InlineLatexText(
                    listOf(InlineSegment.Latex("x^2"), InlineSegment.Text(" is the square")),
                ),
            ),
        )
        assertFalse(
            canHostInlineCursor(
                MarkdownSegment.InlineLatexText(
                    listOf(InlineSegment.Text("the square of "), InlineSegment.Latex("x^2")),
                ),
            ),
        )
        assertFalse(
            canHostInlineCursor(
                MarkdownSegment.InlineLatexText(
                    listOf(InlineSegment.Latex("x^2"), InlineSegment.Text("  ")),
                ),
            ),
        )
    }

    @Test
    fun `a streamed reply routes its tail to the expected cursor branch`() {
        // Exercised through the real parse so the segment shapes are the ones the bubble sees.
        assertTrue(canHostInlineCursor(parseMarkdownSegments("Sure, here goes").last()))
        assertFalse(canHostInlineCursor(parseMarkdownSegments("Done:\n\n```kt\nfun a() {}\n```").last()))
        assertFalse(canHostInlineCursor(parseMarkdownSegments("Math:\n\n\$\$a=b\$\$").last()))
    }
}
