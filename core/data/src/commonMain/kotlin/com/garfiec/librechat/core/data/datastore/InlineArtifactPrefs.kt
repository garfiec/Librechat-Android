package com.garfiec.librechat.core.data.datastore

/**
 * Per-type toggles for rendering message artifacts inline in the chat (instead
 * of a tap-to-expand button). All types default to off; users opt in via
 * Settings > Chat > Artifacts.
 *
 * The MIME → field mapping lives in `feature/chat` as
 * `InlineArtifactPrefs.shouldRenderInline(type)` to keep this class
 * presentation-agnostic.
 */
data class InlineArtifactPrefs(
    val mermaid: Boolean = false,
    val svg: Boolean = false,
    val html: Boolean = false,
    val react: Boolean = false,
    val markdown: Boolean = false,
) {
    val enabledCount: Int
        get() = (if (mermaid) 1 else 0) + (if (svg) 1 else 0) + (if (html) 1 else 0) +
            (if (react) 1 else 0) + (if (markdown) 1 else 0)

    companion object {
        const val FIELD_COUNT = 5
    }
}
