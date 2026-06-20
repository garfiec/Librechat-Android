package com.garfiec.librechat.feature.chat.prompts.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

private val VARIABLE_PATTERN = Regex("\\{\\{(\\w+)\\}\\}")

fun extractVariables(template: String): List<String> {
    return VARIABLE_PATTERN.findAll(template)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}

fun interpolateTemplate(template: String, values: Map<String, String>): String {
    var result = template
    values.forEach { (key, value) ->
        result = result.replace("{{$key}}", value)
    }
    return result
}

@Composable
fun VariableInputDialog(
    promptTemplate: String,
    variables: List<String>,
    onInsert: (interpolatedText: String, autoSend: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val variableValues = remember { mutableStateMapOf<String, String>() }
    var autoSend by remember { mutableStateOf(false) }

    val preview = interpolateTemplate(promptTemplate, variableValues)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(Res.string.dialog_title_fill_variables)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                variables.forEach { variable ->
                    OutlinedTextField(
                        value = variableValues[variable] ?: "",
                        onValueChange = { variableValues[variable] = it },
                        label = { Text(variable) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(Res.string.preview),
                    style = MaterialTheme.typography.titleSmall,
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Auto-send",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = autoSend,
                        onCheckedChange = { autoSend = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onInsert(preview, autoSend) },
                enabled = variables.all { (variableValues[it] ?: "").isNotBlank() },
            ) {
                Text(stringResource(Res.string.action_insert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
