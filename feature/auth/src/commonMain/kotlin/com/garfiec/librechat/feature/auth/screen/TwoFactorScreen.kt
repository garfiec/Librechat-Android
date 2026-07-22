package com.garfiec.librechat.feature.auth.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.feature.auth.resources.*
import com.garfiec.librechat.feature.auth.resources.Res
import com.garfiec.librechat.feature.auth.viewmodel.TwoFactorViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFactorScreen(
    onVerify: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    tempToken: String? = null,
    viewModel: TwoFactorViewModel = koinViewModel { parametersOf(tempToken) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnVerify by rememberUpdatedState(onVerify)

    LaunchedEffect(uiState.isVerified) {
        if (uiState.isVerified) currentOnVerify()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.two_factor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (uiState.isBackupMode) {
                    stringResource(Res.string.enter_backup_code)
                } else {
                    stringResource(Res.string.enter_verification_code)
                },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (uiState.isBackupMode) {
                    stringResource(Res.string.backup_code_prompt)
                } else {
                    stringResource(Res.string.authenticator_prompt)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.isBackupMode) {
                BackupCodeInput(
                    code = uiState.backupCode,
                    onCodeChange = viewModel::onBackupCodeChanged,
                    enabled = !uiState.isLoading,
                )
            } else {
                DigitBoxes(
                    digits = uiState.digits,
                    onDigitChange = viewModel::onDigitChanged,
                    enabled = !uiState.isLoading,
                    focusResetKey = uiState.codeAttempt,
                )
            }

            if (uiState.error != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("twofa_error"),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // A full row normally auto-submits, but a kept entry (network error) needs an explicit
            // button to move forward.
            Button(
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth().testTag("twofa_verify"),
                enabled = !uiState.isLoading && if (uiState.isBackupMode) {
                    uiState.backupCode.isNotBlank()
                } else {
                    uiState.digits.all { it.isNotEmpty() }
                },
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(Res.string.verify))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = viewModel::toggleBackupMode,
                modifier = Modifier.testTag("twofa_toggle_mode"),
            ) {
                Text(
                    if (uiState.isBackupMode) {
                        stringResource(Res.string.use_authenticator_code)
                    } else {
                        stringResource(Res.string.use_backup_code)
                    },
                )
            }
        }
    }
}

@Composable
private fun DigitBoxes(
    digits: List<String>,
    onDigitChange: (Int, String) -> Unit,
    enabled: Boolean,
    focusResetKey: Int,
    modifier: Modifier = Modifier,
) {
    val focusRequesters = remember { List(6) { FocusRequester() } }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        digits.forEachIndexed { index, digit ->
            if (index > 0) Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = digit,
                onValueChange = { value ->
                    val filtered = value.filter { it.isDigit() }.take(1)
                    onDigitChange(index, filtered)
                    if (filtered.isNotEmpty() && index < 5) {
                        focusRequesters[index + 1].requestFocus()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("twofa_digit_$index")
                    .focusRequester(focusRequesters[index]),
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }

    // Re-focus the first box on each rejected code: disabled boxes drop focus while the request runs.
    LaunchedEffect(focusResetKey) {
        focusRequesters[0].requestFocus()
    }
}

@Composable
private fun BackupCodeInput(
    code: String,
    onCodeChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = modifier.fillMaxWidth().testTag("twofa_backup_code"),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(Res.string.backup_code_label)) },
    )
}
