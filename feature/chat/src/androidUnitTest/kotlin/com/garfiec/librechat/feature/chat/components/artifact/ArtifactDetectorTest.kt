package com.garfiec.librechat.feature.chat.components.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactDetectorTest {

    @Test
    fun `detects HTML artifact`() {
        val text = """
:::artifact{identifier="my-page" type="text/html" title="My Page"}
```html
<h1>Hello</h1>
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("my-page", ref.artifact.identifier)
        assertEquals("text/html", ref.artifact.type)
        assertEquals("My Page", ref.artifact.title)
        assertEquals("html", ref.artifact.language)
        assertEquals("<h1>Hello</h1>", ref.artifact.content)
    }

    @Test
    fun `detects SVG artifact`() {
        val text = """
:::artifact{identifier="house-svg" type="image/svg+xml" title="House SVG"}
```svg
<svg viewBox="0 0 100 100"><rect width="50" height="50"/></svg>
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("house-svg", ref.artifact.identifier)
        assertEquals("image/svg+xml", ref.artifact.type)
        assertEquals("<svg viewBox=\"0 0 100 100\"><rect width=\"50\" height=\"50\"/></svg>", ref.artifact.content)
    }

    @Test
    fun `detects React artifact`() {
        val text = """
:::artifact{identifier="react-counter" type="application/vnd.react" title="React Counter"}
```
import { useState } from 'react';

export default function Counter() {
  const [count, setCount] = useState(0);
  return <button onClick={() => setCount(count + 1)}>Count: {count}</button>;
}
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("react-counter", ref.artifact.identifier)
        assertEquals("application/vnd.react", ref.artifact.type)
        assertTrue(ref.artifact.content.contains("useState"))
    }

    @Test
    fun `detects Mermaid artifact`() {
        val text = """
:::artifact{identifier="login-flow" type="application/vnd.mermaid" title="Login Flow"}
```mermaid
graph TD
    A[Start] --> B{Has Account?}
    B -->|Yes| C[Login]
    B -->|No| D[Register]
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("login-flow", ref.artifact.identifier)
        assertEquals("application/vnd.mermaid", ref.artifact.type)
        assertEquals("mermaid", ref.artifact.language)
        assertTrue(ref.artifact.content.contains("graph TD"))
    }

    @Test
    fun `detects Markdown artifact`() {
        val text = """
:::artifact{identifier="report" type="text/markdown" title="Climate Report"}
```markdown
# Climate Change Report

## Introduction
Global temperatures are rising.

- Point 1
- Point 2
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("report", ref.artifact.identifier)
        assertEquals("text/markdown", ref.artifact.type)
        assertTrue(ref.artifact.content.contains("# Climate Change Report"))
    }

    @Test
    fun `detects artifact fenced with 4 backticks`() {
        // LibreChat's server-side artifact prompt defaults to a 4-backtick fence
        // (bumping to 5+ only if the content itself contains a 4-backtick run), so a
        // 3-backtick-only regex misses every artifact real backends actually emit.
        val text = """
:::artifact{identifier="report" type="text/markdown" title="Report"}
````markdown
# Report

## Section
Body text.
````
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("report", ref.artifact.identifier)
        assertEquals("text/markdown", ref.artifact.type)
        assertEquals("markdown", ref.artifact.language)
        assertTrue(ref.artifact.content.contains("# Report"))
    }

    @Test
    fun `4-backtick fence correctly wraps content containing a 3-backtick code block`() {
        // This is the actual scenario the artifact instructions bump the fence length for:
        // a markdown/document artifact whose own content demonstrates a fenced code block.
        val text = """
:::artifact{identifier="guide" type="text/markdown" title="Guide"}
````markdown
# Guide

Run this:

```bash
echo hello
```
````
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("guide", ref.artifact.identifier)
        assertTrue(ref.artifact.content.contains("```bash"))
        assertTrue(ref.artifact.content.contains("echo hello"))
    }

    @Test
    fun `mismatched fence lengths do not match as an artifact`() {
        // A 4-backtick opening fence must be closed with 4 backticks, not 3 — otherwise
        // the embedded 3-backtick block from the previous test would close the artifact early.
        val text = """
:::artifact{identifier="bad" type="text/markdown" title="Bad"}
````markdown
content
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertTrue(segments.none { it is ArtifactSegment.ArtifactReference })
    }

    @Test
    fun `detects plain text artifact`() {
        val text = """
:::artifact{identifier="notes" type="text/plain" title="Notes"}
```
Some plain text content
with multiple lines
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("notes", ref.artifact.identifier)
        assertEquals("text/plain", ref.artifact.type)
    }

    @Test
    fun `detects multiple artifacts in one message`() {
        val text = """
Here's an HTML page:

:::artifact{identifier="page" type="text/html" title="Page"}
```html
<p>Hello</p>
```
:::

And here's a diagram:

:::artifact{identifier="diagram" type="application/vnd.mermaid" title="Diagram"}
```mermaid
graph LR
    A --> B
```
:::

Hope that helps!
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(5, segments.size)
        assertTrue(segments[0] is ArtifactSegment.Text)
        assertTrue(segments[1] is ArtifactSegment.ArtifactReference)
        assertTrue(segments[2] is ArtifactSegment.Text)
        assertTrue(segments[3] is ArtifactSegment.ArtifactReference)
        assertTrue(segments[4] is ArtifactSegment.Text)

        val page = (segments[1] as ArtifactSegment.ArtifactReference).artifact
        assertEquals("page", page.identifier)
        assertEquals("text/html", page.type)

        val diagram = (segments[3] as ArtifactSegment.ArtifactReference).artifact
        assertEquals("diagram", diagram.identifier)
        assertEquals("application/vnd.mermaid", diagram.type)
    }

    @Test
    fun `artifact with surrounding text`() {
        val text = """
Here's what I made:

:::artifact{identifier="test" type="text/html" title="Test"}
```html
<div>test</div>
```
:::

Let me know if you want changes.
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(3, segments.size)
        assertEquals("Here's what I made:", (segments[0] as ArtifactSegment.Text).text)
        assertEquals("test", (segments[1] as ArtifactSegment.ArtifactReference).artifact.identifier)
        assertEquals("Let me know if you want changes.", (segments[2] as ArtifactSegment.Text).text)
    }

    @Test
    fun `version grouping with same identifier`() {
        val text = """
:::artifact{identifier="counter" type="text/html" title="Counter v1"}
```html
<p>Version 1</p>
```
:::

:::artifact{identifier="counter" type="text/html" title="Counter v2" version="2"}
```html
<p>Version 2</p>
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        val versions = groupArtifactVersions(segments)
        assertEquals(1, versions.size)
        assertEquals(2, versions["counter"]?.size)
        assertEquals(1, versions["counter"]?.get(0)?.version)
        assertEquals(2, versions["counter"]?.get(1)?.version)
    }

    @Test
    fun `missing attributes use defaults`() {
        val text = """
:::artifact{type="text/html"}
```
<p>No id or title</p>
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertTrue(ref.artifact.identifier.startsWith("artifact-"))
        assertEquals("Artifact", ref.artifact.title)
        assertEquals(1, ref.artifact.version)
    }

    @Test
    fun `no artifacts returns single text segment`() {
        val text = "This is just regular text with no artifacts."
        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is ArtifactSegment.Text)
        assertEquals(text, (segments[0] as ArtifactSegment.Text).text)
    }

    @Test
    fun `blank text returns empty list`() {
        val segments = detectArtifacts("   ")
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `artifact with text_md type`() {
        val text = """
:::artifact{identifier="doc" type="text/md" title="Doc"}
```
# Hello World
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("text/md", ref.artifact.type)
    }

    @Test
    fun `artifact content preserves internal newlines`() {
        val text = """
:::artifact{identifier="multi" type="text/html" title="Multi-line"}
```html
<div>
  <p>Line 1</p>
  <p>Line 2</p>
  <p>Line 3</p>
</div>
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        val content = (segments[0] as ArtifactSegment.ArtifactReference).artifact.content
        assertTrue(content.contains("<p>Line 1</p>"))
        assertTrue(content.contains("<p>Line 2</p>"))
        assertTrue(content.contains("<p>Line 3</p>"))
        assertEquals(5, content.lines().size)
    }

    @Test
    fun `language hint extracted from backtick line`() {
        val text = """
:::artifact{identifier="code" type="text/html" title="Code"}
```html
<p>test</p>
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        val artifact = (segments[0] as ArtifactSegment.ArtifactReference).artifact
        assertEquals("html", artifact.language)
    }

    @Test
    fun `no language hint when backticks are bare`() {
        val text = """
:::artifact{identifier="bare" type="application/vnd.react" title="React"}
```
export default () => <div>Hello</div>
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        val artifact = (segments[0] as ArtifactSegment.ArtifactReference).artifact
        assertEquals(null, artifact.language)
    }

    @Test
    fun `version grouping sorts by version`() {
        val segments = listOf(
            ArtifactSegment.ArtifactReference(
                Artifact("a", "text/html", "A v3", null, "v3", version = 3),
            ),
            ArtifactSegment.ArtifactReference(
                Artifact("a", "text/html", "A v1", null, "v1", version = 1),
            ),
            ArtifactSegment.ArtifactReference(
                Artifact("a", "text/html", "A v2", null, "v2", version = 2),
            ),
        )

        val grouped = groupArtifactVersions(segments)
        assertEquals(listOf(1, 2, 3), grouped["a"]?.map { it.version })
    }

    @Test
    fun `code-html type artifact detected`() {
        val text = """
:::artifact{identifier="app" type="application/vnd.code-html" title="App"}
```html
<!DOCTYPE html><html><body><p>Code HTML</p></body></html>
```
:::
        """.trimIndent()

        val segments = detectArtifacts(text)
        assertEquals(1, segments.size)
        val ref = segments[0] as ArtifactSegment.ArtifactReference
        assertEquals("application/vnd.code-html", ref.artifact.type)
    }
}
