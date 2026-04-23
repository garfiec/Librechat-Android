package com.garfiec.librechat.feature.settings.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.settings.screen.FavoritesScreen
import kotlinx.serialization.Serializable

@Serializable data object Favorites : SettingsRoute

fun EntryProviderScope<NavKey>.favoritesEntry(
    onBack: () -> Unit,
) {
    entry<Favorites> {
        FavoritesScreen(
            onNavigateBack = onBack,
        )
    }
}
