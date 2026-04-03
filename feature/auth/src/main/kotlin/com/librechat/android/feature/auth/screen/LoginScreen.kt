package com.librechat.android.feature.auth.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librechat.android.feature.auth.R
import com.librechat.android.feature.auth.oauth.OAuthWebViewContract
import com.librechat.android.feature.auth.viewmodel.LoginViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToForgotPassword: () -> Unit = {},
    onNavigateToTwoFactor: (String) -> Unit = {},
    viewModel: LoginViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val oauthLauncher = rememberLauncherForActivityResult(OAuthWebViewContract()) { token ->
        if (!token.isNullOrBlank()) {
            viewModel.completeOAuthWithRefreshToken(token)
        }
    }

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(uiState.twoFactorTempToken) {
        val tempToken = uiState.twoFactorTempToken
        if (tempToken != null) {
            onNavigateToTwoFactor(tempToken)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (uiState.emailLoginEnabled) {
            OutlinedTextField(
                value = uiState.email,
                onValueChange = viewModel::onEmailChanged,
                label = { Text(stringResource(R.string.email_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isLoading,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChanged,
                label = { Text(stringResource(R.string.password_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !uiState.isLoading,
            )
        }

        if (uiState.error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (uiState.emailLoginEnabled) {
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = viewModel::login,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.continue_button))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onNavigateToForgotPassword) {
                Text(stringResource(R.string.forgot_password))
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.registrationEnabled) {
                TextButton(onClick = onNavigateToRegister) {
                    Text(stringResource(R.string.no_account_sign_up))
                }
            }
        }

        // OAuth social login buttons
        val socialLogins = uiState.socialLogins
        if (uiState.socialLoginEnabled && socialLogins.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.emailLoginEnabled) {
                HorizontalDivider(modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.or_continue_with),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            socialLogins.forEach { provider ->
                OutlinedButton(
                    onClick = { oauthLauncher.launch(viewModel.createOAuthIntent(context, provider)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading,
                ) {
                    Text(oAuthProviderLabel(provider))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private fun oAuthProviderLabel(provider: String): String = when (provider.lowercase()) {
    "google" -> "Continue with Google"
    "github" -> "Continue with GitHub"
    "discord" -> "Continue with Discord"
    "facebook" -> "Continue with Facebook"
    "apple" -> "Continue with Apple"
    "openid" -> "Continue with OpenID"
    else -> "Continue with ${provider.replaceFirstChar { it.uppercase() }}"
}
