package com.garfiec.librechat.feature.chat.components.artifact

/**
 * Parsed artifact extracted from message content. Artifacts are marked in LLM
 * responses using the remark-directive markdown format:
 *
 * ```
 * :::artifact{identifier="id" type="mime-type" title="Title"}
 * ```language
 * ...content...
 * ```
 * :::
 * ```
 *
 * Supported types: text/html, image/svg+xml, application/vnd.react,
 * application/vnd.mermaid, text/markdown, text/md, text/plain,
 * application/vnd.code-html.
 */
data class Artifact(
    val identifier: String,
    val type: String,
    val title: String,
    val language: String?,
    val content: String,
    val version: Int = 1,
    /**
     * False when the artifact's closing `:::` never arrived — a reply truncated mid-artifact, or a
     * live stream still in flight. Such artifacts render their **source** rather than being
     * executed; see [IncompleteArtifact]'s KDoc for why that outranks the inline preferences.
     */
    val isComplete: Boolean = true,
)
