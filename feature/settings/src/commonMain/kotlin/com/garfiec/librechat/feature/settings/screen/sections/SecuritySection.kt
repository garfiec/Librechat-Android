package com.garfiec.librechat.feature.settings.screen.sections

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.components.OTP_LENGTH
import com.garfiec.librechat.core.ui.components.OtpCodeInput
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.util.copyToClipboard
import com.garfiec.librechat.feature.settings.util.openUri
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import org.jetbrains.compose.resources.stringResource

/**
 * The `secret` query parameter of an `otpauth://` URI -- what an authenticator's "enter a setup
 * key" screen asks for. The full URI is not accepted there, so manual enrollment needs this alone.
 */
private fun otpauthSecret(uri: String): String? =
    uri.substringAfter("secret=", "").substringBefore("&").takeIf { it.isNotEmpty() }

@Composable
internal fun TwoFactorSetupDialog(
    otpauthUrl: String?,
    isLoading: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var noAuthenticator by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    val secret = otpauthUrl?.let(::otpauthSecret)

    // A rejected code leaves six digits in the boxes; re-arm so editing (or Verify) can submit again.
    LaunchedEffect(isLoading) { if (!isLoading) submitted = false }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_title_enable_2fa)) },
        text = {
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.twofa_enroll_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (otpauthUrl != null) {
                    // The phone IS the second device, so it cannot scan its own screen -- handing
                    // the otpauth:// URI to the authenticator is the enrollment path that works
                    // here. QR and the setup key are the fallbacks, not the default.
                    FilledTonalButton(
                        onClick = { noAuthenticator = !openUri(otpauthUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.action_open_authenticator))
                    }
                    if (noAuthenticator) {
                        Text(
                            text = stringResource(Res.string.twofa_no_authenticator),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (secret != null) {
                        SetupKeyRow(
                            secret = secret,
                            onCopy = {
                                copyToClipboard(secret, "2FA setup key")
                                copied = true
                            },
                        )
                    }
                    TextButton(onClick = { showQr = !showQr }) {
                        Text(
                            stringResource(
                                if (showQr) Res.string.action_hide_qr else Res.string.action_show_qr,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (showQr) {
                        // Black-on-transparent by default, which is unscannable on a dark surface.
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.White,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Image(
                                painter = rememberQrCodePainter(otpauthUrl),
                                contentDescription = stringResource(Res.string.cd_twofa_qr),
                                modifier = Modifier
                                    .padding(16.dp)
                                    .size(QR_SIZE),
                            )
                        }
                    }
                    if (copied) {
                        Text(
                            text = stringResource(Res.string.copied_to_clipboard),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                OtpCodeInput(
                    value = code,
                    onValueChange = {
                        code = it
                        if (it.length == OTP_LENGTH && !submitted) {
                            submitted = true
                            onConfirm(it)
                        }
                    },
                    enabled = !isLoading,
                )
            }
        },
        confirmButton = {
            // Auto-submit covers the happy path; the button is what lets a user retry the same
            // code after a failure without having to delete a digit first.
            TextButton(
                onClick = {
                    submitted = true
                    onConfirm(code)
                },
                enabled = code.length == OTP_LENGTH && !isLoading,
            ) {
                Text(stringResource(if (isLoading) Res.string.action_verifying else Res.string.action_verify))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun SetupKeyRow(secret: String, onCopy: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.twofa_setup_key),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = secret,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(Res.string.cd_copy_setup_key),
                )
            }
        }
    }
}

private val QR_SIZE = 200.dp

@Composable
internal fun TwoFactorCodeDialog(
    title: String,
    description: String,
    isLoading: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text(stringResource(Res.string.hint_verification_code)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(code) },
                enabled = code.length == 6 && !isLoading,
            ) {
                Text(stringResource(if (isLoading) Res.string.action_verifying else Res.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun BackupCodesDialog(
    backupCodes: List<String>,
    onDismiss: () -> Unit,
) {
    // These are single-use recovery codes shown exactly once. Without a copy action the only way
    // to keep them is a screenshot or transcribing ten hex strings by hand, so offer the same
    // copy-then-confirm affordance ApiKeyCreateDialog uses for its equally one-shot secret.
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_title_backup_codes)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.backup_codes_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            backupCodes.forEach { code ->
                                Text(
                                    text = code,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
                if (copied) {
                    Text(
                        text = stringResource(Res.string.copied_to_clipboard),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_done))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    copyToClipboard(backupCodes.joinToString("\n"), "Backup Codes")
                    copied = true
                },
            ) {
                Text(stringResource(Res.string.action_copy))
            }
        },
    )
}
