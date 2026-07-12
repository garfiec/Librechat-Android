package com.garfiec.librechat.feature.conversations.drawer

/**
 * The drawer "Library" section's two modes: the recents history vs. the project folders. Persisted
 * so the choice survives app restarts. Stored strings are decoupled from the constant names so
 * renaming a constant can't orphan a saved preference, and a future third tab is an added case here
 * rather than a storage-schema change.
 */
enum class DrawerTab {
    Chats,
    Projects;

    companion object {
        fun fromString(value: String?): DrawerTab = when (value) {
            "projects" -> Projects
            else -> Chats
        }
    }

    fun toStorageString(): String = when (this) {
        Chats -> "chats"
        Projects -> "projects"
    }
}
