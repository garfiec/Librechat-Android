package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData

private val MAX_LIST_HEIGHT = 220.dp

/**
 * Prompt suggestions for the composer's `/` picker.
 *
 * Rendered inline in the composer column rather than as a popup or dropdown, so it participates in
 * the same insets as the input itself and cannot be clipped or hidden behind the keyboard.
 */
@Composable
fun PromptSuggestionList(
    suggestions: List<PromptMentionDisplayData>,
    onSelect: (PromptMentionDisplayData) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = ChatInputDefaults.containerColor,
        border = BorderStroke(1.dp, ChatInputDefaults.borderColor),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = MAX_LIST_HEIGHT)) {
            items(suggestions, key = { it.id }, contentType = { "prompt-suggestion" }) { suggestion ->
                PromptSuggestionRow(
                    suggestion = suggestion,
                    onClick = { onSelect(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun PromptSuggestionRow(
    suggestion: PromptMentionDisplayData,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = promptCategoryIcon(suggestion.category),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = suggestion.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Falls back to the prompt body so a group with no oneliner still shows what it does —
            // the web client leaves this slot empty entirely.
            val description = suggestion.oneliner?.takeIf { it.isNotBlank() } ?: suggestion.promptText
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
