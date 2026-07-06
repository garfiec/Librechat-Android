package com.garfiec.librechat.feature.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.ShortcutManagerCompat

@Composable
actual fun rememberDisableHomeScreenShortcut(): (id: String, message: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { id, message ->
            ShortcutManagerCompat.disableShortcuts(context, listOf(id), message)
        }
    }
}
