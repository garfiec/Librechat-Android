package com.garfiec.librechat.feature.skills.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.feature.skills.components.SkillAclSharingSection
import com.garfiec.librechat.feature.skills.components.SkillFilesSection
import com.garfiec.librechat.feature.skills.components.rememberSkillFilePicker
import com.garfiec.librechat.feature.skills.resources.*
import com.garfiec.librechat.feature.skills.resources.Res
import com.garfiec.librechat.feature.skills.viewmodel.SkillAclViewModel
import com.garfiec.librechat.feature.skills.viewmodel.SkillDetailEvent
import com.garfiec.librechat.feature.skills.viewmodel.SkillDetailViewModel
import com.garfiec.librechat.feature.skills.viewmodel.SkillFilesViewModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(
    skillId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SkillDetailViewModel = koinViewModel { parametersOf(skillId) },
) {
    // Obtained locally (not forwarded params) so detekt's
    // ComposableForwardingViewModel rule is satisfied — each is passed to a
    // single section, mirroring the agent editor's pattern.
    val aclViewModel: SkillAclViewModel = koinViewModel()
    val filesViewModel: SkillFilesViewModel = koinViewModel { parametersOf(skillId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val aclState by aclViewModel.uiState.collectAsStateWithLifecycle()
    val filesState by filesViewModel.uiState.collectAsStateWithLifecycle()
    val currentOnDelete by rememberUpdatedState(onDelete)

    val filePicker = rememberSkillFilePicker(onPick = { doc -> filesViewModel.upload(doc) })

    // Load on first show and refetch on return from the editor so an edit's new
    // content replaces the pre-edit view. Nav3 retains the VM, so init alone
    // wouldn't re-fetch. load() skips the spinner when content is already present.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.load()
        filesViewModel.load()
        // Only fetch ACL grants when the user can actually share (fail-closed).
        if (aclState.canShare) aclViewModel.load(skillId)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SkillDetailEvent.Deleted -> currentOnDelete()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(
                        text = uiState.skill?.let { it.displayTitle?.takeIf { t -> t.isNotBlank() } ?: it.name }
                            ?: stringResource(Res.string.skills_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.skills_back))
                    }
                },
                actions = {
                    val skill = uiState.skill
                    if (skill != null) {
                        IconButton(onClick = viewModel::toggleSource) {
                            Text(if (uiState.showSource) "</>" else "¶", style = MaterialTheme.typography.titleMedium)
                        }
                        if (uiState.canEdit) {
                            IconButton(onClick = { onEdit(skill.id) }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.skill_edit))
                            }
                        }
                        if (uiState.canDelete) {
                            IconButton(onClick = viewModel::requestDelete) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.skill_delete))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.error != null && uiState.skill == null -> Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                uiState.skill != null -> {
                    val skill = uiState.skill!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        if (skill.description.isNotBlank()) {
                            Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                        }
                        skill.category?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = stringResource(Res.string.skill_category_label, it),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            text = stringResource(Res.string.skill_version_label, skill.version),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.skill_active_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(Res.string.skill_active_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = uiState.isActive,
                                // Disabled until the full states map loads — toggling on a
                                // null snapshot would full-replace-clobber other overrides.
                                enabled = uiState.activeStateLoaded,
                                onCheckedChange = { viewModel.toggleActive() },
                            )
                        }

                        when {
                            skill.body.isBlank() -> Text(
                                text = stringResource(Res.string.skill_empty_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            uiState.showSource -> Text(
                                text = skill.body,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            else -> Markdown(
                                content = skill.body,
                                colors = markdownColor(),
                                typography = markdownTypography(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // Attached files (flat list; upload/delete gated on canEditFiles).
                        Spacer(modifier = Modifier.height(16.dp))
                        SkillFilesSection(
                            state = filesState,
                            onAddFile = { filePicker.launch(emptyList()) },
                            onRemoveFile = { file -> filesViewModel.delete(file) },
                        )

                        // Sharing (ACL) — fail-CLOSED on SKILLS.SHARE.
                        if (aclState.canShare) {
                            Spacer(modifier = Modifier.height(16.dp))
                            SkillAclSharingSection(viewModel = aclViewModel)
                        }
                    }
                }
            }
        }
    }

    if (uiState.showDeleteConfirm) {
        val name = uiState.skill?.let { it.displayTitle?.takeIf { t -> t.isNotBlank() } ?: it.name } ?: ""
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(Res.string.skill_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.skill_delete_confirm_message, name)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(Res.string.skill_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(Res.string.skill_cancel))
                }
            },
        )
    }
}
