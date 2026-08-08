package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.data.prefetch.PrefetchDepth
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.prefetch_activity
import com.garfiec.librechat.feature.settings.resources.prefetch_attachments
import com.garfiec.librechat.feature.settings.resources.prefetch_attachments_desc
import com.garfiec.librechat.feature.settings.resources.prefetch_depth
import com.garfiec.librechat.feature.settings.resources.prefetch_depth_desc
import com.garfiec.librechat.feature.settings.resources.prefetch_depth_value
import com.garfiec.librechat.feature.settings.resources.prefetch_enabled
import com.garfiec.librechat.feature.settings.resources.prefetch_enabled_desc
import com.garfiec.librechat.feature.settings.resources.prefetch_on_metered
import com.garfiec.librechat.feature.settings.resources.prefetch_on_metered_desc
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_last_run
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_last_run_never
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_status
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_warmed
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_warmed_value
import com.garfiec.librechat.feature.settings.state.PrefetchDisplayStatus
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Background prefetch controls, with a compact status summary underneath.
 *
 * The network override is rendered but **disabled** while prefetching is off, rather than hidden.
 * Hiding it would reflow the section on every toggle, and a greyed row is what tells the user the
 * setting exists at all. The summary follows the same rule for the same reason.
 */
@Composable
fun PrefetchSettingsSection(
    prefetchEnabled: Boolean,
    prefetchOnMeteredEnabled: Boolean,
    prefetchAttachmentsEnabled: Boolean,
    prefetchAttachmentsSupported: Boolean,
    prefetchDepth: Int,
    status: PrefetchDisplayStatus,
    warmedCount: Int,
    eligibleCount: Int,
    lastRunLabel: String?,
    onPrefetchEnabledChange: (Boolean) -> Unit,
    onPrefetchOnMeteredChange: (Boolean) -> Unit,
    onPrefetchAttachmentsChange: (Boolean) -> Unit,
    onPrefetchDepthChange: (Int) -> Unit,
    onActivityClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToggleRow(
            title = stringResource(Res.string.prefetch_enabled),
            description = stringResource(Res.string.prefetch_enabled_desc),
            checked = prefetchEnabled,
            onChange = onPrefetchEnabledChange,
        )
        // Hidden, not disabled, where there is no image cache to warm: a greyed row invites the user
        // to turn prefetching on to reach a switch that would still do nothing.
        if (prefetchAttachmentsSupported) {
            ToggleRow(
                title = stringResource(Res.string.prefetch_attachments),
                description = stringResource(Res.string.prefetch_attachments_desc),
                checked = prefetchAttachmentsEnabled,
                onChange = onPrefetchAttachmentsChange,
                enabled = prefetchEnabled,
            )
        }
        ToggleRow(
            title = stringResource(Res.string.prefetch_on_metered),
            description = stringResource(Res.string.prefetch_on_metered_desc),
            checked = prefetchOnMeteredEnabled,
            onChange = onPrefetchOnMeteredChange,
            enabled = prefetchEnabled,
        )

        DepthSlider(
            depth = prefetchDepth,
            onChange = onPrefetchDepthChange,
            enabled = prefetchEnabled,
        )

        HorizontalDivider()

        SummaryRow(
            label = stringResource(Res.string.prefetch_summary_status),
            value = status.label(),
            enabled = prefetchEnabled,
        )
        SummaryRow(
            label = stringResource(Res.string.prefetch_summary_last_run),
            value = lastRunLabel ?: stringResource(Res.string.prefetch_summary_last_run_never),
            enabled = prefetchEnabled,
        )
        SummaryRow(
            label = stringResource(Res.string.prefetch_summary_warmed),
            value = stringResource(Res.string.prefetch_summary_warmed_value, warmedCount, eligibleCount),
            enabled = prefetchEnabled,
        )

        OutlinedButton(
            onClick = onActivityClick,
            enabled = prefetchEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.prefetch_activity))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * How many recent conversations to keep warm.
 *
 * The position is held locally during the gesture and committed on release: writing on every value
 * change puts dozens of writes per drag through the preference store, and the status readout
 * re-subscribes its queries on each one.
 */
@Composable
private fun DepthSlider(depth: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    var pending by remember { mutableStateOf<Int?>(null) }
    val shown = pending ?: depth
    val alpha = if (enabled) 1f else DISABLED_ALPHA

    // Keyed on `pending` as well as `depth`: a commit equal to the stored depth writes an identical
    // value that the flow conflates, so `depth` never changes and waiting on it alone would strand
    // the local position with nothing able to clear it.
    LaunchedEffect(depth, pending) {
        if (pending == depth) pending = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.prefetch_depth),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                text = stringResource(Res.string.prefetch_depth_value, shown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Text(
            text = stringResource(Res.string.prefetch_depth_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
        Slider(
            value = shown.toFloat(),
            onValueChange = { pending = PrefetchDepth.snap(it.roundToInt()) },
            // Reads the state, not a composition-captured copy: Material3 can end a gesture in the
            // same input batch as its last value change, so a captured value is one step stale.
            onValueChangeFinished = { pending?.let(onChange) },
            valueRange = PrefetchDepth.MIN.toFloat()..PrefetchDepth.MAX.toFloat(),
            steps = PrefetchDepth.STEPS_BETWEEN_ENDS,
            enabled = enabled,
        )
    }
}

/** Matches the toggles above, which grey out at the same alpha when prefetching is off. */
private const val DISABLED_ALPHA = 0.38f

@Composable
private fun SummaryRow(label: String, value: String, enabled: Boolean) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}
