package com.garfiec.librechat.feature.skills.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.response.Category
import com.garfiec.librechat.feature.skills.resources.*
import com.garfiec.librechat.feature.skills.resources.Res
import com.garfiec.librechat.feature.skills.viewmodel.SkillEditorEvent
import com.garfiec.librechat.feature.skills.viewmodel.SkillEditorViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
    skillId: String?,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SkillEditorViewModel = koinViewModel { parametersOf(skillId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnSave by rememberUpdatedState(onSave)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SkillEditorEvent.Saved -> currentOnSave(event.skillId)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val titleRes = if (uiState.isEditMode) {
                        Res.string.skill_editor_edit_title
                    } else {
                        Res.string.skill_editor_create_title
                    }
                    Text(stringResource(titleRes))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.skills_back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = uiState.canSave) {
                        Text(stringResource(Res.string.skill_save))
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            uiState.conflictNotice?.let { notice ->
                ConflictBanner(notice = notice, onDismiss = viewModel::dismissConflictNotice)
            }
            uiState.error?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::onNameChanged,
                label = { Text(stringResource(Res.string.skill_field_name)) },
                placeholder = { Text(stringResource(Res.string.skill_field_name_hint)) },
                singleLine = true,
                isError = uiState.nameError,
                supportingText = if (uiState.nameError) {
                    { Text(stringResource(Res.string.skill_field_name_error)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.displayTitle,
                onValueChange = viewModel::onDisplayTitleChanged,
                label = { Text(stringResource(Res.string.skill_field_display_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.description,
                onValueChange = viewModel::onDescriptionChanged,
                label = { Text(stringResource(Res.string.skill_field_description)) },
                isError = uiState.descriptionTooLong,
                supportingText = if (uiState.descriptionTooLong) {
                    { Text(stringResource(Res.string.skill_field_description_error)) }
                } else {
                    null
                },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            CategoryDropdown(
                selected = uiState.category,
                categories = uiState.availableCategories,
                onSelect = viewModel::onCategoryChanged,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.skill_field_always_apply),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = uiState.alwaysApply,
                    onCheckedChange = viewModel::onAlwaysApplyChanged,
                )
            }
            OutlinedTextField(
                value = uiState.body,
                onValueChange = viewModel::onBodyChanged,
                label = { Text(stringResource(Res.string.skill_field_body)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
            )
        }
    }
}

/**
 * Category picker — a preset dropdown sourced from `GET /api/categories` (the same
 * shared list Prompts use), matching web's CategorySelector. [selected] is the
 * persisted category `value`; the menu lists each preset's `value`. A previously
 * saved category that isn't in the preset list (custom/legacy) is still shown as
 * the current selection so an edit never silently drops it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    selected: String,
    categories: List<Category>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val values = remember(categories) { categories.mapNotNull { it.value?.takeIf { v -> v.isNotBlank() } } }
    val placeholder = stringResource(Res.string.skill_category_none)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.ifBlank { placeholder },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.skill_field_category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // "None" clears the category (sent as null on save via ifBlank).
            DropdownMenuItem(
                text = { Text(placeholder) },
                onClick = {
                    onSelect("")
                    expanded = false
                },
            )
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
            // Preserve a saved-but-unlisted category so editing doesn't drop it.
            if (selected.isNotBlank() && selected !in values) {
                DropdownMenuItem(
                    text = { Text(selected) },
                    onClick = {
                        onSelect(selected)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ConflictBanner(notice: String, onDismiss: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.skill_conflict_dismiss))
            }
        }
    }
}
