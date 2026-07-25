package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.data.datastore.DuringRunAction
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_during_run_options
import com.garfiec.librechat.feature.chat.resources.during_run_always_queue
import com.garfiec.librechat.feature.chat.resources.during_run_always_steer
import com.garfiec.librechat.feature.chat.resources.during_run_queue
import com.garfiec.librechat.feature.chat.resources.during_run_steer
import org.jetbrains.compose.resources.stringResource

/**
 * The caret next to the composer's send button while a reply is generating: picks whether THIS
 * message steers the running turn or waits in the queue, and flips the standing default.
 *
 * Only rendered when steering is actually reachable. With one option there is nothing to choose
 * between, so the send button stands alone and mobile's long-standing mid-run queueing is left
 * exactly as it was.
 *
 * A visible caret rather than a long-press on the send button: during a run the send button is
 * already carrying several meanings (stop, queue, steer, update, awaiting-upload), and hiding a
 * mode switch behind a gesture on top of that would make steering undiscoverable.
 */
@Composable
fun DuringRunSendMenu(
    defaultAction: DuringRunAction,
    onSteerOnce: () -> Unit,
    onQueueOnce: () -> Unit,
    onSetDefault: (DuringRunAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.ExpandLess,
                contentDescription = stringResource(Res.string.cd_during_run_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.during_run_steer)) },
                leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                onClick = {
                    expanded = false
                    onSteerOnce()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.during_run_queue)) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onQueueOnce()
                },
            )
            HorizontalDivider()
            // Offers the OPPOSITE of the current default — the switch you would make, not a
            // restatement of the setting you are already on.
            val next = if (defaultAction == DuringRunAction.STEER) {
                DuringRunAction.QUEUE
            } else {
                DuringRunAction.STEER
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (next == DuringRunAction.STEER) {
                                Res.string.during_run_always_steer
                            } else {
                                Res.string.during_run_always_queue
                            },
                        ),
                    )
                },
                onClick = {
                    expanded = false
                    onSetDefault(next)
                },
            )
        }
    }
}
