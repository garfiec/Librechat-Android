package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactButton
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactSegment
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.components.artifact.InlineArtifactStrategy
import com.garfiec.librechat.feature.chat.components.artifact.InlineArtifactView
import com.garfiec.librechat.feature.chat.components.artifact.InlineMarkdownArtifact
import com.garfiec.librechat.feature.chat.components.artifact.InlineSvgArtifact
import com.garfiec.librechat.feature.chat.components.artifact.InlineSvgSurface
import com.garfiec.librechat.feature.chat.components.artifact.LocalAddArtifactToHomeScreen
import com.garfiec.librechat.feature.chat.components.artifact.LocalInlineArtifactPrefs
import com.garfiec.librechat.feature.chat.components.artifact.LocalMermaidRenderCache
import com.garfiec.librechat.feature.chat.components.artifact.LocalOpenArtifact
import com.garfiec.librechat.feature.chat.components.artifact.detectArtifacts
import com.garfiec.librechat.feature.chat.components.artifact.groupArtifactVersions
import com.garfiec.librechat.feature.chat.components.artifact.isCacheableMermaid
import com.garfiec.librechat.feature.chat.components.artifact.mermaidCacheKey
import com.garfiec.librechat.feature.chat.components.artifact.selectInlineArtifactStrategy
import com.garfiec.librechat.feature.chat.components.artifact.shouldRenderInline

// ─── TextContentPart ────────────────────────────────────────────────

@Composable
internal fun TextContentPart(
    text: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
) {
    if (text.isBlank()) return

    val segments = remember(text) { detectArtifacts(text) }
    val hasArtifacts = remember(segments) { segments.any { it is ArtifactSegment.ArtifactReference } }

    if (!hasArtifacts) {
        MarkdownContent(
            text,
            modifier,
            fontSizeMultiplier,
            useKatex,
            searchQuery,
            searchFocusedOccurrence,
            onFocusedOccurrencePosition,
        )
    } else {
        val versionMap = remember(segments) { groupArtifactVersions(segments) }
        val inlinePrefs = LocalInlineArtifactPrefs.current
        val openArtifact = LocalOpenArtifact.current
        val addToHomeScreen = LocalAddArtifactToHomeScreen.current
        // Per-Text-segment occurrence base offsets (artifact content contributes none — see the
        // SearchMatchEnumeration render-order contract). Computed once per (segments, query): the
        // per-segment countMarkdownOccurrences re-parses markdown, so keeping it off recomposition matters.
        val textOffsets = remember(segments, searchQuery) {
            IntArray(segments.size).also { offsets ->
                if (!searchQuery.isNullOrBlank()) {
                    var acc = 0
                    segments.forEachIndexed { i, segment ->
                        offsets[i] = acc
                        if (segment is ArtifactSegment.Text) {
                            acc += countMarkdownOccurrences(segment.text, searchQuery)
                        }
                    }
                }
            }
        }
        Column(modifier = modifier) {
            segments.forEachIndexed { index, segment ->
                when (segment) {
                    is ArtifactSegment.Text -> {
                        MarkdownContent(
                            segment.text,
                            Modifier.fillMaxWidth(),
                            fontSizeMultiplier,
                            useKatex,
                            searchQuery,
                            searchFocusedOccurrence - textOffsets[index],
                            onFocusedOccurrencePosition,
                        )
                    }
                    is ArtifactSegment.ArtifactReference -> {
                        val versions = versionMap[segment.artifact.identifier] ?: listOf(segment.artifact)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (inlinePrefs.shouldRenderInline(segment.artifact.type)) {
                            val type = ArtifactType.from(segment.artifact.type)
                            val cachedSvg = rememberCachedMermaidSvg(segment.artifact.content, type)
                            when (val strategy = selectInlineArtifactStrategy(type, segment.artifact.content, cachedSvg)) {
                                is InlineArtifactStrategy.CachedMermaidSvg -> InlineSvgSurface(
                                    svg = strategy.svg,
                                    onTap = { openArtifact?.invoke(segment.artifact, versions) },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = 4.dp,
                                )
                                InlineArtifactStrategy.NativeMarkdown -> InlineMarkdownArtifact(
                                    artifact = segment.artifact,
                                    onTap = { openArtifact?.invoke(segment.artifact, versions) },
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSizeMultiplier = fontSizeMultiplier,
                                    searchQuery = searchQuery,
                                )
                                InlineArtifactStrategy.IntrinsicSvg -> InlineSvgArtifact(
                                    artifact = segment.artifact,
                                    onTap = { openArtifact?.invoke(segment.artifact, versions) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                InlineArtifactStrategy.WebViewSlot -> InlineArtifactView(
                                    artifact = segment.artifact,
                                    onTap = { openArtifact?.invoke(segment.artifact, versions) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            ArtifactButton(
                                artifact = segment.artifact,
                                onClick = { openArtifact?.invoke(segment.artifact, versions) },
                                versionCount = versions.size,
                                onAddToHomeScreen = addToHomeScreen?.let { add -> { add(segment.artifact) } },
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Reads the [LocalMermaidRenderCache] for the given artifact content and theme,
 * returning the cached SVG when present. Returns `null` for non-mermaid types
 * or non-cacheable mermaid sources so callers don't need to gate themselves.
 */
@Composable
private fun rememberCachedMermaidSvg(content: String, type: ArtifactType): String? {
    if (type != ArtifactType.MERMAID || !isCacheableMermaid(content)) return null
    val cache = LocalMermaidRenderCache.current
    val isDark = isSurfaceDark()
    val key = remember(content, isDark) { mermaidCacheKey(content, isDark) }
    return cache[key]
}
