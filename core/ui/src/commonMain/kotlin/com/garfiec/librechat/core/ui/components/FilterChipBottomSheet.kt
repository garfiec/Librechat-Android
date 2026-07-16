package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Generic bottom sheet with a FlowRow of FilterChips and an optional text input for adding new items.
 *
 * @param items All available items to display as chips.
 * @param selectedItems Currently selected items.
 * @param onSelectionChange Called with the final selection when the sheet is dismissed.
 * @param label Extracts a display label from each item.
 * @param onDismiss Called when the sheet is dismissed.
 * @param title Title displayed at the top of the sheet.
 * @param emptyMessage Message shown when there are no items and nothing is selected.
 * @param onAdd Optional callback that converts user-entered text into an item to add to the selection.
 *   When non-null, a text input row is shown. Return null to reject the input.
 * @param addPlaceholder Placeholder text for the add-new input field.
 * @param addContentDescription Content description for the add button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun <T : Any> FilterChipBottomSheet(
    items: List<T>,
    selectedItems: Set<T>,
    onSelectionChange: (Set<T>) -> Unit,
    label: (T) -> String,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    emptyMessage: String = "",
    onAdd: ((String) -> T?)? = null,
    addPlaceholder: String = "",
    addContentDescription: String = "",
) {
    val sheetState = rememberModalBottomSheetState()
    var currentSelection by remember { mutableStateOf(selectedItems) }
    var newItemText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = {
            onSelectionChange(currentSelection)
            onDismiss()
        },
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = { LowProfileDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (items.isEmpty() && currentSelection.isEmpty()) {
                if (emptyMessage.isNotEmpty()) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.forEach { item ->
                        FilterChip(
                            selected = item in currentSelection,
                            onClick = {
                                currentSelection = if (item in currentSelection) {
                                    currentSelection - item
                                } else {
                                    currentSelection + item
                                }
                            },
                            label = { Text(label(item)) },
                        )
                    }
                }
            }

            if (onAdd != null) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newItemText,
                        onValueChange = { newItemText = it },
                        placeholder = { Text(addPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val trimmed = newItemText.trim()
                            if (trimmed.isNotEmpty()) {
                                val newItem = onAdd(trimmed)
                                if (newItem != null) {
                                    currentSelection = currentSelection + newItem
                                }
                                newItemText = ""
                            }
                        },
                        enabled = newItemText.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = addContentDescription,
                        )
                    }
                }
            }
        }
    }
}
