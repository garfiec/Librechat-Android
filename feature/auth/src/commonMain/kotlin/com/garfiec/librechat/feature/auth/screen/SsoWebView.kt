package com.garfiec.librechat.feature.auth.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Embedded WebView that runs the full OAuth round-trip for [provider] against [serverUrl].
 *
 *  Loads `{serverUrl}/api/oauth/{provider}` with a browser-like User-Agent (the server's
 *  ua-parser middleware 403s WebView agents). On every page event it checks the native cookie
 *  store for the server's `refreshToken` cookie — the OAuth callback completes on the server's
 *  own origin and sets it — and reports it via [onTokenCaptured]. A redirect carrying an
 *  `error` query parameter (the server's OAuth failure landing page) reports [onOAuthError]. */
@Composable
expect fun SsoWebView(
    serverUrl: String,
    provider: String,
    onTokenCaptured: (String) -> Unit,
    onOAuthError: (String?) -> Unit,
    modifier: Modifier = Modifier,
)