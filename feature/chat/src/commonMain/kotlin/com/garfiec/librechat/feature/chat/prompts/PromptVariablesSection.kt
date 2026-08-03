package com.garfiec.librechat.feature.chat.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Composable that parses `{{variable_name}}` patterns from [promptText],
 * renders an [OutlinedTextField] per variable, and shows a preview of the
 * prompt with variables substituted.
 */
@Composable
fun PromptVariablesSection(
    promptText: String,
    variableValues: Map<String, String>,
    onVariableChange: (name: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val variables = remember(promptText) { extractPromptVariables(promptText) }

    if (variables.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            text = stringResource(Res.string.label_variables),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))

        variables.forEach { varName ->
            OutlinedTextField(
                value = variableValues[varName] ?: "",
                onValueChange = { onVariableChange(varName, it) },
                label = { Text(varName) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(Res.string.preview),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))

        val preview = remember(promptText, variableValues) {
            substitutePromptVariables(promptText, variableValues)
        }

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
    }
}
