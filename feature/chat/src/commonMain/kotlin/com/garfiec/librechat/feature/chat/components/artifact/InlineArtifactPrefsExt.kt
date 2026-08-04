package com.garfiec.librechat.feature.chat.components.artifact

import com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs

/**
 * Maps an artifact's MIME type to its [InlineArtifactPrefs] field via
 * [ArtifactType]. Plain text and unrecognised code always render as the
 * tap-to-open button (no toggle).
 */
fun InlineArtifactPrefs.shouldRenderInline(type: String): Boolean =
    when (ArtifactType.from(type)) {
        ArtifactType.MERMAID -> mermaid
        ArtifactType.SVG -> svg
        ArtifactType.HTML -> html
        ArtifactType.REACT -> react
        ArtifactType.MARKDOWN -> markdown
        ArtifactType.PLAIN, ArtifactType.CODE -> false
    }

/**
 * [shouldRenderInline], additionally gated off while the message is still streaming — an inline
 * preview would reload its WebView on every delta. Full rationale: feature/chat/CLAUDE.md, Artifacts.
 */
fun shouldRenderInlineArtifact(prefs: InlineArtifactPrefs, type: String, streaming: Boolean): Boolean =
    !streaming && prefs.shouldRenderInline(type)
