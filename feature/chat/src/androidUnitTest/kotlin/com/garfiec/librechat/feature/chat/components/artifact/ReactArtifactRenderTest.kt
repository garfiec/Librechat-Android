package com.garfiec.librechat.feature.chat.components.artifact

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the React-artifact renderer in [ArtifactWebContent.buildHtml].
 *
 * React artifacts are compiled in-browser and loaded as a real ES module; the artifact's
 * own `import`/`export` statements run verbatim against an import map the runner builds
 * over the bundled React/ReactDOM UMD globals. The load-bearing properties: (1) every
 * script the page executes ships with the app, (2) React resolves to a single instance,
 * (3) a package that is not bundled fails by name rather than reaching the network, and
 * (4) the source round-trips through the embedding untouched.
 */
class ReactArtifactRenderTest {

    private val reactType = "application/vnd.react"

    private fun build(content: String): String =
        ArtifactWebContent.buildHtml(content, reactType, isDarkTheme = false)

    /** Recovers the artifact source the runner will execute, from the base64 blob. */
    private fun embeddedSource(html: String): String {
        val b64 = Regex("""atob\('([^']*)'\)""").find(html)!!.groupValues[1]
        return String(Base64.getDecoder().decode(b64))
    }

    @Test
    fun `every script the page loads is bundled with the app`() {
        val html = build("export default function App() { return <div/>; }")
        assertTrue(html.contains("""<script src="react/react.production.min.js">"""))
        assertTrue(html.contains("""<script src="react-dom/react-dom.production.min.js">"""))
        assertTrue(html.contains("""<script src="babel/babel.min.js">"""))
        assertTrue(html.contains("""<script src="tailwind/tailwind.min.js">"""))
    }

    @Test
    fun `the page may not reach any remote origin`() {
        // Not a style preference: a remote <script src> here is what F-Droid's inclusion
        // policy forbids, and it is invisible in a passing render because the CDN answers.
        val html = build(
            """
            import { LineChart } from 'recharts';
            export default function App() { return <LineChart/>; }
            """.trimIndent(),
        )
        val csp = Regex("""content="(default-src[^"]*)"""").find(html)!!.groupValues[1]
        val scriptSrc = csp.split(";").map { it.trim() }.first { it.startsWith("script-src") }
        assertFalse(scriptSrc.contains("http"), "CSP still allows remote script: $scriptSrc")
        assertFalse(html.contains("https://cdn."), "page references a CDN")
        assertFalse(html.contains("esm.sh"), "page references the ESM CDN")
        assertFalse(html.contains("unpkg.com"), "page references unpkg")
    }

    @Test
    fun `react resolves to the single bundled instance`() {
        val html = build("export default function App() { return <div/>; }")
        // Both come off the same window.React / window.ReactDOM the UMD scripts defined,
        // so there is no way for a second copy to exist and break hooks.
        assertTrue(html.contains("'react': globalModule('React', window.React)"))
        assertTrue(html.contains("'react-dom': globalModule('ReactDOM', window.ReactDOM)"))
        assertTrue(html.contains("'react-dom/client': moduleUrl(DOM_CLIENT)"))
        assertTrue(html.contains("'react/jsx-runtime': moduleUrl(JSX_RUNTIME)"))
    }

    @Test
    fun `an unbundled package fails by name instead of loading from a cdn`() {
        val html = build(
            """
            import { Plus } from 'lucide-react';
            import { LineChart } from 'recharts';
            import * as Dialog from '@radix-ui/react-dialog';
            export default function App() { return <Plus/>; }
            """.trimIndent(),
        )
        // Each specifier carries the names it is imported under. Those names are what the stub
        // declares as exports, and declaring them is what lets the explanation below run at all:
        // named imports are resolved while the module graph links, before any code executes, so
        // a stub exporting nothing is rejected with "does not provide an export named 'X'" and
        // the package is never named. A namespace import binds to whatever exists, so
        // @radix-ui/react-dialog correctly needs none.
        assertTrue(
            html.contains(
                """[["lucide-react",["Plus"]],["recharts",["LineChart"]],""" +
                    """["@radix-ui/react-dialog",[]]]""",
            ),
            "unbundled specifiers are not routed to the naming stub with their imported names",
        )
        assertTrue(html.contains("an npm package that is not "), "stub carries no explanation")
    }

    @Test
    fun `relative and url imports are left for the browser to resolve`() {
        val html = build(
            """
            import { helper } from './utils';
            import data from 'https://example.com/data.js';
            import './styles.css';
            export default function App() { return <div/>; }
            """.trimIndent(),
        )
        val missing = Regex("""\[([^\]]*)\]\.forEach""").find(html)!!.groupValues[1]
        assertFalse(missing.contains("./utils"), "relative import should not be mapped")
        assertFalse(missing.contains("example.com"), "url import should not be mapped")
        assertFalse(missing.contains("./styles.css"), "relative css should not be mapped")
    }

    @Test
    fun `a specifier that is not a package name is dropped rather than embedded`() {
        // The specifier list is interpolated into a script, and its contents come from
        // model output. Anything outside the npm name charset never reaches the page.
        val html = build(
            """
            import x from 'evil'/*"];window.stolen=1;//*/;
            export default function App() { return <div/>; }
            """.trimIndent(),
        )
        val missing = Regex("""(\[.*\])\.forEach""").find(html)!!.groupValues[1]
        assertFalse(missing.contains("window.stolen"), "injected script escaped the specifier list")
    }

    @Test
    fun `artifact source round-trips through the embedding verbatim`() {
        // Includes characters that would break naive string embedding.
        val source = """
            import { useState } from 'react';
            export default function App() {
              const html = `<script>alert(1)</script>`;
              const t = `total: ${'$'}{1 + 2}`;
              return <div>{html}{t}</div>;
            }
        """.trimIndent()
        assertEquals(source, embeddedSource(build(source)), "source must survive embedding unchanged")
    }

    @Test
    fun `source is not rewritten - imports and exports are preserved`() {
        // The whole point of the module approach: no regex surgery on the source.
        val source = "import { useState } from 'react';\nexport default function App() { return <div/>; }"
        val recovered = embeddedSource(build(source))
        assertTrue(recovered.contains("import { useState } from 'react';"), "import was altered")
        assertTrue(recovered.contains("export default function App()"), "export was altered")
    }

    @Test
    fun `runner compiles jsx and loads the artifact as a module`() {
        val html = build("export default function App() { return <div/>; }")
        assertTrue(html.contains("Babel.transform("), "jsx is not compiled")
        assertTrue(html.contains("runtime: 'automatic'"), "automatic runtime lets JSX work without importing React")
        assertTrue(html.contains("createRoot(root).render"), "component is not mounted")
        // The import map has to be in the document before the first dynamic import, which
        // is the whole reason the runner is a classic script wrapping an async IIFE.
        val mapAt = html.indexOf("mapTag.type = 'importmap'")
        val importAt = html.indexOf("await import('react')")
        assertTrue(mapAt in 1 until importAt, "import map is injected after the first import")
    }
}
