package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.ui.components.OtpVerificationDialog
import com.garfiec.librechat.feature.settings.resources.*
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.screen.sections.BackupCodesDialog
import com.garfiec.librechat.feature.settings.screen.sections.TwoFactorCodeDialog
import com.garfiec.librechat.feature.settings.screen.sections.TwoFactorSetupDialog
import com.garfiec.librechat.feature.settings.viewmodel.SettingsViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    onLogout: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToApiKeys: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToProviderKeys: () -> Unit,
    onNavigateToRoleSkillsAdmin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_account)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        AccountSettingsContent(
            onLogout = onLogout,
            onNavigateToApiKeys = onNavigateToApiKeys,
            onNavigateToFavorites = onNavigateToFavorites,
            onNavigateToProviderKeys = onNavigateToProviderKeys,
            onNavigateToRoleSkillsAdmin = onNavigateToRoleSkillsAdmin,
            snackbarHostState = snackbarHostState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * Reusable Account settings content (without Scaffold/TopAppBar).
 * Used by both the standalone screen and the tabbed settings screen.
 */
@Composable
fun AccountSettingsContent(
    onLogout: () -> Unit,
    onNavigateToApiKeys: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToProviderKeys: () -> Unit,
    onNavigateToRoleSkillsAdmin: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnLogout by rememberUpdatedState(onLogout)
    val retryLabel = stringResource(Res.string.action_retry)

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = error,
            actionLabel = retryLabel,
        )
        viewModel.dismissError()
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.retry()
        }
    }

    LaunchedEffect(uiState.isLoggedOut, uiState.isAccountDeleted) {
        if (uiState.isLoggedOut || uiState.isAccountDeleted) {
            currentOnLogout()
        }
    }

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Account section
            item(key = "account_header") {
                SectionHeader(stringResource(Res.string.section_profile))
            }
            item(key = "account_info") {
                AccountInfoSection(
                    user = uiState.user,
                    profileLoadError = uiState.profileLoadError,
                    onAvatarClick = viewModel::showAvatarDialog,
                    onRetry = viewModel::retry,
                )
            }
            // Sign out — grouped with the profile it acts on, kept out of the Danger Zone.
            item(key = "sign_out") {
                SignOutButton(
                    isDeleting = uiState.isDeletingAccount,
                    onLogoutClick = { showLogoutDialog = true },
                )
            }

            // Balance section
            item(key = "balance_header") {
                SectionHeader(stringResource(Res.string.section_balance))
            }
            item(key = "balance_section") {
                BalanceSection(
                    tokenCredits = uiState.tokenCredits,
                    isLoading = uiState.isBalanceLoading,
                )
            }

            // Security section
            item(key = "security_header") {
                SectionHeader(stringResource(Res.string.section_security))
            }
            item(key = "security_settings") {
                SecuritySection(
                    isTwoFactorEnabled = uiState.isTwoFactorEnabled,
                    isLoading = uiState.isTwoFactorLoading,
                    onToggleTwoFactor = viewModel::toggleTwoFactor,
                    onViewBackupCodes = viewModel::viewBackupCodes,
                )
            }
            item(key = "api_keys_row") {
                AccountSettingsRow(
                    icon = Icons.Default.Key,
                    title = stringResource(Res.string.api_keys),
                    subtitle = stringResource(Res.string.api_keys_subtitle),
                    onClick = onNavigateToApiKeys,
                )
            }
            item(key = "provider_keys_row") {
                AccountSettingsRow(
                    icon = Icons.Default.Key,
                    title = stringResource(Res.string.provider_keys_title),
                    subtitle = stringResource(Res.string.provider_keys_subtitle),
                    onClick = onNavigateToProviderKeys,
                )
            }
            item(key = "favorites_row") {
                AccountSettingsRow(
                    icon = Icons.Default.Star,
                    title = stringResource(Res.string.favorites),
                    subtitle = stringResource(Res.string.favorites_subtitle),
                    onClick = onNavigateToFavorites,
                )
            }
            // Admin-only: role skill access. Fail-CLOSED — shown only for ADMIN.
            if (uiState.isAdmin) {
                item(key = "role_skills_admin_row") {
                    AccountSettingsRow(
                        icon = Icons.Default.Star,
                        title = stringResource(Res.string.role_skills_settings_row),
                        subtitle = stringResource(Res.string.role_skills_description),
                        onClick = onNavigateToRoleSkillsAdmin,
                    )
                }
            }

            // Danger zone — destructive, irreversible actions only.
            if (uiState.allowAccountDeletion) {
                item(key = "danger_header") {
                    SectionHeader(stringResource(Res.string.section_danger_zone))
                }
                item(key = "danger_actions") {
                    DangerZone(
                        isDeleting = uiState.isDeletingAccount,
                        onDeleteClick = { showDeleteDialog = true },
                    )
                }
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }

        // Avatar upload dialog
        if (uiState.showAvatarDialog) {
            AvatarUploadDialog(
                currentAvatarUrl = uiState.user?.avatar,
                isUploading = uiState.isAvatarUploading,
                onPickImage = viewModel::uploadAvatar,
                onDismiss = viewModel::dismissAvatarDialog,
            )
        }

        // 2FA enable setup dialog
        if (uiState.showTwoFactorSetupDialog) {
            TwoFactorSetupDialog(
                otpauthUrl = uiState.twoFactorOtpauthUrl,
                isLoading = uiState.isTwoFactorLoading,
                onConfirm = viewModel::confirmEnableTwoFactor,
                onDismiss = viewModel::dismissTwoFactorSetupDialog,
            )
        }

        // 2FA disable dialog
        if (uiState.showDisableTwoFactorDialog) {
            TwoFactorCodeDialog(
                title = stringResource(Res.string.dialog_title_disable_2fa),
                description = stringResource(Res.string.twofa_disable_instructions),
                isLoading = uiState.isTwoFactorLoading,
                onConfirm = viewModel::confirmDisableTwoFactor,
                onDismiss = viewModel::dismissDisableTwoFactorDialog,
            )
        }

        // Backup codes dialog
        if (uiState.showBackupCodesDialog) {
            BackupCodesDialog(
                backupCodes = uiState.backupCodes,
                onDismiss = viewModel::dismissBackupCodesDialog,
            )
        }

        // Logout confirmation dialog
        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text(stringResource(Res.string.dialog_title_sign_out)) },
                text = { Text(stringResource(Res.string.dialog_sign_out_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showLogoutDialog = false
                            viewModel.logout()
                        },
                    ) {
                        Text(stringResource(Res.string.action_sign_out))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }

        // Delete account confirmation dialog
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(Res.string.dialog_title_delete_account)) },
                text = {
                    Text(stringResource(Res.string.dialog_delete_account_message))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteAccount()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(Res.string.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                },
            )
        }

        // OTP dialog for account deletion when 2FA is enabled
        if (uiState.showDeleteAccountOtpDialog) {
            OtpVerificationDialog(
                title = stringResource(Res.string.otp_title_verify_identity),
                description = stringResource(Res.string.otp_desc_delete_account),
                isLoading = uiState.isDeletingAccount,
                onVerify = { token, backupCode ->
                    viewModel.deleteAccount(token = token, backupCode = backupCode)
                },
                onDismiss = viewModel::dismissDeleteAccountOtpDialog,
                verifyLabel = stringResource(Res.string.otp_verify),
                cancelLabel = stringResource(Res.string.otp_cancel),
                backupCodeLabel = stringResource(Res.string.otp_backup_code_label),
                useBackupToggleLabel = stringResource(Res.string.otp_use_backup_code),
                useOtpToggleLabel = stringResource(Res.string.otp_use_otp_code),
            )
        }

        // OTP dialog for enabling 2FA when re-enrolling
        if (uiState.showEnableTwoFactorOtpDialog) {
            OtpVerificationDialog(
                title = stringResource(Res.string.otp_title_verify_identity),
                description = stringResource(Res.string.otp_desc_reenroll_2fa),
                isLoading = uiState.isTwoFactorLoading,
                onVerify = { token, backupCode ->
                    viewModel.enableTwoFactorWithOtp(token = token, backupCode = backupCode)
                },
                onDismiss = viewModel::dismissEnableTwoFactorOtpDialog,
                verifyLabel = stringResource(Res.string.otp_verify),
                cancelLabel = stringResource(Res.string.otp_cancel),
                backupCodeLabel = stringResource(Res.string.otp_backup_code_label),
                useBackupToggleLabel = stringResource(Res.string.otp_use_backup_code),
                useOtpToggleLabel = stringResource(Res.string.otp_use_otp_code),
            )
        }

        // OTP dialog for regenerating backup codes
        if (uiState.showBackupCodesOtpDialog) {
            OtpVerificationDialog(
                title = stringResource(Res.string.otp_title_verify_identity),
                description = stringResource(Res.string.otp_desc_regenerate_backup_codes),
                isLoading = uiState.isTwoFactorLoading,
                onVerify = { token, backupCode ->
                    viewModel.viewBackupCodesWithOtp(token = token, backupCode = backupCode)
                },
                onDismiss = viewModel::dismissBackupCodesOtpDialog,
                verifyLabel = stringResource(Res.string.otp_verify),
                cancelLabel = stringResource(Res.string.otp_cancel),
                backupCodeLabel = stringResource(Res.string.otp_backup_code_label),
                useBackupToggleLabel = stringResource(Res.string.otp_use_backup_code),
                useOtpToggleLabel = stringResource(Res.string.otp_use_otp_code),
            )
        }
    } // Column
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SignOutButton(
    isDeleting: Boolean,
    onLogoutClick: () -> Unit,
) {
    // Sign out closes out the profile group; the divider below it separates the
    // whole Profile section (info + sign out) from Balance.
    Column {
        OutlinedButton(
            onClick = onLogoutClick,
            enabled = !isDeleting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(Res.string.action_sign_out))
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DangerZone(
    isDeleting: Boolean,
    onDeleteClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onDeleteClick,
        enabled = !isDeleting,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = if (isDeleting) 0.12f else 1f),
        ),
    ) {
        Text(stringResource(Res.string.delete_account))
    }
}

@Composable
private fun AccountSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}
