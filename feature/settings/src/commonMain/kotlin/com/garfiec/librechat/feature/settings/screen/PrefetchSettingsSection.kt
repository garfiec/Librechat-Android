package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.prefetch_attachments
import com.garfiec.librechat.feature.settings.resources.prefetch_attachments_desc
import com.garfiec.librechat.feature.settings.resources.prefetch_enabled
import com.garfiec.librechat.feature.settings.resources.prefetch_enabled_desc
import com.garfiec.librechat.feature.settings.resources.prefetch_on_metered
import com.garfiec.librechat.feature.settings.resources.prefetch_on_metered_desc
import org.jetbrains.compose.resources.stringResource

/**
 * Background prefetch controls.
 *
 * The network override is rendered but **disabled** while prefetching is off, rather than hidden.
 * Hiding it would reflow the section on every toggle, and a greyed row is what tells the user the
 * setting exists at all.
 */
@Composable
fun PrefetchSettingsSection(
    prefetchEnabled: Boolean,
    prefetchOnMeteredEnabled: Boolean,
    prefetchAttachmentsEnabled: Boolean,
    prefetchAttachmentsSupported: Boolean,
    onPrefetchEnabledChange: (Boolean) -> Unit,
    onPrefetchOnMeteredChange: (Boolean) -> Unit,
    onPrefetchAttachmentsChange: (Boolean) -> Unit,
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
    }
}
