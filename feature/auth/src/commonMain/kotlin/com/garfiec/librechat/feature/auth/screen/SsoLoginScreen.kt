package com.garfiec.librechat.feature.auth.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.feature.auth.viewmodel.SsoLoginViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SsoLoginScreen(
    provider: String,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: SsoLoginViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnLoginSuccess by rememberUpdatedState(onLoginSuccess)

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            currentOnLoginSuccess()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SsoWebView(
            serverUrl = viewModel.serverUrl,
            provider = provider,
            onTokenCaptured = viewModel::onTokenCaptured,
            onOAuthError = viewModel::onOAuthError,
            modifier = Modifier.fillMaxSize(),
        )

        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        if (uiState.error != null && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.padding(top = 16.dp))
                    Button(onClick = { onBack?.invoke() }) {
                        Text("Back to sign-in")
                    }
                }
            }
        }

        BackAffordanceOverlay(onBack)
    }
}