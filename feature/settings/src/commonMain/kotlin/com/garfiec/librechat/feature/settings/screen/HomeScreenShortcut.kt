package com.garfiec.librechat.feature.settings.screen

import androidx.compose.runtime.Composable

/**
 * Returns an action that disables a pinned home-screen shortcut by id. Android has no API to remove an
 * already-placed launcher icon, so disabling is the most we can do: a lingering icon becomes inert and
 * tapping it shows [message]. No-op on platforms without pinned shortcuts (iOS).
 */
@Composable
expect fun rememberDisableHomeScreenShortcut(): (id: String, message: String) -> Unit
