package com.garfiec.librechat.shared.navigation

sealed interface SidebarMode {
    data object Conversations : SidebarMode
    data object Settings : SidebarMode
}

/**
 * Settings categories shown as a navigation menu in the sidebar.
 * Each maps to a dedicated settings sub-page in the main content area.
 */
enum class SettingsCategory(val label: String) {
    GENERAL("General"),
    CHAT("Chat"),
    ACCOUNT("Account"),
    DATA("Data"),
}
