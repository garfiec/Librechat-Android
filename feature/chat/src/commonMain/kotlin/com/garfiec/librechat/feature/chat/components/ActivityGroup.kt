package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.ContentGroup
import org.jetbrains.compose.resources.stringResource

/**
 * True for the message whose reply just finished streaming, provided by `MessageList`.
 *
 * Live tool calls render as separate expanded cards outside the message; at Final those vanish and
 * the same calls re-render inside it. Collapsing them in that same frame drops the reply's height
 * by the whole stack at once, which reads as content disappearing rather than as a fold. The
 * groups stay open for this render and collapse on a later load instead.
 */
internal val LocalSuppressGroupAutoCollapse = compositionLocalOf { false }

/**
 * A reasoning + tool-call block under one collapsible header.
 *
 * The header is the model's own one-line summary of the batch when the server generated one, and
 * the generic "Used N tools" otherwise — the generated line only wins once it exists, so a block
 * whose label never filled renders exactly as it did before the feature.
 *
 * Deliberately not a [CollapsibleDisclosureCard]: this wraps tool cards, which are themselves
 * surfaces, and nesting one card inside another reads as a rendering bug. It reuses that card's
 * auto-expand *mechanism* rather than its chrome.
 */
@Composable
internal fun ActivityGroup(
    group: ContentGroup.Activity,
    stateKey: String,
    modifier: Modifier = Modifier,
    // Pops the block open when the in-conversation search focuses a match inside it; without this
    // a collapsed group swallows a match the user just navigated to.
    autoExpand: Boolean = false,
    autoExpandKey: Any? = null,
    body: @Composable () -> Unit,
) {
    // Saveable and keyed on the group: this is a LazyColumn item, so scrolling the message out of
    // the viewport disposes plain `remember` state and silently re-collapses what the user opened.
    val suppressAutoCollapse = LocalSuppressGroupAutoCollapse.current
    var isExpanded by rememberSaveable(key = "activity:$stateKey") {
        mutableStateOf(!group.collapsedByDefault || suppressAutoCollapse)
    }
    var userOverride by rememberSaveable(key = "activity-override:$stateKey") { mutableStateOf(false) }
    // Latch: the auto-collapse decision is taken at most ONCE per group. Re-deciding would let a
    // block shut under someone reading it the moment its label settles.
    var autoCollapsed by rememberSaveable(key = "activity-latched:$stateKey") { mutableStateOf(false) }

    LaunchedEffect(group.collapsedByDefault, suppressAutoCollapse) {
        if (!group.collapsedByDefault || userOverride || autoCollapsed) return@LaunchedEffect
        // Latch the decision including when the answer is "no". A suppressed group left unlatched
        // would simply fold later: the flag moves on to the next turn's reply, suppression flips
        // back off underneath a message the user has settled into reading, and the height drop
        // arrives anyway — just further from the cause that explains it.
        autoCollapsed = true
        if (!suppressAutoCollapse) isExpanded = false
    }
    LaunchedEffect(autoExpand, autoExpandKey) {
        if (autoExpand) isExpanded = true
    }

    val headerLabel = group.labelText.ifEmpty {
        stringResource(Res.string.activity_used_n_tools, group.toolCount)
    }
    // A state description, not a content description: `clickable` makes this Row a merging
    // semantics node, so a contentDescription on top of the merged label announces the label and
    // then the label again.
    val expansionState =
        stringResource(if (isExpanded) Res.string.state_expanded else Res.string.state_collapsed)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .heightIn(min = 40.dp)
                .clickable {
                    userOverride = true
                    isExpanded = !isExpanded
                }
                .padding(vertical = 4.dp)
                .semantics {
                    role = Role.Button
                    stateDescription = expansionState
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Build,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            val labelColor = if (group.failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            if (group.labelText.isEmpty()) {
                // "Used N tools" is our own fallback — chrome, kept out of "Select all". The Box
                // carries the weight that the DisableSelection lambda cannot (it is not RowScope).
                Box(modifier = Modifier.weight(1f)) {
                    DisableSelection { ActivityHeaderLabel(headerLabel, labelColor) }
                }
            } else {
                // The server's own label is message text — SearchMatchEnumeration counts it, so a
                // phrase found by search has to be copyable too.
                ActivityHeaderLabel(headerLabel, labelColor, Modifier.weight(1f))
            }
            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // AnimatedVisibility, not animateContentSize: the latter animates on every token while a
        // child grows and re-runs from zero height each time the item re-enters the viewport.
        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                body()
            }
        }
    }
}

@Composable
private fun ActivityHeaderLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier,
    )
}

/**
 * A label whose own block was filtered out of the render. Upstream keeps it as a bare line rather
 * than dropping it — it is still the only description of what happened at that point.
 *
 * Selectable: this is the server's text, and search already treats it as message content.
 */
@Composable
internal fun OrphanActivityLabel(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}
