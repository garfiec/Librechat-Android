package com.garfiec.librechat.feature.settings.screen.providerkeys

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.action_cancel
import com.garfiec.librechat.feature.settings.resources.action_save
import com.garfiec.librechat.feature.settings.resources.provider_keys_dialog_title
import com.garfiec.librechat.feature.settings.resources.provider_keys_expiry_label
import com.garfiec.librechat.feature.settings.resources.provider_keys_required_fields
import com.garfiec.librechat.feature.settings.resources.provider_keys_revoke
import com.garfiec.librechat.feature.settings.state.providerkeys.ProviderKeyExpiry
import com.garfiec.librechat.feature.settings.state.providerkeys.SetProviderKeyEffect
import com.garfiec.librechat.feature.settings.viewmodel.providerkeys.SetProviderKeyViewModel
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetProviderKeyDialog(
    endpointName: String,
    onDismiss: () -> Unit,
    onMutationSuccess: (endpointName: String) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    // One VM per endpoint name, reused across opens — bounded by the number of user-provide
    // endpoints (~5-10 in practice). The LaunchedEffect below re-fetches state on each open.
    val viewModel: SetProviderKeyViewModel = koinViewModel(
        key = "ProviderKeyVm:$endpointName",
    ) { parametersOf(endpointName) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val saveLabel = stringResource(Res.string.action_save)

    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnMutationSuccess by rememberUpdatedState(onMutationSuccess)
    val currentOnShowMessage by rememberUpdatedState(onShowMessage)

    LaunchedEffect(endpointName) {
        viewModel.refreshKeyState()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SetProviderKeyEffect.ShowMessage -> currentOnShowMessage(getString(effect.message))
                is SetProviderKeyEffect.Mutated -> {
                    // Lifecycle + optional success-toast travel together. The parent
                    // emits the message into its own snackbar host before refreshing.
                    effect.message?.let { currentOnShowMessage(getString(it)) }
                    currentOnMutationSuccess(endpointName)
                    currentOnDismiss()
                }
                is SetProviderKeyEffect.RequiredFieldsMissing -> {
                    val labels = effect.fields.map { getString(it) }.joinToString(", ")
                    currentOnShowMessage(
                        getString(Res.string.provider_keys_required_fields, labels),
                    )
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { LowProfileDragHandle() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.provider_keys_dialog_title, state.displayLabel),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            KeyStateDisplay(state.currentKeyState)

            ExpiryRow(
                selected = state.expiry,
                onSelect = viewModel::selectExpiry,
            )

            // Construct callbacks once at the dialog level — the form composables receive
            // a stable FormCallbacks instance instead of the VM, which removes the
            // ViewModelForwarding smell at the form / sub-form boundary.
            val launchGoogleFilePicker = rememberProviderKeyFilePicker(
                onFileRead = viewModel::onGoogleServiceKeyFileRead,
            )
            val callbacks = remember(viewModel, launchGoogleFilePicker) {
                ProviderKeyFormCallbacks(
                    onApiKeyChange = viewModel::updateApiKey,
                    onBaseUrlChange = viewModel::updateBaseUrl,
                    onAzureFieldChange = viewModel::updateAzureField,
                    onGoogleServiceKeyChange = viewModel::updateGoogleServiceKey,
                    onGoogleApiKeyChange = viewModel::updateGoogleApiKey,
                    onBedrockFieldChange = viewModel::updateBedrockField,
                    launchGoogleFilePicker = launchGoogleFilePicker,
                )
            }
            ProviderKeyForm(
                form = state.form,
                formKind = state.formKind,
                displayLabel = state.displayLabel,
                callbacks = callbacks,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val canRevoke = (state.currentKeyState is KeyState.Set ||
                    state.currentKeyState is KeyState.Expired) && !state.isRevoking
                OutlinedButton(
                    onClick = viewModel::revoke,
                    enabled = canRevoke,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    if (state.isRevoking) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(Res.string.provider_keys_revoke))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = viewModel::save,
                    enabled = !state.isSaving,
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(saveLabel)
                }
            }
        }
    }
}

@Composable
private fun ExpiryRow(
    selected: ProviderKeyExpiry,
    onSelect: (ProviderKeyExpiry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.provider_keys_expiry_label),
            style = MaterialTheme.typography.labelLarge,
        )
        // Simple wrapping row of choices.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderKeyExpiry.entries.forEach { entry ->
                AssistChip(
                    onClick = { onSelect(entry) },
                    label = { Text(stringResource(entry.label)) },
                    colors = if (entry == selected) {
                        androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        androidx.compose.material3.AssistChipDefaults.assistChipColors()
                    },
                )
            }
        }
    }
}
