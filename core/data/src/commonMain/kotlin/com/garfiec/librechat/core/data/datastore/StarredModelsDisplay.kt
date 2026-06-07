package com.garfiec.librechat.core.data.datastore

/**
 * Mobile-only preference controlling how pinned ("starred") models and agents are
 * surfaced in the model-selection bottom sheet.
 *
 * - [OFF] — no dedicated section; favorites simply float to the top within their own
 *   provider/agent group (the historical behavior).
 * - [GROUPED] — a collapsible "Starred" group at the top of the sheet, styled like the
 *   provider groups. Items still also appear in their original groups.
 * - [TOP] — starred items listed flat at the very top, no header or collapse. Items still
 *   also appear in their original groups.
 */
enum class StarredModelsDisplay {
    OFF, GROUPED, TOP;

    companion object {
        fun fromString(value: String?): StarredModelsDisplay = when (value) {
            "grouped" -> GROUPED
            "top" -> TOP
            else -> OFF
        }
    }

    fun toStorageString(): String = when (this) {
        OFF -> "off"
        GROUPED -> "grouped"
        TOP -> "top"
    }
}
