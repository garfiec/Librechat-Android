package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.request.ForkOption
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import org.jetbrains.compose.resources.stringResource

enum class ForkMode(val apiValue: String) {
    DIRECT_PATH(ForkOption.DIRECT_PATH),
    INCLUDE_BRANCHES(ForkOption.INCLUDE_BRANCHES),
    TARGET_LEVEL(ForkOption.TARGET_LEVEL),
    ;

    companion object {
        fun fromApiValue(value: String): ForkMode = entries.firstOrNull { it.apiValue == value } ?: TARGET_LEVEL
    }
}

@Composable
internal fun forkModeLabel(mode: ForkMode): String = when (mode) {
    ForkMode.DIRECT_PATH -> stringResource(Res.string.fork_mode_direct_path)
    ForkMode.INCLUDE_BRANCHES -> stringResource(Res.string.fork_mode_include_branches)
    ForkMode.TARGET_LEVEL -> stringResource(Res.string.fork_mode_target_level)
}

@Composable
internal fun forkModeDescription(mode: ForkMode): String = when (mode) {
    ForkMode.DIRECT_PATH -> stringResource(Res.string.fork_mode_direct_path_desc)
    ForkMode.INCLUDE_BRANCHES -> stringResource(Res.string.fork_mode_include_branches_desc)
    ForkMode.TARGET_LEVEL -> stringResource(Res.string.fork_mode_target_level_desc)
}

/** Radio-select dialog controlling conversation fork depth. */
@Composable
internal fun ForkSettingsDialog(
    selectedMode: ForkMode,
    onModeSelect: (ForkMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentMode by remember { mutableStateOf(selectedMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(Res.string.fork_behavior)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ForkMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(
                                selected = currentMode == mode,
                                onClick = { currentMode = mode },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = forkModeLabel(mode),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = forkModeDescription(mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onModeSelect(currentMode) }) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
