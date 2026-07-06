package com.garfiec.librechat.core.model

private const val OFFICE_PREVIEW_MIME_PREFIX = "application/vnd.librechat."
private const val OFFICE_PREVIEW_MIME_SUFFIX = "-preview"

/**
 * Type-string → representative emoji for a pinned artifact. Single-sourced here so the launcher icon
 * (:feature:chat) and the management-screen row (:feature:settings) can't drift apart for the same
 * snapshot. Mirrors the substring ladder in :feature:chat `ArtifactType.from`, including the
 * office-doc preview buckets that ship as HTML.
 */
fun artifactTypeGlyph(type: String): String = when {
    type.contains("mermaid") -> "📊"
    type.contains("react") -> "⚛️"
    type.contains("svg") -> "🖼️"
    type.contains("markdown") || type == "text/md" -> "📝"
    type.contains("html") -> "🌐"
    type.startsWith(OFFICE_PREVIEW_MIME_PREFIX) && type.endsWith(OFFICE_PREVIEW_MIME_SUFFIX) -> "🌐"
    type == "text/plain" -> "📄"
    else -> "💻"
}

/**
 * Type-string → human label, single-sourced alongside [artifactTypeGlyph] so the two never disagree
 * (e.g. an office-preview artifact shows the 🌐 glyph and the "HTML Page" label, not "Code"). Same
 * branch order as the glyph ladder and :feature:chat `ArtifactType.from`.
 */
fun artifactTypeLabel(type: String): String = when {
    type.contains("mermaid") -> "Mermaid Diagram"
    type.contains("react") -> "React Component"
    type.contains("svg") -> "SVG Image"
    type.contains("markdown") || type == "text/md" -> "Markdown Document"
    type.contains("html") -> "HTML Page"
    type.startsWith(OFFICE_PREVIEW_MIME_PREFIX) && type.endsWith(OFFICE_PREVIEW_MIME_SUFFIX) -> "HTML Page"
    type == "text/plain" -> "Plain Text"
    else -> "Code"
}

/** Glyph shown for a pinned shortcut: the user's emoji when set, otherwise the type default. */
val ArtifactShortcut.displayGlyph: String
    get() = emoji?.takeIf { it.isNotBlank() } ?: artifactTypeGlyph(type)

/** Label shown for a pinned shortcut: the user's label when set, otherwise the artifact title. */
val ArtifactShortcut.displayLabel: String
    get() = label.ifBlank { title }
