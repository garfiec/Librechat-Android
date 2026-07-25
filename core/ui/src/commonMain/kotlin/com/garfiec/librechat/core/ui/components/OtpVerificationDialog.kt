package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Reusable OTP verification dialog for 2FA-protected operations.
 *
 * Shows a 6-digit OTP input with visual digit boxes (paste-friendly, auto-submit on 6th digit).
 * Includes a "Use a backup code instead" toggle that switches to a single text field
 * for alphanumeric backup code input with an explicit Verify button.
 *
 * @param title Dialog title (e.g. "Verify Identity", "Enter OTP")
 * @param description Optional description text shown below the title
 * @param isLoading Whether to disable inputs while an operation is in progress
 * @param onVerify Called with (token, backupCode) — one will be non-null
 * @param onDismiss Called when the dialog is dismissed
 * @param verifyLabel Label for the verify button (backup code mode)
 * @param cancelLabel Label for the cancel/dismiss button
 * @param backupCodeLabel Label for the backup code text field
 * @param useBackupToggleLabel Text for the "use backup code" toggle
 * @param useOtpToggleLabel Text for the "use OTP" toggle
 */
@Composable
fun OtpVerificationDialog(
    title: String,
    onVerify: (token: String?, backupCode: String?) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
    isLoading: Boolean = false,
    verifyLabel: String = "Verify",
    cancelLabel: String = "Cancel",
    backupCodeLabel: String = "Backup Code",
    useBackupToggleLabel: String = "Use a backup code instead",
    useOtpToggleLabel: String = "Use OTP code instead",
) {
    var otpValue by remember { mutableStateOf("") }
    var backupCodeValue by remember { mutableStateOf("") }
    var useBackupCode by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            submitted = false
        }
    }

    LaunchedEffect(useBackupCode) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
            ) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (useBackupCode) {
                    OutlinedTextField(
                        value = backupCodeValue,
                        onValueChange = { backupCodeValue = it },
                        label = { Text(backupCodeLabel) },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (backupCodeValue.isNotBlank()) {
                                    onVerify(null, backupCodeValue.trim())
                                }
                            },
                        ),
                    )
                } else {
                    OtpCodeInput(
                        value = otpValue,
                        onValueChange = { newValue ->
                            otpValue = newValue
                            if (newValue.length == OTP_LENGTH && !submitted) {
                                submitted = true
                                onVerify(newValue, null)
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        useBackupCode = !useBackupCode
                        otpValue = ""
                        backupCodeValue = ""
                    },
                    enabled = !isLoading,
                ) {
                    Text(
                        if (useBackupCode) useOtpToggleLabel else useBackupToggleLabel,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (useBackupCode) {
                TextButton(
                    onClick = { onVerify(null, backupCodeValue.trim()) },
                    enabled = !isLoading && backupCodeValue.isNotBlank(),
                ) {
                    Text(verifyLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(cancelLabel)
            }
        },
    )
}
