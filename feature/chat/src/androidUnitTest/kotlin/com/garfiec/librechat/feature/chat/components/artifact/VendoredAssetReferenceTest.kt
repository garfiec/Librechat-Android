package com.garfiec.librechat.feature.chat.components.artifact

import com.garfiec.librechat.feature.chat.components.buildKatexHtml
import com.garfiec.librechat.feature.chat.components.buildMermaidHtml
import com.garfiec.librechat.feature.chat.components.web.VendoredWebAssets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every WebView document must load its scripts and stylesheets from the copies bundled
 * with the app, and must reference only files that are actually bundled.
 *
 * Both halves fail silently, which is why they are pinned here rather than left to
 * review. A remote `<script src>` renders perfectly on a developer's machine and is
 * exactly what F-Droid rejects; a *wrong* relative path renders a blank box with no
 * error anywhere in Kotlin, because the failure happens inside the WebView.
 */
class VendoredAssetReferenceTest {

    private val documents: Map<String, String> = buildMap {
        // The two chat-message renderers, not just the artifact ones: KaTeX is the
        // highest-traffic page here (it loads whenever a message contains math) and the
        // inline mermaid block is a separate document from the mermaid artifact.
        put("katex-block", buildKatexHtml("x^2", displayMode = true, textColorCss = "#000"))
        put("katex-inline", buildKatexHtml("x^2", displayMode = false, textColorCss = "#000"))
        put("mermaid-message", buildMermaidHtml("graph TD; A-->B;", theme = "default"))
        put("mermaid", MermaidWebContent.buildHtml("graph TD; A-->B;", isDarkTheme = false))
        put("mermaid-dark", MermaidWebContent.buildHtml("graph TD; A-->B;", isDarkTheme = true))
        put("markdown", MarkdownWebContent.buildHtml("# Title\n\n```kotlin\nval x = 1\n```", isDarkTheme = false))
        put("markdown-dark", MarkdownWebContent.buildHtml("# Title", isDarkTheme = true))
        put("react", ArtifactWebContent.buildHtml("export default () => <div/>;", "application/vnd.react", false))
        put("html", ArtifactWebContent.buildHtml("<h1>hi</h1>", "text/html", false))
        put("html-full", ArtifactWebContent.buildHtml("<!DOCTYPE html><html><head></head><body/></html>", "text/html", false))
        put("svg", ArtifactWebContent.buildHtml("<svg/>", "image/svg+xml", false))
        put("plain", ArtifactWebContent.buildHtml("hello", "text/plain", false))
    }

    /** Every `src="…"` / `href="…"` in a document, excluding data and blob URIs. */
    private fun references(html: String): List<String> =
        Regex("""(?:src|href)="([^"]+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("data:") || it.startsWith("blob:") }
            .toList()

    @Test
    fun `no document references a remote origin`() {
        documents.forEach { (name, html) ->
            val remote = references(html).filter { it.startsWith("http://") || it.startsWith("https://") }
            assertEquals(emptyList(), remote, "$name loads remote resources")
        }
    }

    /**
     * `img-src https:` is deliberately still allowed and is not checked here. A model can
     * put a remote `<img>` in an artifact and the web client renders it too; that is
     * content the user asked to see, not code the app chose to execute. The directives
     * below are the ones that would let a remote origin *run*.
     */
    @Test
    fun `no content security policy lets a remote origin execute`() {
        val executable = listOf("default-src", "script-src", "style-src", "font-src", "connect-src")
        documents.forEach { (name, html) ->
            Regex("""content="(default-src[^"]*)"""").findAll(html).forEach { match ->
                match.groupValues[1].split(";")
                    .map { it.trim() }
                    .filter { directive -> executable.any { directive.startsWith(it) } }
                    .forEach { directive ->
                        assertTrue(
                            !directive.contains("http"),
                            "$name CSP lets a remote origin execute: $directive",
                        )
                    }
            }
        }
    }

    @Test
    fun `every referenced asset is one the vendoring script actually bundles`() {
        documents.forEach { (name, html) ->
            references(html)
                .filterNot { it.startsWith("#") }
                .forEach { ref ->
                    assertTrue(
                        ref in VendoredWebAssets.FILES,
                        "$name references '$ref', which is not in the vendored manifest. " +
                            "Either the path is wrong or scripts/web-assets.json no longer ships it.",
                    )
                }
        }
    }

    @Test
    fun `the anchor used to locate the asset directory is itself bundled`() {
        assertTrue(VendoredWebAssets.ANCHOR in VendoredWebAssets.FILES)
    }

    @Test
    fun `markdown highlighting goes through the marked-highlight extension`() {
        // marked deleted its `highlight` option in v5. Passing one to setOptions is
        // accepted and silently ignored, so the only evidence of a correct wiring is
        // that the extension is installed at all.
        val html = documents.getValue("markdown")
        assertTrue(html.contains("markedHighlight.markedHighlight("), "highlighting is not wired up")
        assertTrue(
            !Regex("""setOptions\(\{[^}]*highlight:""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(html),
            "highlight passed to setOptions, where marked ignores it",
        )
    }

    @Test
    fun `markdown loads a theme stylesheet that matches the requested theme`() {
        assertTrue(documents.getValue("markdown").contains("""href="highlight/github.min.css""""))
        assertTrue(documents.getValue("markdown-dark").contains("""href="highlight/github-dark.min.css""""))
    }
}
