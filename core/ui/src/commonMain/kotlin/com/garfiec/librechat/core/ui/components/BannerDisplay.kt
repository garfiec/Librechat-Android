package com.garfiec.librechat.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Banner
import com.garfiec.librechat.core.ui.resources.Res
import com.garfiec.librechat.core.ui.resources.banner_dismiss
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the server banner, if there is one to show.
 *
 * The server sends at most one banner and filters it by type and display window itself, so there
 * is no variant to style for: every banner is informational. A `persistable` banner cannot be
 * dismissed, matching the web client — the dismiss control is hidden rather than inert.
 */
@Composable
fun BannerDisplay(
    banner: Banner?,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bannerId = banner?.bannerId
    // Null when `persistable` marks the banner as one the user may not dismiss.
    val dismissId = bannerId?.takeIf { banner.persistable != true }
    // Nullability is decided here rather than by the callers so this composable stays in the tree
    // across the transition: hoisting the check out of it adds and removes the AnimatedVisibility
    // instead of toggling it, and the enter/exit below then never run. A dismissal reaches this the
    // same way — the holder nulls the banner — which is what animates it out.
    //
    // A banner needs an id even when it can't be dismissed — without one nothing can ever remove
    // it, so it would stay pinned for the whole process. A blank message would render a card
    // carrying nothing but the icon.
    val visible = banner != null && bannerId != null && !banner.message.isNullOrBlank()

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
    ) {
        BannerCard(
            message = banner?.message.orEmpty(),
            dismissId = dismissId,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun BannerCard(
    message: String,
    dismissId: String?,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
                end = if (dismissId != null) 4.dp else 16.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                // Decorative: the message carries the content.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (dismissId != null) {
                IconButton(onClick = { onDismiss(dismissId) }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.banner_dismiss),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}
