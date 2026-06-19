package com.garfiec.librechat.core.data.datastore

/**
 * Mobile-only preference controlling how the artifact viewer is presented when a
 * user opens an artifact from a message.
 *
 * - [BOTTOM_SHEET] — a modal bottom sheet (the historical default).
 * - [FULLSCREEN] — a full-screen page, giving complex artifacts (React apps,
 *   HTML dashboards, diagrams) the maximum available room.
 */
enum class ArtifactDisplayMode {
    BOTTOM_SHEET, FULLSCREEN;

    companion object {
        fun fromString(value: String?): ArtifactDisplayMode = when (value) {
            "fullscreen" -> FULLSCREEN
            else -> BOTTOM_SHEET
        }
    }

    fun toStorageString(): String = when (this) {
        BOTTOM_SHEET -> "bottom_sheet"
        FULLSCREEN -> "fullscreen"
    }
}
