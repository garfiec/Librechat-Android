package com.garfiec.librechat.feature.chat.components.web

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactWebContent
import com.garfiec.librechat.feature.chat.components.artifact.MarkdownWebContent
import com.garfiec.librechat.feature.chat.components.artifact.MermaidWebContent
import com.garfiec.librechat.feature.chat.components.buildKatexHtml
import com.garfiec.librechat.feature.chat.components.buildMermaidHtml
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Proves on a real device that the vendored web assets actually load and execute.
 *
 * Nothing in the unit-test suite can establish this. `VendoredAssetReferenceTest` checks that
 * every referenced path is one the vendoring script ships, but a path that exists in the
 * manifest still says nothing about whether the WebView can *reach* it: the whole Android
 * scheme rests on `loadDataWithBaseURL` resolving relative subresources against a
 * `file:///android_asset/…` base while `settings.allowFileAccess = false`. If that assumption
 * is wrong, every gate stays green and every renderer draws an empty box.
 *
 * So each case here loads the renderer's own HTML — the real builder output, not a fixture —
 * into a WebView configured exactly as the app configures it, and asserts against the live DOM
 * that the library defined itself *and* produced output. Asserting only that a global exists
 * would miss the more common failure, which is a script that loads and then renders nothing.
 */
@RunWith(AndroidJUnit4::class)
class VendoredWebAssetInstrumentedTest {

    // ── Assets resolve at all ─────────────────────────────────────────

    @Test
    fun theBaseUrlResolvesIntoTheApkAssets() {
        val base = runBlocking { webAssetBaseUrl() }
        assertTrue(
            "base URL should live under android_asset, was: $base",
            base.startsWith(ANDROID_ASSET_PREFIX),
        )
        assertTrue("base URL must end in a separator to resolve relative paths", base.endsWith("/"))
    }

    @Test
    fun everyManifestedFileIsPackagedInTheApk() {
        // A file can be present in the source tree, listed in the manifest, hashed by the lock,
        // and still not reach the APK -- at which point the only symptom is an empty WebView.
        //
        // This is checked through AssetManager rather than from inside the page, because a
        // document loaded from a file: base has an opaque origin: fetch() and XHR against it are
        // refused by CORS whatever the CSP says, so a JS probe would report every file missing
        // even when all of them load. What the WebView can actually *reach* is established by
        // the renderer cases below, which load these files the way the app does.
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val root = runBlocking { webAssetBaseUrl() }.removePrefix(ANDROID_ASSET_PREFIX)

        val unreadable = VendoredWebAssets.FILES.filter { path ->
            runCatching { assets.open(root + path).use { it.read() } }.isFailure
        }

        assertTrue("not packaged in the APK: $unreadable", unreadable.isEmpty())
    }

    // ── Chat-message renderers ────────────────────────────────────────

    @Test
    fun katexRendersMathWithItsBundledFonts() {
        val page = render(buildKatexHtml("E = mc^2", displayMode = true, textColorCss = "rgb(0,0,0)"))

        assertEquals("katex should define itself", "\"object\"", page.await("typeof katex"))
        assertEquals(
            "katex should have produced markup",
            "true",
            page.awaitTruthy("document.querySelectorAll('.katex').length > 0"),
        )
        // The fonts are the half most likely to break quietly: a missing woff2 falls back to a
        // serif face, which still renders legible math and so survives a visual glance.
        assertEquals(
            "KaTeX_Main should have loaded from the bundled woff2",
            "\"true\"",
            page.awaitTruthy(
                "document.fonts.ready.then(function () {" +
                    "window.__fonts = String(document.fonts.check('12px KaTeX_Main')); });" +
                    "window.__fonts",
            ),
        )
        page.assertNoConsoleErrors()
    }

    @Test
    fun mermaidRendersADiagramInAChatMessage() {
        val page = render(buildMermaidHtml("graph TD; A-->B;", theme = "default"))

        assertEquals("mermaid should define itself", "\"object\"", page.await("typeof mermaid"))
        assertEquals(
            "mermaid should have produced an svg",
            "true",
            page.awaitTruthy("document.querySelectorAll('svg').length > 0"),
        )
    }

    // ── Artifact renderers ────────────────────────────────────────────

    @Test
    fun mermaidRendersAnArtifactDiagram() {
        val page = render(MermaidWebContent.buildHtml("graph LR; X-->Y;", isDarkTheme = false))

        assertEquals("\"object\"", page.await("typeof mermaid"))
        assertEquals("true", page.awaitTruthy("document.querySelectorAll('svg').length > 0"))
    }

    @Test
    fun markdownArtifactsHighlightCodeWithTheBundledTheme() {
        val markdown = "# Title\n\n```kotlin\nval x: Int = 1\n```\n"
        val page = render(MarkdownWebContent.buildHtml(markdown, isDarkTheme = false))

        assertEquals("marked should define itself", "\"object\"", page.await("typeof marked"))
        assertEquals("hljs should define itself", "\"object\"", page.await("typeof hljs"))
        // Highlighting had been dead for two independent reasons before the libraries were
        // bundled, and in both cases the page still rendered readable markdown. Asserting that
        // `marked` exists would have passed against the broken build too.
        assertEquals(
            "the code block should carry hljs token markup",
            "true",
            page.awaitTruthy("document.querySelectorAll('code .hljs-keyword').length > 0"),
        )
        // The theme stylesheet is a separate file from the highlighter, so token spans can
        // exist while the CSS that colours them is missing.
        val keywordColor =
            page.awaitTruthy("getComputedStyle(document.querySelector('code .hljs-keyword')).color")
        assertTrue(
            "the hljs theme stylesheet should have coloured the keyword, was: $keywordColor",
            keywordColor != "\"rgb(0, 0, 0)\"",
        )
    }

    @Test
    fun htmlArtifactsCompileTailwindUtilities() {
        val html = "<div id=\"probe\" class=\"text-red-500\">tailwind</div>"
        val page = render(ArtifactWebContent.buildHtml(html, type = "text/html", isDarkTheme = false))

        assertEquals("tailwind should define itself", "\"object\"", page.await("typeof tailwind"))
        // Tailwind is a JIT compiler here, so the real question is whether it produced CSS.
        // The class attribute is present either way.
        assertEquals(
            "text-red-500 should have compiled to a red colour",
            "\"rgb(239, 68, 68)\"",
            page.awaitTruthy("getComputedStyle(document.getElementById('probe')).color"),
        )
    }

    @Test
    fun reactArtifactsMountAndRunHooks() {
        val jsx = """
            import React, { useState } from 'react';
            export default function App() {
                const [n] = useState(41);
                return <div id="probe">{n + 1}</div>;
            }
        """.trimIndent()
        val page = render(
            ArtifactWebContent.buildHtml(jsx, type = "application/vnd.react", isDarkTheme = false),
        )

        // A mounted component rendering the hook's value proves the whole chain at once: Babel
        // compiled the JSX, the jsx-runtime shim resolved, React and ReactDOM came from the
        // bundled UMD builds, and the generated import map bridged them together.
        assertEquals(
            "the component should have mounted and evaluated its hook",
            "\"42\"",
            page.awaitTruthy("(document.getElementById('probe') || {}).textContent"),
        )
    }

    @Test
    fun reactArtifactsNameAnUnbundledPackageImportedByName() {
        assertNamesTheMissingPackage(
            """
            import React from 'react';
            import { LineChart } from 'recharts';
            export default function App() { return <div>{typeof LineChart}</div>; }
            """.trimIndent(),
            expectedPackage = "recharts",
        )
    }

    @Test
    fun reactArtifactsNameAnUnbundledPackageImportedAsADefault() {
        // Default and named imports fail at different points in module linking, so one passing
        // says nothing about the other.
        assertNamesTheMissingPackage(
            """
            import React from 'react';
            import confetti from 'canvas-confetti';
            export default function App() { return <div>{typeof confetti}</div>; }
            """.trimIndent(),
            expectedPackage = "canvas-confetti",
        )
    }

    private fun assertNamesTheMissingPackage(jsx: String, expectedPackage: String) {
        val page = render(
            ArtifactWebContent.buildHtml(jsx, type = "application/vnd.react", isDarkTheme = false),
        )

        // Read the error element, never document.body.textContent: the body's text includes the
        // inline runner script, whose generated import map already contains the
        // package name. Matching against the body passes on a page that rendered nothing.
        //
        // Both halves of the message are asserted, because any broken page produces *some*
        // error -- requiring the "not bundled" wording is what distinguishes a deliberate,
        // named refusal from an incidental failure somewhere else in the chain.
        val shown = page.awaitTruthy("document.getElementById('error-display').textContent")
        assertTrue("the failure should name the missing package, was: $shown", shown.contains(expectedPackage))
        assertTrue("the failure should say the package is not bundled, was: $shown", shown.contains("not bundled"))
    }

    // ── Harness ───────────────────────────────────────────────────────

    /**
     * Loads [html] into a WebView configured the way the app's hosts configure theirs — in
     * particular with file access off, which is the setting the android_asset scheme depends on.
     */
    private fun render(html: String): Page {
        val base = runBlocking { webAssetBaseUrl() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ref = AtomicReference<WebView>()
        val errors = mutableListOf<String>()
        val loaded = CountDownLatch(1)

        instrumentation.runOnMainSync {
            val webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.allowFileAccess = false
            webView.settings.allowContentAccess = false
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        errors += "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                    }
                    return true
                }
            }
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = loaded.countDown()
            }
            webView.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
            ref.set(webView)
        }

        assertTrue("page never finished loading", loaded.await(PAGE_LOAD_SECONDS, TimeUnit.SECONDS))
        return Page(ref.get(), errors)
    }

    private class Page(private val webView: WebView, private val consoleErrors: List<String>) {

        /** Evaluates [js] once and returns the JSON-encoded result. */
        fun await(js: String): String = evaluate(js)

        /**
         * Polls [js] until it returns something other than `null`, `undefined` or the empty
         * string.
         *
         * Every renderer here finishes its work after `onPageFinished`: mermaid renders
         * asynchronously, Tailwind compiles from a mutation observer, and the React runner is an
         * async IIFE awaiting several dynamic imports. Reading once would race all of them.
         */
        fun awaitTruthy(js: String): String {
            val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
            var last = "null"
            while (System.currentTimeMillis() < deadline) {
                last = evaluate(js)
                if (last !in EMPTY_RESULTS) return last
                Thread.sleep(POLL_INTERVAL_MS)
            }
            return last
        }

        fun assertNoConsoleErrors() {
            assertTrue("console errors: $consoleErrors", consoleErrors.isEmpty())
        }

        private fun evaluate(js: String): String {
            val latch = CountDownLatch(1)
            val result = AtomicReference("null")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView.evaluateJavascript(js) { value ->
                    result.set(value ?: "null")
                    latch.countDown()
                }
            }
            latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return result.get()
        }
    }

    private companion object {
        const val ANDROID_ASSET_PREFIX = "file:///android_asset/"
        const val PAGE_LOAD_SECONDS = 30L
        const val PROBE_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 250L
        val EMPTY_RESULTS = setOf("null", "undefined", "\"undefined\"", "\"\"")
    }
}
