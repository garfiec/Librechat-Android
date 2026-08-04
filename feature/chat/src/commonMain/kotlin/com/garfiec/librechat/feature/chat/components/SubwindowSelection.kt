package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable

/**
 * Selection scope for message content that renders in its own composition owner — a `Dialog` or
 * `Popup` opened from inside a message.
 *
 * CompositionLocals cross that boundary but layout hierarchies do not, so without a scope of its
 * own the subwindow's text registers with the *message's* selection registrar (provided by the
 * `SelectionContainer` in [MessageContentAndActions]) while its layout nodes live in another
 * window. One selection then spans two hierarchies: "Select all" emits the subwindow's text a
 * second time, and resolving a position across the two owners fails outright — `localPositionOf`
 * rejects nodes that share no ancestor.
 *
 * Its own container keeps the subwindow's text selectable as a separate selection.
 */
@Composable
internal fun SubwindowSelectionContainer(content: @Composable () -> Unit) {
    SelectionContainer(content = content)
}
