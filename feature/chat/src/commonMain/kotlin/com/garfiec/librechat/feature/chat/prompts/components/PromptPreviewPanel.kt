package com.garfiec.librechat.feature.chat.prompts.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.prompts.substitutePromptVariables
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
fun PromptPreviewPanel(
    promptTemplate: String,
    variableValues: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val interpolated = substitutePromptVariables(promptTemplate, variableValues)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.preview),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Text(
                text = interpolated.ifBlank { "Enter variable values to see preview" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (interpolated.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }
    }
}
