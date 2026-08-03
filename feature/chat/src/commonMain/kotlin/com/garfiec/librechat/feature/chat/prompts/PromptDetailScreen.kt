package com.garfiec.librechat.feature.chat.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.components.LibreChatTopBar
import com.garfiec.librechat.feature.chat.prompts.components.PromptPreviewPanel
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptDetailScreen(
    group: PromptGroupDetailDisplayData,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onUseInChat: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onShare: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LibreChatTopBar(
                title = group.name,
                onNavigateBack = onBack,
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(Res.string.cd_share_prompt),
                        )
                    }
                    IconButton(onClick = { onEdit(group.id) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(Res.string.cd_edit_prompt),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(Res.string.cd_delete_prompt),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Leading icon + name row for visual hierarchy
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    val category = group.category
                    if (!category.isNullOrBlank()) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.prompt_author, group.authorName),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            val oneliner = group.oneliner
            if (!oneliner.isNullOrBlank()) {
                Text(
                    text = oneliner,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!group.command.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Res.string.prompt_command_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text(
                        text = "/${group.command}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // Preview panel for the production prompt
            val productionPromptText = group.productionPromptText
            if (productionPromptText != null) {
                Spacer(modifier = Modifier.height(16.dp))
                PromptPreviewPanel(
                    promptTemplate = productionPromptText,
                    variableValues = emptyMap(),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Emits the prompt body, not the group's command — the command is a shorthand for
            // finding the prompt, never the text the user wants in their message.
            val usableText = group.productionPromptText
            Button(
                onClick = { usableText?.let(onUseInChat) },
                enabled = !usableText.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.use_in_chat))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
