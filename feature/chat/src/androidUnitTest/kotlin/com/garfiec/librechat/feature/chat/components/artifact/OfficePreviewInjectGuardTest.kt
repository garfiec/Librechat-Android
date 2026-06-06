package com.garfiec.librechat.feature.chat.components.artifact

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the security-critical HTML-vs-text injection rule for the deferred
 * office-doc preview: `text` is injected as live HTML ONLY when
 * `textFormat == "html"`; otherwise it must be escaped, never injected.
 */
class OfficePreviewInjectGuardTest {

    private val payload = "<script>alert(1)</script><b>hi</b>"

    @Test
    fun `html format injects content verbatim`() {
        val html = ArtifactWebContent.buildOfficePreviewHtml(payload, "html", isDarkTheme = false)
        // The raw tags survive (rendered live) — this is the trusted sanitized-HTML path.
        assertTrue(html.contains(payload))
    }

    @Test
    fun `text format escapes content and never injects raw tags`() {
        val html = ArtifactWebContent.buildOfficePreviewHtml(payload, "text", isDarkTheme = false)
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `null format is treated as text and escaped`() {
        val html = ArtifactWebContent.buildOfficePreviewHtml(payload, null, isDarkTheme = false)
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `unknown format is treated as text and escaped`() {
        val html = ArtifactWebContent.buildOfficePreviewHtml(payload, "markdown", isDarkTheme = false)
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
}
