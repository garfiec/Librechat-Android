package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Monotonic id of the current in-conversation-search focus request (see `SearchFocusRequest`),
 * provided by `MessageList` only around the current-match bubble (0L elsewhere, so unrelated
 * bubbles never recompose on navigation). A collapsible block that holds the focused match keys
 * its auto-expand on this so re-navigating to the *same* occurrence — even after the user manually
 * collapsed it — re-opens the card; the plain occurrence index alone wouldn't change on a re-focus.
 */
internal val LocalSearchFocusNonce = compositionLocalOf { 0L }

// ─── CollapsibleDisclosureCard ──────────────────────────────────────

/**
 * Shared collapsible card shell used by [ThinkingContentPart] and
 * [SummaryContentPart]: a rounded surface with a tap-to-toggle header
 * (leading icon + title + expand/collapse chevron) and an animated body.
 * Callers supply the header chrome and the expandable [body]; the card owns
 * the expand/collapse state and selects the matching toggle description.
 */
@Composable
private fun CollapsibleDisclosureCard(
    leadingIcon: ImageVector,
    leadingIconContentDescription: String,
    title: String,
    expandActionContentDescription: StringResource,
    collapseActionContentDescription: StringResource,
    modifier: Modifier = Modifier,
    // Flips the card open when it becomes true (find-in-page expands folded regions);
    // the user can still collapse it manually afterwards.
    autoExpand: Boolean = false,
    // Re-arms the auto-expand each time it changes, so navigating to a *different* focused match
    // inside the same block re-opens it even if the user manually collapsed it after the last jump.
    autoExpandKey: Any? = null,
    body: @Composable () -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(autoExpand, autoExpandKey) {
        if (autoExpand) isExpanded = true
    }
    val toggleContentDescription =
        stringResource(if (isExpanded) collapseActionContentDescription else expandActionContentDescription)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { isExpanded = !isExpanded }
                .padding(12.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = toggleContentDescription
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                leadingIcon,
                leadingIconContentDescription,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                stringResource(if (isExpanded) Res.string.cd_collapse else Res.string.cd_expand),
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                body()
            }
        }
    }
}

// ─── ThinkingContentPart ────────────────────────────────────────────

@Composable
internal fun ThinkingContentPart(
    thinkingText: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
    searchQuery: String? = null,
    searchFocusedOccurrence: Int = -1,
    onFocusedOccurrencePosition: ((LayoutCoordinates, Rect) -> Unit)? = null,
) {
    // The focused occurrence index is already rebased to this part; when it falls
    // inside the thinking text, pop the card open so the match can be scrolled to.
    val containsFocusedMatch = remember(thinkingText, searchQuery, searchFocusedOccurrence) {
        searchFocusedOccurrence >= 0 && !searchQuery.isNullOrBlank() &&
            searchFocusedOccurrence < countMarkdownOccurrences(thinkingText, searchQuery)
    }
    CollapsibleDisclosureCard(
        leadingIcon = Icons.Default.Psychology,
        leadingIconContentDescription = stringResource(Res.string.cd_thinking_indicator),
        title = stringResource(Res.string.label_thinking),
        expandActionContentDescription = Res.string.cd_expand_thinking,
        collapseActionContentDescription = Res.string.cd_collapse_thinking,
        modifier = modifier,
        autoExpand = containsFocusedMatch,
        // Re-arm on every navigation: LocalSearchFocusNonce changes per focus request, so both
        // moving to a different occurrence in this block AND returning to the same one (after a
        // manual collapse) re-open the card — the rebased occurrence index alone wouldn't change
        // on a re-focus of the same match.
        autoExpandKey = LocalSearchFocusNonce.current,
    ) {
        MarkdownContent(
            thinkingText,
            fontSizeMultiplier = fontSizeMultiplier,
            useKatex = useKatex,
            searchQuery = searchQuery,
            searchFocusedOccurrence = searchFocusedOccurrence,
            onFocusedOccurrencePosition = onFocusedOccurrencePosition,
        )
    }
}

// ─── SummaryContentPart ─────────────────────────────────────────────

/**
 * Collapsed "Summarized earlier messages" card rendered when the server
 * emits a SUMMARY content part. Content-compaction is triggered by long
 * agent chats (v0.8.5+); tap to expand and read the summary text.
 */
@Composable
internal fun SummaryContentPart(
    summaryText: String,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1.0f,
    useKatex: Boolean = false,
) {
    if (summaryText.isBlank()) return

    CollapsibleDisclosureCard(
        leadingIcon = Icons.Default.Notes,
        leadingIconContentDescription = stringResource(Res.string.cd_summary_indicator),
        title = stringResource(Res.string.label_summary),
        expandActionContentDescription = Res.string.cd_expand_summary,
        collapseActionContentDescription = Res.string.cd_collapse_summary,
        modifier = modifier,
    ) {
        MarkdownContent(
            summaryText,
            fontSizeMultiplier = fontSizeMultiplier,
            useKatex = useKatex,
        )
    }
}
