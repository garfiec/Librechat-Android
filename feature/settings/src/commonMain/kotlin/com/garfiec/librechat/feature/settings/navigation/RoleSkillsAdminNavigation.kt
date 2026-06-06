package com.garfiec.librechat.feature.settings.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.garfiec.librechat.feature.settings.screen.RoleSkillsAdminScreen
import kotlinx.serialization.Serializable

@Serializable data object RoleSkillsAdmin : SettingsRoute

fun EntryProviderScope<NavKey>.roleSkillsAdminEntry(
    onBack: () -> Unit,
) {
    entry<RoleSkillsAdmin> {
        RoleSkillsAdminScreen(
            onNavigateBack = onBack,
        )
    }
}
