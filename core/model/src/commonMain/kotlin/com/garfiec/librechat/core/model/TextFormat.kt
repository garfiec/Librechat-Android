package com.garfiec.librechat.core.model

/**
 * Values of the deferred-preview `textFormat` field (`TFile.textFormat` /
 * `FilePreviewResponse.textFormat`, v0.8.6).
 *
 * SECURITY: [HTML] is the ONLY value for which extracted preview `text` may be
 * injected as live HTML — the backend produced a sanitized full document. [TEXT]
 * (or null/any other value) is plain text and MUST be escaped, never injected.
 * Naming the gate value keeps the load-bearing `textFormat == HTML` check
 * self-documenting and grep-able.
 */
object TextFormat {
    const val HTML = "html"
    const val TEXT = "text"
}
