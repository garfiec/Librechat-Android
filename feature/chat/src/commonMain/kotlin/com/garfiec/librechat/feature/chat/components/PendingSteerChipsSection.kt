package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_cancel_steer
import com.garfiec.librechat.feature.chat.viewmodel.PendingSteerChip
import com.garfiec.librechat.feature.chat.viewmodel.SteerChipStatus
import org.jetbrains.compose.resources.stringResource

/**
 * Steers waiting to go into the reply that is currently streaming (v0.8.8), pinned above the
 * composer.
 *
 * Sits directly above [QueuedMessagesSection] and is deliberately styled apart from it: a queued
 * message becomes the *next* turn, a steer changes the one being written right now. The bolt
 * marks the difference, the primary tint gives it the more-urgent read, and there is no drag
 * handle — the run injects steers in the order they were accepted, and the client cannot reorder
 * what the server has already queued.
 *
 * A row disappears on its own the moment the run injects it (`on_steer_applied`); the × withdraws
 * it before that happens.
 */
@Composable
fun PendingSteerChipsSection(
    pendingSteers: List<PendingSteerChip>,
    onCancel: (steerId: String) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeMultiplier: Float = 1f,
) {
    if (pendingSteers.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pendingSteers.forEach { chip ->
            PendingSteerRow(
                chip = chip,
                fontSizeMultiplier = fontSizeMultiplier,
                onCancel = { onCancel(chip.steerId) },
            )
        }
    }
}

@Composable
private fun PendingSteerRow(
    chip: PendingSteerChip,
    fontSizeMultiplier: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            // The POST is still out, so the row shows a spinner instead of the bolt: until the
            // server accepts it there is nothing to cancel server-side and nothing to inject.
            if (chip.status == SteerChipStatus.SENDING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))

            Text(
                text = chip.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 14.sp * fontSizeMultiplier,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
            )

            IconButton(onClick = onCancel, modifier = Modifier.alpha(0.8f)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.cd_cancel_steer),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
