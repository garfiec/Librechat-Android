package com.garfiec.librechat.core.data.datastore

/**
 * Mobile-only preference for where the v0.8.7 context-window usage gauge is surfaced.
 * Single-choice: the gauge appears in at most one location at a time.
 *
 * - [HIDDEN] — never shown.
 * - [ABOVE_INPUT] — a slim pill just above the composer (between the attachment/tool chips and
 *   the input row); tap opens the breakdown sheet.
 * - [OPTIONS_SHEET] — a full-width gauge at the top of the composer "+" tools sheet (above the
 *   model selector row) that expands the breakdown in place. The default.
 * - [OVERFLOW_MENU] — a gauge menu item in the chat top-bar overflow ("⋮") menu; tap opens the
 *   breakdown sheet.
 */
enum class ContextBarPlacement {
    HIDDEN, ABOVE_INPUT, OPTIONS_SHEET, OVERFLOW_MENU;

    companion object {
        fun fromString(value: String?): ContextBarPlacement = when (value) {
            "hidden" -> HIDDEN
            "above_input" -> ABOVE_INPUT
            "overflow_menu" -> OVERFLOW_MENU
            // Unset / unrecognized → the default placement.
            else -> OPTIONS_SHEET
        }
    }

    fun toStorageString(): String = when (this) {
        HIDDEN -> "hidden"
        ABOVE_INPUT -> "above_input"
        OPTIONS_SHEET -> "options_sheet"
        OVERFLOW_MENU -> "overflow_menu"
    }
}
