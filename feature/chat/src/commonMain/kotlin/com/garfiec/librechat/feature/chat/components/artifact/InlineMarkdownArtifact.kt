package com.garfiec.librechat.feature.chat.components.artifact

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.components.MarkdownContent

/**
 * Native inline rendering for `text/markdown` artifacts. Bypasses the WebView
 * path used by other artifact types — the chat's own `MarkdownContent` already
 * supports CommonMark, GFM tables, syntax-highlighted code blocks, and native
 * LaTeX, so we reuse it. This sidesteps async height measurement entirely:
 * Compose knows the layout at measure time, so `LazyColumn` does not scroll-
 * jump when these artifacts re-enter the viewport.
 *
 * Search matches inside artifact content are highlighted but NOT navigable
 * (no focused occurrence / position reporting): whether an artifact renders
 * inline depends on a UI preference the search enumeration cannot see, so
 * artifact content is excluded from occurrence counting (SearchMatchEnumeration).
 */
@Composable
fun InlineMarkdownArtifact(
    artifact: Artifact,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    searchQuery: String? = null,
) {
    ArtifactCardSurface(onTap = onTap, modifier = modifier) {
        MarkdownContent(
            text = artifact.content,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            fontSizeMultiplier = fontSizeMultiplier,
            useKatex = false,
            searchQuery = searchQuery,
            // Synchronous parsing: the m3 Markdown composable parses async by
            // default, so each text segment's height arrives over multiple
            // frames and the LazyColumn scroll-jumps when the artifact item
            // recycles back into view. The artifact content is already fully
            // streamed, so blocking-parse is the right trade-off here.
            immediate = true,
        )
    }
}
