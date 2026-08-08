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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.prefetch_activity
import com.garfiec.librechat.feature.settings.resources.prefetch_attachments
import com.garfiec.librechat.feature.settings.resources.prefetch_attachments_desc
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
    status: PrefetchDisplayStatus,
    warmedCount: Int,
    eligibleCount: Int,
    lastRunLabel: String?,
    onPrefetchEnabledChange: (Boolean) -> Unit,
    onPrefetchOnMeteredChange: (Boolean) -> Unit,
    onPrefetchAttachmentsChange: (Boolean) -> Unit,
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
