package com.garfiec.librechat.feature.settings.util

/**
 * Hands [uri] to whatever app registered its scheme, returning false when nothing can handle it.
 *
 * Used for `otpauth://` enrollment links: every major authenticator (Google Authenticator, Authy,
 * 1Password, Bitwarden, Microsoft Authenticator, Aegis) registers the scheme, so one tap adds the
 * account with issuer and label pre-filled. The false return is the contract that matters -- the
 * caller must fall back to manual entry rather than leaving the user on a dead button.
 */
expect fun openUri(uri: String): Boolean
