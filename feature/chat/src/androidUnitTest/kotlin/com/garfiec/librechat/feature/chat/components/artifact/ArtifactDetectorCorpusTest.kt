package com.garfiec.librechat.feature.chat.components.artifact

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Differential corpus: the 25 cases that were run through upstream's **actual** parser
 * (`mdast-util-from-markdown` + `micromark-extension-directive` + gfm, mirroring `parseToMdast` in
 * `client/src/components/Chat/Messages/Content/splitMarkdown.ts`) to establish what the web client
 * really renders. Each row's expectation equals that measured result, except the three rows marked
 * `DELIBERATE DIVERGENCE` — cases 10, 23 and 25, documented in [detectArtifacts]'s KDoc — which pin
 * *our* intended behavior instead. So the scanner agrees with web on 22 of 25, and a change that
 * makes any *other* row fail is a regression, not a new trade-off.
 *
 * "Upstream renders an artifact" means *the card paints*, not
 * merely that micromark emits a directive node. `updateArtifact`
 * (`client/src/components/Artifacts/Artifact.tsx`) early-returns when identifier, type and title are
 * all absent, so `ArtifactButton` receives null and renders nothing. Case 10 is exactly that trap.
 */
class ArtifactDetectorCorpusTest {

    private data class Case(
        val name: String,
        val input: String,
        /** Expected artifact count. */
        val cards: Int,
        /** Expected content of the first artifact, or null when no card is expected. */
        val content: String? = null,
        val complete: Boolean = true,
        val type: String? = null,
        val language: String? = null,
    )

    private val cases = listOf(
        Case("01 baseline 3-fence", art("```md\nhello\n```"), 1, "hello", language = "md"),
        Case("02 4-fence (dev default)", art("````md\nhello\n````"), 1, "hello", language = "md"),
        Case("03 5-fence wrapping 4-fence", art("`````md\n````\ninner\n````\n`````"), 1, "````\ninner\n````"),
        Case("04 4-open / 5-close", art("````md\nhello\n`````"), 1, "hello"),
        // A 3-backtick line cannot close a 4-backtick fence, so the block runs to the container close.
        Case("05 4-open / 3-close", art("````md\nhello\n```"), 1, "hello\n```"),
        Case("06 tilde fence", art("~~~md\nhello\n~~~"), 1, "hello", language = "md"),
        Case("07 unfenced content", art("hello world"), 1, "hello world"),
        // Leaf directive: valid upstream, no body.
        Case("08 leaf directive", "::artifact{identifier=\"x\" type=\"text/markdown\" title=\"X\"}", 1, ""),
        // Text directive is rewritten to literal text upstream — never an artifact.
        Case("09 text directive", "see :artifact{identifier=\"x\"} inline", 0),
        // DELIBERATE DIVERGENCE: micromark emits a node, but no card paints (default-key early return).
        Case("10 no attributes", ":::artifact\n```md\nhello\n```\n:::", 0),
        Case("11 four colons", "::::artifact{identifier=\"x\" type=\"text/markdown\" title=\"X\"}\n" +
            "```md\nhello\n```\n::::", 1, "hello"),
        Case("12 info string w/ attrs", art("```html title=\"a\"\nhello\n```"), 1, "hello", language = "html"),
        Case("13 CRLF line endings", art("```md\nhello\n```").replace("\n", "\r\n"), 1, "hello"),
        Case("14 indented opening fence", art("  ```md\nhello\n  ```"), 1, "hello"),
        Case("15 indented closing colons", "$OPEN\n```md\nhello\n```\n  :::", 1, "hello"),
        // Unclosed with an unclosed fence = truncated mid-artifact. Emitted, but marked incomplete.
        Case("16 unclosed artifact (stream)", "$OPEN\n````md\n# partial heading\nsome text so far",
            1, "# partial heading\nsome text so far", complete = false),
        Case("17 unclosed fence, closed colons", "$OPEN\n````md\nhello\n:::", 1, "hello"),
        Case("18 unquoted attr values", ":::artifact{identifier=x type=text/markdown title=X}\n" +
            "```md\nhello\n```\n:::", 1, "hello", type = "text/markdown"),
        Case("19 blank line before fence", art("\n```md\nhello\n```"), 1, "hello"),
        Case("20 two artifacts (4 then 3)", art("````md\none\n````") + "\n\nsome text\n\n" +
            ":::artifact{identifier=\"y\" type=\"text/markdown\" title=\"X\"}\n```md\ntwo\n```\n:::",
            2, "one"),
        Case("21 3-fence w/ inner 3-fence", art("```md\n```bash\necho hi\n```\n```"), 1, "```bash\necho hi"),
        Case("22 lang hint w/ hyphen", art("```mermaid-js\ngraph TD\n```"), 1, "graph TD", language = "mermaid-js"),
        // DELIBERATE DIVERGENCE: web keeps reading (content `hello\n::: trailing`); the backend
        // scanner closes here, and so do we.
        Case("23 trailing text after close", art("```md\nhello\n```").dropLast(3) + "::: trailing", 1, "hello"),
        Case("24 no newline after close", art("```md\nhello\n```"), 1, "hello"),
        // DELIBERATE DIVERGENCE: web demotes a tab-indented fence to an indented code block and
        // produces garbage (```mdhello); we render the artifact.
        Case("25 tab-indented fence", art("\t```md\nhello\n```"), 1, "hello"),
    )

    @Test
    fun `scanner matches the measured upstream corpus`() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val artifacts = detectArtifacts(case.input)
                .filterIsInstance<ArtifactSegment.ArtifactReference>()
                .map { it.artifact }

            if (artifacts.size != case.cards) {
                failures += "${case.name}: expected ${case.cards} card(s), got ${artifacts.size}"
                continue
            }
            val first = artifacts.firstOrNull() ?: continue
            if (case.content != null && first.content != case.content) {
                failures += "${case.name}: content expected ${case.content.q()}, got ${first.content.q()}"
            }
            if (first.isComplete != case.complete) {
                failures += "${case.name}: isComplete expected ${case.complete}, got ${first.isComplete}"
            }
            case.type?.let { if (first.type != it) failures += "${case.name}: type expected $it, got ${first.type}" }
            case.language?.let {
                if (first.language != it) failures += "${case.name}: language expected $it, got ${first.language}"
            }
        }
        assertEquals("corpus divergences:\n" + failures.joinToString("\n"), emptyList<String>(), failures)
    }

    // ─── behaviours where we intentionally beat upstream's backend scanner ───

    @Test
    fun `directive inside a fenced code block is not an artifact`() {
        // upstream's findAllArtifacts uses a plain indexOf and would eat this; micromark does not,
        // and neither do we. Without this a message explaining the artifact format renders as a card.
        val text = "Here's how the format works:\n\n" +
            "````\n$OPEN\n```md\nhello\n```\n:::\n````\n\nMake sense?"

        val segments = detectArtifacts(text)
        assertEquals(0, segments.count { it is ArtifactSegment.ArtifactReference })
    }

    @Test
    fun `mid-line directive is not an artifact`() {
        val text = "as in $OPEN inline\n```md\nhello\n```\n:::"
        assertEquals(0, detectArtifacts(text).count { it is ArtifactSegment.ArtifactReference })
    }

    @Test
    fun `directive indented four spaces is not an artifact`() {
        // 4+ spaces is an indented code block in CommonMark; micromark emits no directive.
        val text = "    $OPEN\n```md\nhello\n```\n:::"
        assertEquals(0, detectArtifacts(text).count { it is ArtifactSegment.ArtifactReference })
    }

    @Test
    fun `unclosed directive with no fence does not swallow the rest of the message`() {
        // The regression this scanner must not introduce: a stray directive in prose would otherwise
        // take every following paragraph off-screen behind a collapsed artifact button.
        val text = "You can write $OPEN to make one.\n\nIt renders as a card.\n\nHope that helps!"

        val segments = detectArtifacts(text)
        assertEquals(0, segments.count { it is ArtifactSegment.ArtifactReference })
        val rendered = segments.filterIsInstance<ArtifactSegment.Text>().joinToString("\n") { it.text }
        assertEquals(true, rendered.contains("Hope that helps!"))
    }

    @Test
    fun `text after an unclosed artifact whose fence closed is never dropped`() {
        // If the artifact segment ran to EOF while its content stopped at the closing fence,
        // everything between them would belong to no segment at all and vanish —
        // not hidden behind a card, gone. Nothing may ever be invisible.
        val text = "$OPEN\n```html\n<p>hi</p>\n```\n\nAnd here is more discussion that must stay visible."

        val segments = detectArtifacts(text)
        val artifact = segments.filterIsInstance<ArtifactSegment.ArtifactReference>().single().artifact
        assertEquals("<p>hi</p>", artifact.content)
        val rendered = segments.filterIsInstance<ArtifactSegment.Text>().joinToString("\n") { it.text }
        assertEquals("And here is more discussion that must stay visible.", rendered)
    }

    @Test
    fun `prose describing the format keeps its trailing sentence`() {
        val text = "Write this:\n$OPEN\n```html\nyour html\n```\nand then close it with three colons."

        val segments = detectArtifacts(text)
        val rendered = segments.filterIsInstance<ArtifactSegment.Text>().joinToString("\n") { it.text }
        assertEquals(true, rendered.contains("and then close it with three colons."))
    }

    @Test
    fun `a brace inside a quoted attribute value does not void the directive`() {
        // A naive first-`}` scan would truncate the attribute string mid-quote, so `parseAttributes`
        // would hit an unterminated quote and the whole artifact would fall back to raw directive
        // text — exactly the symptom this scanner exists to remove. Upstream renders both of these.
        val braced = detectArtifacts(":::artifact{identifier=\"x\" title=\"The {config} object\"}\n```\nhi\n```\n:::")
            .filterIsInstance<ArtifactSegment.ArtifactReference>()
            .single().artifact
        assertEquals("The {config} object", braced.title)

        val closer = detectArtifacts(":::artifact{identifier=\"x\" title=\"a} b\"}\n```\nhi\n```\n:::")
            .filterIsInstance<ArtifactSegment.ArtifactReference>()
            .single().artifact
        assertEquals("a} b", closer.title)
    }

    @Test
    fun `an empty shortcut voids the directive and dotted shortcuts split`() {
        val empty = ":::artifact{# type=\"text/html\" title=\"T\"}\n```html\nhi\n```\n:::"
        assertEquals(0, detectArtifacts(empty).count { it is ArtifactSegment.ArtifactReference })

        // `#a.b` is id=a plus class=b upstream, not a single id of "a.b".
        val split = detectArtifacts(":::artifact{#a.b type=\"text/html\"}\n```html\nhi\n```\n:::")
            .filterIsInstance<ArtifactSegment.ArtifactReference>()
            .single().artifact
        assertEquals("a", split.identifier)
    }

    @Test
    fun `shorter closing run does not close a longer opening run`() {
        // micromark requires the close to be no shorter than the open; upstream's backend scanner
        // (startsWith(":::")) accepts the short run and is wrong here.
        val text = "::::artifact{identifier=\"x\" type=\"text/markdown\" title=\"X\"}\n" +
            "```md\nhello\n```\n:::\nstill inside\n::::"

        val artifact = detectArtifacts(text)
            .filterIsInstance<ArtifactSegment.ArtifactReference>()
            .single().artifact
        assertEquals("hello", artifact.content)
    }

    // ─── attribute grammar, verified against micromark's factory-attributes.js ───

    @Test
    fun `unquoted value keeps a brace but a quote character voids the directive`() {
        // valueUnquoted consumes `{` into the value, but returns nok on " ' < = > ` — which aborts
        // the whole attribute block, so upstream renders no card at all.
        val kept = detectArtifacts(":::artifact{identifier=x type=a{b title=X}\n```\nhi\n```\n:::")
            .filterIsInstance<ArtifactSegment.ArtifactReference>()
            .single().artifact
        assertEquals("a{b", kept.type)

        val voided = ":::artifact{identifier=x type=a\"b title=X}\n```\nhi\n```\n:::"
        assertEquals(0, detectArtifacts(voided).count { it is ArtifactSegment.ArtifactReference })
    }

    @Test
    fun `label between name and attributes is accepted and dropped`() {
        val text = ":::artifact[Some Label]{identifier=\"x\" type=\"text/html\" title=\"X\"}\n" +
            "```html\nhi\n```\n:::"

        val artifact = detectArtifacts(text)
            .filterIsInstance<ArtifactSegment.ArtifactReference>()
            .single().artifact
        assertEquals("x", artifact.identifier)
        // upstream's extractContent folds the label into the content; we deliberately do not.
        assertEquals("hi", artifact.content)
    }

    @Test
    fun `space before the attribute brace voids the directive`() {
        val text = ":::artifact {identifier=\"x\" type=\"text/html\" title=\"X\"}\n```html\nhi\n```\n:::"
        assertEquals(0, detectArtifacts(text).count { it is ArtifactSegment.ArtifactReference })
    }

    @Test
    fun `hash and dot shortcuts map to id and class`() {
        val text = ":::artifact{#my-id .my-class type=\"text/html\"}\n```html\nhi\n```\n:::"
        val artifact = detectArtifacts(text)
            .filterIsInstance<ArtifactSegment.ArtifactReference>()
            .single().artifact
        // `identifier` is absent, so the `id` shortcut supplies it.
        assertEquals("my-id", artifact.identifier)
    }

    // ─── identifier fallback ───

    @Test
    fun `two attribute-less artifacts get distinct synthetic identifiers`() {
        // The fallback key groups versions across a whole conversation, so it must stay a character
        // offset — a line index would collide across messages and group unrelated artifacts.
        val text = ":::artifact{type=\"text/html\"}\n```\none\n```\n:::\n\n" +
            ":::artifact{type=\"text/html\"}\n```\ntwo\n```\n:::"

        val grouped = groupArtifactVersions(detectArtifacts(text))
        assertEquals(2, grouped.size)
    }

    private companion object {
        const val OPEN = ":::artifact{identifier=\"x\" type=\"text/markdown\" title=\"X\"}"

        /** Wraps [body] in the standard container directive used by most corpus rows. */
        fun art(body: String): String = "$OPEN\n$body\n:::"

        fun String.q(): String = "\"" + replace("\n", "\\n") + "\""
    }
}
