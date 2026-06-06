package com.garfiec.librechat.feature.chat.components.artifact

/**
 * Classification of an artifact MIME type into the renderer/icon families the
 * UI knows about. Centralises the substring-matching ladder that used to live
 * in `ArtifactButton`, `ArtifactPanel.isPreviewableType`, `ArtifactWebContent`,
 * and `InlineArtifactPrefs.shouldRenderInline` — all four sites now classify
 * via [from] and switch on the resulting enum.
 *
 * Matching order matters: `application/vnd.code-html` must be classified as
 * HTML, not CODE. The [from] cases are ordered so the most-specific tokens
 * win first.
 *
 * Office-doc preview buckets (`application/vnd.librechat.{docx,spreadsheet,
 * presentation}-preview`) are sanitized full-document HTML the backend ships
 * via `attachment.text`; upstream loads them as a static `index.html`
 * artifact (`client/src/utils/artifacts.ts`). They contain none of the
 * substrings the ladder below matches on, so without an explicit branch they
 * fall through to [CODE] and render as raw source — hence the dedicated
 * `-preview` check.
 */
enum class ArtifactType {
    MERMAID,
    REACT,
    SVG,
    MARKDOWN,
    HTML,
    PLAIN,
    CODE;

    companion object {
        /** Shared office-doc preview MIME bucket pattern (`application/vnd.librechat.{docx,
         *  spreadsheet,presentation}-preview`). Single-sourced so the matcher + the
         *  fallback MIME never drift. */
        const val OFFICE_PREVIEW_MIME_PREFIX = "application/vnd.librechat."
        const val OFFICE_PREVIEW_MIME_SUFFIX = "-preview"

        /** Fallback office-preview MIME when an attachment omits its type (docx bucket). */
        const val DEFAULT_OFFICE_PREVIEW_MIME = "${OFFICE_PREVIEW_MIME_PREFIX}docx$OFFICE_PREVIEW_MIME_SUFFIX"

        /**
         * True for the office-doc preview MIME buckets (docx/spreadsheet/
         * presentation). These ride the deferred-preview attachment flow: the
         * backend ships sanitized HTML via `attachment.text` and the mobile client
         * renders it as a static HTML artifact. Used to route office attachments to
         * the preview card without touching ordinary image/tool attachments.
         */
        fun isOfficePreviewMime(mime: String?): Boolean =
            mime != null &&
                mime.startsWith(OFFICE_PREVIEW_MIME_PREFIX) &&
                mime.endsWith(OFFICE_PREVIEW_MIME_SUFFIX)

        fun from(mime: String): ArtifactType = when {
            mime.contains("mermaid") -> MERMAID
            mime.contains("react") -> REACT
            mime.contains("svg") -> SVG
            mime.contains("markdown") || mime == "text/md" -> MARKDOWN
            mime.contains("html") -> HTML
            // Office-doc preview buckets ship sanitized HTML → render as HTML.
            isOfficePreviewMime(mime) -> HTML
            mime == "text/plain" -> PLAIN
            else -> CODE
        }
    }
}
