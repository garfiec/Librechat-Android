package com.garfiec.librechat.feature.chat.components.artifact

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition-scoped opener for an artifact viewer. Provided once at the chat/media
 * navigation entry, which owns the screen-level presentation: it reads the display-mode
 * pref and either pushes the full-screen `ArtifactFullscreen` route or shows a screen-root
 * bottom sheet. Call sites deep in the message list (artifact buttons/inline views) just
 * fire this event with the tapped artifact and its version history.
 *
 * Null when no navigation host is in scope (e.g. previews/tests); callers no-op the tap.
 */
val LocalOpenArtifact =
    staticCompositionLocalOf<((Artifact, List<Artifact>) -> Unit)?> { null }
