package com.garfiec.librechat.feature.settings.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.settings.screen.ArtifactShortcutsScreen
import kotlinx.serialization.Serializable

@Serializable data object ArtifactShortcuts : SettingsRoute

fun EntryProviderScope<NavKey>.artifactShortcutsEntry(
    onBack: () -> Unit,
) {
    entry<ArtifactShortcuts> {
        ArtifactShortcutsScreen(
            onNavigateBack = onBack,
        )
    }
}
