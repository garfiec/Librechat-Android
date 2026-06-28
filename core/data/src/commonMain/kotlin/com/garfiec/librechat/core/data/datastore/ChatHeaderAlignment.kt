package com.garfiec.librechat.core.data.datastore

/**
 * Mobile-only preference controlling how the chat floating top bar's bubble is positioned
 * within the region between the hamburger button and the overflow menu.
 *
 * - [LEFT] — the bubble hugs its content next to the hamburger (the historical behavior).
 * - [CENTER] — the bubble hugs its content, centered in the available region.
 * - [FILL] — the bubble expands to fill the width between the hamburger and the overflow menu.
 */
enum class ChatHeaderAlignment {
    LEFT, CENTER, FILL;

    companion object {
        fun fromString(value: String?): ChatHeaderAlignment = when (value) {
            "center" -> CENTER
            "fill" -> FILL
            else -> LEFT
        }
    }

    fun toStorageString(): String = when (this) {
        LEFT -> "left"
        CENTER -> "center"
        FILL -> "fill"
    }
}
