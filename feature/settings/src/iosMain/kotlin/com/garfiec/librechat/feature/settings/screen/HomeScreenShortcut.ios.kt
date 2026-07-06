package com.garfiec.librechat.feature.settings.screen

import androidx.compose.runtime.Composable

// iOS has no pinned home-screen shortcuts, so there is nothing to disable.
@Composable
actual fun rememberDisableHomeScreenShortcut(): (id: String, message: String) -> Unit = { _, _ -> }
