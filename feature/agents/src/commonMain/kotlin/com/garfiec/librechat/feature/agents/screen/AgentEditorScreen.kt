package com.garfiec.librechat.feature.agents.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.feature.agents.components.rememberAgentFilePicker
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorEvent
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorViewModel
import com.garfiec.librechat.feature.agents.viewmodel.AgentFileSlot
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// The screen owns the editor ViewModel and forwards it to its own private child
// composables (dialogs, form). These are screen-internal, not reusable components,
// so this is intentional structure, not the cross-component forwarding the rule targets.
@Suppress("ViewModelForwarding")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorScreen(
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    agentId: String? = null,
    onDelete: () -> Unit = onBack,
    onDuplicate: (String) -> Unit = onSave,
    viewModel: AgentEditorViewModel = koinViewModel { parametersOf(agentId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showToolDialog by rememberSaveable { mutableStateOf(false) }
    val currentOnSaved by rememberUpdatedState(onSave)
    val currentOnDuplicated by rememberUpdatedState(onDuplicate)
    val currentOnDeleted by rememberUpdatedState(onDelete)
    val snackbarHostState = remember { SnackbarHostState() }

    // One picker per slot; iOS impl is a stub today.
    val codeFilePicker = rememberAgentFilePicker(
        onFilePick = { ref -> viewModel.uploadAgentFile(ref, AgentFileSlot.CODE) },
    )
    val knowledgeFilePicker = rememberAgentFilePicker(
        onFilePick = { ref -> viewModel.uploadAgentFile(ref, AgentFileSlot.KNOWLEDGE) },
    )
    val contextFilePicker = rememberAgentFilePicker(
        onFilePick = { ref -> viewModel.uploadAgentFile(ref, AgentFileSlot.CONTEXT) },
    )

    // Map VM sentinel error markers to localized snackbar messages. Other errors
    // continue to flow through the ErrorBanner banner below.
    val saveFirstMsg = stringResource(Res.string.agent_files_save_first)
    val uploadFailedMsg = stringResource(Res.string.agent_file_upload_failed)
    val removeFailedMsg = stringResource(Res.string.agent_file_remove_failed)
    // The numeric MB comes from a VM-side marker; resolve the format with a placeholder
    // we replace at LaunchedEffect time. `%1$d` -> "%d" makes the substitution trivial.
    val tooLargeTemplate = stringResource(Res.string.agent_file_too_large, 0)
        .replaceFirst("0", "%d")
    LaunchedEffect(uiState.error) {
        val err = uiState.error ?: return@LaunchedEffect
        val localized = when {
            err == AgentEditorViewModel.AGENT_FILES_SAVE_FIRST_MARKER -> saveFirstMsg
            err.startsWith(AgentEditorViewModel.AGENT_FILES_TOO_LARGE_MARKER) -> {
                val mb = err.removePrefix(AgentEditorViewModel.AGENT_FILES_TOO_LARGE_MARKER)
                    .toIntOrNull() ?: 0
                tooLargeTemplate.replace("%d", mb.toString())
            }
            err == AgentEditorViewModel.AGENT_FILE_UPLOAD_FAILED_MARKER -> uploadFailedMsg
            err == AgentEditorViewModel.AGENT_FILE_REMOVE_FAILED_MARKER -> removeFailedMsg
            else -> null
        }
        if (localized != null) {
            snackbarHostState.showSnackbar(localized)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AgentEditorEvent.SaveSuccess -> currentOnSaved(event.agentId)
                is AgentEditorEvent.DuplicateSuccess -> currentOnDuplicated(event.agentId)
                is AgentEditorEvent.DeleteSuccess -> currentOnDeleted()
            }
        }
    }

    AgentEditorDialogs(
        uiState = uiState,
        viewModel = viewModel,
        showToolDialog = showToolDialog,
        onDismissToolDialog = { showToolDialog = false },
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AgentEditorTopBar(
                isEditMode = uiState.isEditMode,
                onBack = onBack,
                onDuplicate = viewModel::showDuplicateConfirmation,
                onVersionHistory = viewModel::showVersionHistory,
                onDelete = viewModel::showDeleteConfirmation,
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                LoadingIndicator(modifier = Modifier.padding(innerPadding))
            }

            else -> {
                AgentEditorForm(
                    uiState = uiState,
                    viewModel = viewModel,
                    onAddCodeFile = { codeFilePicker.launch("*/*") },
                    onAddKnowledgeFile = { knowledgeFilePicker.launch("*/*") },
                    onAddContextFile = { contextFilePicker.launch("*/*") },
                    onShowToolDialog = { showToolDialog = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}
