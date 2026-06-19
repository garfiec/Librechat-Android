package com.garfiec.librechat.core.data.datastore

/**
 * Preferences controlling how the artifact viewer presents content. Configured
 * via Settings > Chat > Artifacts.
 *
 * - [mode] — bottom sheet vs. full-screen presentation. Artifacts open preview-first;
 *   source is reached via the viewer's source/preview toggle button.
 */
data class ArtifactDisplayPrefs(
    val mode: ArtifactDisplayMode = ArtifactDisplayMode.BOTTOM_SHEET,
)
