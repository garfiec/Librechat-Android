package com.garfiec.librechat.feature.chat.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs [streamingCursorAnnotate] against a real parse of cursor-bearing markdown.
 *
 * The cursor's whole mechanism is "the sentinel lands in a leaf node that the annotator claims and
 * replaces with an inline-content placeholder". Nothing about that is visible to the compiler: an
 * annotator that claims no node, or claims one and drops the surrounding text, builds and renders
 * exactly like a working one — minus the cursor. So these tests walk the AST the renderer would walk
 * and assert on what the annotator produced. They do not cover the renderer *calling* the hook, which
 * is library behaviour.
 */
class StreamingCursorAnnotateTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor())

    /**
     * Offers every leaf to the annotator, as the renderer's traversal does. Only claimed leaves
     * contribute text: the renderer's default handling of the leaves it keeps is not modelled here,
     * so the built string is the annotator's own output, not a full render.
     */
    private fun annotateLeaves(markdown: String): Pair<AnnotatedString, Int> {
        var claimed = 0
        val built = buildAnnotatedString {
            fun walk(node: ASTNode) {
                if (node.children.isEmpty()) {
                    if (streamingCursorAnnotate(markdown, node)) claimed++
                    return
                }
                node.children.forEach(::walk)
            }
            walk(parser.buildMarkdownTreeFromString(markdown))
        }
        return built to claimed
    }

    private fun AnnotatedString.inlineCursorRanges() =
        getStringAnnotations(0, length).filter { it.item == STREAMING_CURSOR_INLINE_ID }

    @Test
    fun `claims exactly one node in a plain paragraph and emits the placeholder`() {
        val (built, claimed) = annotateLeaves(withStreamingCursor("Hello there"))

        assertEquals(1, claimed)
        val ranges = built.inlineCursorRanges()
        assertEquals(1, ranges.size)
        // The placeholder must sit at the very end, after the last word.
        assertEquals(built.length, ranges.single().end)
    }

    @Test
    fun `the sentinel never reaches the rendered text`() {
        val (built, _) = annotateLeaves(withStreamingCursor("Hello there"))

        assertFalse(built.text.contains(STREAMING_CURSOR_SENTINEL))
        // Text either side of the sentinel is preserved; only the alternate space is added.
        assertEquals("Hello there ", built.text)
    }

    @Test
    fun `claims the trailing leaf when the sentinel follows inline markup`() {
        // "**bold**⁣" — the sentinel is a sibling of the emphasis node, not inside it.
        val (built, claimed) = annotateLeaves(withStreamingCursor("a **bold** word"))

        assertEquals(1, claimed)
        assertEquals(1, built.inlineCursorRanges().size)
        assertFalse(built.text.contains(STREAMING_CURSOR_SENTINEL))
    }

    @Test
    fun `claims the trailing leaf mid-way through writing emphasis`() {
        // Half-written markup is the common streaming case: no EMPH node exists yet.
        val (built, claimed) = annotateLeaves(withStreamingCursor("a **bol"))

        assertEquals(1, claimed)
        assertEquals(1, built.inlineCursorRanges().size)
        assertFalse(built.text.contains(STREAMING_CURSOR_SENTINEL))
    }

    @Test
    fun `claims the trailing leaf inside a list item`() {
        val (built, claimed) = annotateLeaves(withStreamingCursor("- one\n- two"))

        assertEquals(1, claimed)
        assertEquals(1, built.inlineCursorRanges().size)
    }

    @Test
    fun `claims the trailing leaf inside a heading`() {
        val (built, claimed) = annotateLeaves(withStreamingCursor("## Results so far"))

        assertEquals(1, claimed)
        assertEquals(1, built.inlineCursorRanges().size)
    }

    @Test
    fun `claims nothing when there is no cursor to place`() {
        val (built, claimed) = annotateLeaves("Hello there")

        assertEquals(0, claimed)
        assertTrue(built.inlineCursorRanges().isEmpty())
    }

    @Test
    fun `an unclaimed sentinel is invisible rather than markup`() {
        // The routing in canHostInlineCursor keeps the sentinel out of segments the annotator cannot
        // serve, but if one ever leaks it must degrade to nothing on screen — see the sentinel choice.
        val leaked = withStreamingCursor("text")
        assertEquals("text$STREAMING_CURSOR_SENTINEL", leaked)
        assertTrue(STREAMING_CURSOR_SENTINEL[0].isDefined())
    }
}
