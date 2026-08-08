package com.garfiec.librechat.feature.settings.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.settings.screen.PrefetchActivityScreen
import kotlinx.serialization.Serializable

/** Detail behind the background-prefetch summary in Data settings. */
@Serializable data object PrefetchActivity : SettingsRoute

fun EntryProviderScope<NavKey>.prefetchActivityEntry(onBack: () -> Unit) {
    entry<PrefetchActivity> {
        PrefetchActivityScreen(onNavigateBack = onBack)
    }
}
