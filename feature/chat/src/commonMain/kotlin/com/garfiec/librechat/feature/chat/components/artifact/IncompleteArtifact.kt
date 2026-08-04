package com.garfiec.librechat.feature.chat.components.artifact

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.components.CodeBlock
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.artifact_incomplete
import org.jetbrains.compose.resources.stringResource

/**
 * Renders an artifact whose closing `:::` never arrived — a reply truncated mid-artifact, or a live
 * stream still in flight ([Artifact.isComplete]` == false`).
 *
 * Such artifacts show their **source**, deliberately bypassing both the inline-artifact preferences
 * and [selectInlineArtifactStrategy]. Two reasons, and both matter:
 *
 *  - Every inline preference defaults to off, so the normal path would render a collapsed
 *    [ArtifactButton] and take the partial content off screen. Text that renders fine today would
 *    silently disappear behind a tap — the opposite of an improvement.
 *  - Half-written markup handed to a WebView renders a blank box with no recourse. Source is both
 *    safer and more useful: readable, selectable, and searchable.
 *
 * Search matches here **are** counted (unlike complete artifacts, whose content contributes zero) —
 * see the render-order contract in `SearchMatchEnumeration`. That is why this composable takes the
 * focused-occurrence plumbing and forwards it to [CodeBlock].
 */
@Composable
fun IncompleteArtifact(
    artifact: Artifact,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
    streaming: Boolean = false,
) {
    ArtifactCardSurface(onTap = onTap, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = artifact.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.artifact_incomplete),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            CodeBlock(
                code = artifact.content,
                language = artifact.language,
                modifier = Modifier.fillMaxWidth(),
                searchQuery = searchQuery,
                searchFocusedOccurrence = searchFocusedOccurrence,
                onFocusedMatchPosition = onFocusedOccurrencePosition,
                streaming = streaming,
            )
        }
    }
}
