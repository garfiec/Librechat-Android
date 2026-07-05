package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Consistency contract for the shared render-order occurrence enumeration
 * (SearchMatchEnumeration). Both the ViewModel (InConversationSearchDelegate)
 * and the renderers count via these helpers; any drift focuses the wrong match,
 * so these tests pin the counting rules the render walk must follow.
 */
class SearchMatchEnumerationTest {

    private fun message(text: String = "", parts: List<MessageContentPart>? = null) = Message(
        messageId = "m1",
        conversationId = "c1",
        text = text,
        content = parts,
    )

    private fun textPart(text: String) = MessageContentPart(type = ContentType.TEXT, text = text)
    private fun thinkPart(think: String) = MessageContentPart(type = ContentType.THINK, think = think)

    // --- plain text / fallback (no parts) ---

    @Test
    fun `no-parts message counts occurrences in message text`() {
        val msg = message(text = "foo bar foo baz foo")
        assertThat(countMessageOccurrences(msg, "foo")).isEqualTo(3)
    }

    @Test
    fun `counting is case-insensitive`() {
        val msg = message(text = "Foo FOO foo fOo")
        assertThat(countMessageOccurrences(msg, "foo")).isEqualTo(4)
    }

    @Test
    fun `blank query yields zero`() {
        assertThat(countMessageOccurrences(message(text = "anything"), "")).isEqualTo(0)
    }

    // --- code blocks ---

    @Test
    fun `code fence language tag is not counted`() {
        // "python" appears only as the fence's language tag, never in the code body.
        val msg = message(text = "```python\nprint('hello')\n```")
        assertThat(countMessageOccurrences(msg, "python")).isEqualTo(0)
    }

    @Test
    fun `matches inside code body are counted`() {
        val msg = message(text = "```kotlin\nval x = x + x\n```")
        assertThat(countMessageOccurrences(msg, "x")).isEqualTo(3)
    }

    @Test
    fun `mermaid code block occurrences are not counted`() {
        // Mermaid renders as a WebView diagram with no text layout; occurrences there
        // are not navigable, so the enumerator must skip them to stay consistent.
        val msg = message(text = "```mermaid\ngraph TD; graph --> node\n```")
        assertThat(countMessageOccurrences(msg, "graph")).isEqualTo(0)
    }

    // --- tables (per-cell) ---

    @Test
    fun `table matches are counted per cell across headers and rows`() {
        val table = """
            | Name | Value |
            |------|-------|
            | foo  | foo   |
            | bar  | foo   |
        """.trimIndent()
        // headers: "Name","Value" (0) + rows: foo,foo,bar,foo (3 foos)
        assertThat(countMessageOccurrences(message(text = table), "foo")).isEqualTo(3)
    }

    @Test
    fun `table cell texts are ordered headers-first then rows row-major`() {
        val table = """
            | A | B |
            |---|---|
            | c | d |
            | e | f |
        """.trimIndent()
        val segment = parseMarkdownSegments(table)
            .filterIsInstance<MarkdownSegment.Table>()
            .single()
        assertThat(tableCellTexts(segment)).containsExactly("A", "B", "c", "d", "e", "f").inOrder()
    }

    // --- multi-part text + think ---

    @Test
    fun `occurrences sum across text and think parts in order`() {
        val msg = message(
            parts = listOf(
                textPart("alpha beta"),
                thinkPart("beta beta"),
                textPart("beta"),
            ),
        )
        // text: 1, think: 2, text: 1
        assertThat(countMessageOccurrences(msg, "beta")).isEqualTo(4)
    }

    @Test
    fun `non-searchable part types contribute nothing`() {
        val msg = message(
            parts = listOf(
                textPart("match"),
                MessageContentPart(type = ContentType.TOOL_CALL, text = "match match"),
                MessageContentPart(type = ContentType.ERROR, text = "match"),
            ),
        )
        assertThat(countMessageOccurrences(msg, "match")).isEqualTo(1)
    }

    @Test
    fun `parts present takes precedence over message text fallback`() {
        // When content parts exist, message.text must NOT be double-counted.
        val msg = message(text = "ghost ghost", parts = listOf(textPart("ghost")))
        assertThat(countMessageOccurrences(msg, "ghost")).isEqualTo(1)
    }

    // --- findOccurrenceRange ---

    @Test
    fun `findOccurrenceRange returns nth match range case-insensitively`() {
        val text = "Foo foo FOO"
        assertThat(findOccurrenceRange(text, "foo", 0)).isEqualTo(0 until 3)
        assertThat(findOccurrenceRange(text, "foo", 1)).isEqualTo(4 until 7)
        assertThat(findOccurrenceRange(text, "foo", 2)).isEqualTo(8 until 11)
    }

    @Test
    fun `findOccurrenceRange out of range is null`() {
        assertThat(findOccurrenceRange("foo foo", "foo", 2)).isNull()
        assertThat(findOccurrenceRange("foo", "foo", -1)).isNull()
    }

    @Test
    fun `findOccurrenceRange stays aligned after a character whose lowercase changes length`() {
        // U+0130 (Turkish 'İ') lowercases to two chars ("i" + combining dot). Indexing a
        // lowercased copy would shift this range by one; matching the original string keeps it exact.
        val text = "İ foo" // 'İ' at 0, space at 1, "foo" at 2..4
        assertThat(findOccurrenceRange(text, "foo", 0)).isEqualTo(2 until 5)
    }

    @Test
    fun `highlight span anchors to original offsets after a length-changing lowercase`() {
        val text = "İ foo"
        val span = buildHighlightedString(text, "foo").spanStyles.single()
        assertThat(span.start).isEqualTo(2)
        assertThat(span.end).isEqualTo(5)
    }

    // --- render-side rebasing contract ---

    @Test
    fun `per-part offset rebasing partitions every global occurrence into exactly one part`() {
        // Reproduces the walk in MessageContentAndActions: each part resolves the focused
        // occurrence as (global - runningOffset). Every message-wide occurrence index must land
        // in exactly one part (no gaps, no overlaps) or prev/next focuses the wrong part.
        val parts = listOf(textPart("beta beta"), thinkPart("beta"), textPart("x beta beta"))
        val counts = parts.map { countPartOccurrences(it, "beta") }
        val total = countMessageOccurrences(message(parts = parts), "beta")
        assertThat(total).isEqualTo(counts.sum())

        for (global in 0 until total) {
            var offset = 0
            val owners = parts.indices.filter { i ->
                val local = global - offset
                offset += counts[i]
                local in 0 until counts[i]
            }
            assertThat(owners).hasSize(1)
        }
    }
}
