package com.garfiec.librechat.feature.chat.components.artifact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineArtifactStrategyTest {

    private val cacheableMermaid = "flowchart TD\n  A --> B"
    private val nonCacheableMermaid = "sequenceDiagram\n  A->>B: hi"
    private val cachedSvg = "<svg id='cached'/>"

    @Test
    fun `cacheable mermaid with cached svg returns CachedMermaidSvg`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.MERMAID,
            content = cacheableMermaid,
            cachedMermaidSvg = cachedSvg,
        )
        assertTrue(result is InlineArtifactStrategy.CachedMermaidSvg)
        assertEquals(cachedSvg, result.svg)
    }

    @Test
    fun `cacheable mermaid without cached svg falls through to WebViewSlot`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.MERMAID,
            content = cacheableMermaid,
            cachedMermaidSvg = null,
        )
        assertEquals(InlineArtifactStrategy.WebViewSlot, result)
    }

    @Test
    fun `non-cacheable mermaid with cached svg still returns WebViewSlot`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.MERMAID,
            content = nonCacheableMermaid,
            cachedMermaidSvg = cachedSvg,
        )
        assertEquals(InlineArtifactStrategy.WebViewSlot, result)
    }

    @Test
    fun `markdown returns NativeMarkdown`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.MARKDOWN,
            content = "# heading",
            cachedMermaidSvg = null,
        )
        assertEquals(InlineArtifactStrategy.NativeMarkdown, result)
    }

    @Test
    fun `svg returns IntrinsicSvg`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.SVG,
            content = "<svg/>",
            cachedMermaidSvg = null,
        )
        assertEquals(InlineArtifactStrategy.IntrinsicSvg, result)
    }

    @Test
    fun `html returns WebViewSlot`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.HTML,
            content = "<div/>",
            cachedMermaidSvg = null,
        )
        assertEquals(InlineArtifactStrategy.WebViewSlot, result)
    }

    @Test
    fun `react returns WebViewSlot`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.REACT,
            content = "export default () => null",
            cachedMermaidSvg = null,
        )
        assertEquals(InlineArtifactStrategy.WebViewSlot, result)
    }

    @Test
    fun `code returns WebViewSlot`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.CODE,
            content = "<!doctype html>",
            cachedMermaidSvg = null,
        )
        assertEquals(InlineArtifactStrategy.WebViewSlot, result)
    }

    @Test
    fun `plain returns WebViewSlot`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.PLAIN,
            content = "just text",
            cachedMermaidSvg = null,
        )
        assertEquals(InlineArtifactStrategy.WebViewSlot, result)
    }

    @Test
    fun `empty content dispatches without throwing for every type`() {
        // Leaf directives (`::artifact{…}`, no body) make empty content reachable for the first
        // time. The ladder must stay total — in particular isCacheableMermaid("") must not throw
        // and must not claim a cache hit.
        val expected = ArtifactType.entries.associateWith { type ->
            when (type) {
                ArtifactType.MARKDOWN -> InlineArtifactStrategy.NativeMarkdown
                ArtifactType.SVG -> InlineArtifactStrategy.IntrinsicSvg
                else -> InlineArtifactStrategy.WebViewSlot
            }
        }
        val actual = ArtifactType.entries.associateWith { type ->
            selectInlineArtifactStrategy(type = type, content = "", cachedMermaidSvg = null)
        }
        assertEquals(expected, actual)
    }

    @Test
    fun `empty mermaid content does not take the cached-svg path`() {
        val result = selectInlineArtifactStrategy(
            type = ArtifactType.MERMAID,
            content = "",
            cachedMermaidSvg = "<svg/>",
        )
        assertEquals(InlineArtifactStrategy.WebViewSlot, result)
    }
}
