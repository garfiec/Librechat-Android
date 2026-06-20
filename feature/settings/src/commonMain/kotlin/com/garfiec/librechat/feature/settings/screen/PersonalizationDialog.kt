package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import org.jetbrains.compose.resources.stringResource

/** "About you" and "Response style" text areas with enable toggle; fields disabled when off. */
@Composable
internal fun PersonalizationDialog(
    aboutUser: String,
    responseStyle: String,
    enabled: Boolean,
    onSave: (aboutUser: String, responseStyle: String, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentAboutUser by remember { mutableStateOf(aboutUser) }
    var currentResponseStyle by remember { mutableStateOf(responseStyle) }
    var currentEnabled by remember { mutableStateOf(enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(Res.string.personalization)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.enable_personalization),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = currentEnabled,
                        onCheckedChange = { currentEnabled = it },
                    )
                }

                OutlinedTextField(
                    value = currentAboutUser,
                    onValueChange = { currentAboutUser = it },
                    label = { Text(stringResource(Res.string.about_you_label)) },
                    supportingText = {
                        Text(stringResource(Res.string.about_you_hint))
                    },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = currentEnabled,
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = currentResponseStyle,
                    onValueChange = { currentResponseStyle = it },
                    label = { Text(stringResource(Res.string.response_style_label)) },
                    supportingText = {
                        Text(stringResource(Res.string.response_style_hint))
                    },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = currentEnabled,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(currentAboutUser, currentResponseStyle, currentEnabled)
                },
            ) {
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
