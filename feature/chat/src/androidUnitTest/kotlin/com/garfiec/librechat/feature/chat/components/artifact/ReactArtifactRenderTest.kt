package com.garfiec.librechat.feature.chat.components.artifact

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the React-artifact renderer in [ArtifactWebContent.buildHtml].
 *
 * React artifacts are compiled in-browser and loaded as a real ES module; the
 * artifact's own `import`/`export` statements run verbatim against a generated
 * import map that points every bare specifier at an ESM CDN. The load-bearing
 * properties: (1) React resolves to a single pinned instance, (2) ANY npm
 * package the model imports gets an import-map entry with no per-library code,
 * and (3) the source round-trips through the embedding untouched.
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
    fun `import map pins react and react-dom to a single esm instance`() {
        val html = build("export default function App() { return <div/>; }")
        assertTrue(html.contains("\"react\": \"https://esm.sh/react@18.3.1\""), "react not pinned")
        assertTrue(html.contains("\"react/jsx-runtime\": \"https://esm.sh/react@18.3.1/jsx-runtime\""))
        assertTrue(
            html.contains("\"react-dom/client\": \"https://esm.sh/react-dom@18.3.1/client?external=react\""),
            "react-dom/client must externalize react to avoid a duplicate React",
        )
    }

    @Test
    fun `arbitrary libraries resolve generically via the esm cdn`() {
        // Three unrelated libraries, none special-cased — proves general-purpose.
        val html = build(
            """
            import { Plus } from 'lucide-react';
            import { LineChart } from 'recharts';
            import * as Dialog from '@radix-ui/react-dialog';
            export default function App() { return <Plus/>; }
            """.trimIndent(),
        )
        assertTrue(html.contains("\"lucide-react\": \"https://esm.sh/lucide-react?external=react,react-dom\""))
        assertTrue(html.contains("\"recharts\": \"https://esm.sh/recharts?external=react,react-dom\""))
        assertTrue(
            html.contains("\"@radix-ui/react-dialog\": \"https://esm.sh/@radix-ui/react-dialog?external=react,react-dom\""),
        )
    }

    @Test
    fun `relative and url imports are left out of the import map`() {
        val html = build(
            """
            import { helper } from './utils';
            import data from 'https://example.com/data.js';
            import './styles.css';
            export default function App() { return <div/>; }
            """.trimIndent(),
        )
        assertFalse(html.contains("esm.sh/./utils"), "relative import should not be mapped")
        assertFalse(html.contains("esm.sh/https://"), "url import should not be mapped")
        assertFalse(html.contains("\"./styles.css\""))
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
        assertTrue(html.contains("""<script type="module">"""), "module runner missing")
        assertTrue(html.contains("Babel.transform("), "jsx is not compiled")
        assertTrue(html.contains("runtime: 'automatic'"), "automatic runtime lets JSX work without importing React")
        assertTrue(html.contains("createRoot(root).render"), "component is not mounted")
    }
}
