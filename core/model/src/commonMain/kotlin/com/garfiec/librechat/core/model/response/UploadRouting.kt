package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.common.BackendVersion
import com.garfiec.librechat.core.common.EndpointConstants

/**
 * How a composer attachment is delivered to the model.
 *
 * Both modes are the same multipart upload; only the `tool_resource` form field differs.
 * See `client/src/hooks/Files/useFileHandling.ts` upstream.
 */
enum class UploadRoute {
    /** Sent to the provider natively — no `tool_resource` field. This is what mobile has always done. */
    PROVIDER,

    /** `tool_resource=context` — the server extracts the document to text and injects it each turn. */
    TEXT,
}

/**
 * The `tool_resource` form field an upload in this mode carries; null for the native provider path,
 * where upstream sends no such field at all.
 */
fun UploadRoute.toolResource(): String? = when (this) {
    UploadRoute.PROVIDER -> null
    UploadRoute.TEXT -> "context"
}

/**
 * Providers that accept documents natively in the agent system.
 *
 * Verbatim mirror of `documentSupportedProviders` in upstream
 * `packages/data-provider/src/schemas.ts`, including its deliberate omissions.
 *
 * `azureOpenAI` is absent upstream because it only takes documents with `useResponsesApi`
 * enabled, which upstream handles in the two menu components rather than in the set. We could
 * read that flag (it surfaces as a dynamic model parameter), but choose not to model the corner
 * — an Azure document therefore routes to text, which works, rather than to a path that depends
 * on a per-agent toggle.
 *
 * This set is a hardcoded mirror because the server never serves it. It has changed roughly
 * every seven weeks and is net growing, so treat it as structurally behind the server: an
 * unknown name must fail toward [UploadRoute.PROVIDER], never toward text.
 */
private val DOCUMENT_SUPPORTED_PROVIDERS: Set<String> = setOf(
    "anthropic",
    "openAI",
    "bedrock",
    "custom",
    "google",
    "vertexai",
    "mistralai",
    "mistral",
    "deepseek",
    "moonshot",
    "openrouter",
    "xai",
)

/**
 * MIME types Bedrock accepts as native document blocks — the keys of upstream's
 * `bedrockDocumentFormats` (`packages/data-provider/src/file-config.ts`). The values there are
 * Converse API `format` strings the client never needs.
 */
private val BEDROCK_DOCUMENT_MIME_TYPES: Set<String> = setOf(
    "application/pdf",
    "text/csv",
    "application/csv",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/html",
    "text/plain",
    "text/markdown",
)

/**
 * Verbatim mirror of upstream's `mimeTypeAliases`. The server normalises these on receipt, so the
 * router must apply them *before* any lookup or `text/x-markdown` misses the Bedrock table.
 *
 * The two shell-script entries landed in v0.8.8-rc1 (upstream 5e464bc93078): Chrome on Linux
 * reports `.sh` as `application/x-shellscript` (freedesktop shared-mime-info) and libmagic as
 * `text/x-shellscript`. A PRE-rc1 server does not normalise them and rejects the raw type at
 * upload, so [normalizeMimeType] applies these two only when the detected server is ≥ 0.8.8-rc1
 * — see [SHELL_SCRIPT_MIME_ALIASES].
 */
private val MIME_TYPE_ALIASES: Map<String, String> = mapOf(
    "application/x-zip-compressed" to "application/zip",
    "text/x-python-script" to "text/x-python",
    "text/x-markdown" to "text/markdown",
    "application/x-shellscript" to "application/x-sh",
    "text/x-shellscript" to "application/x-sh",
)

/**
 * The [MIME_TYPE_ALIASES] keys whose aliasing is version-gated at ≥ 0.8.8-rc1. On an older or
 * UNKNOWN server the raw type passes through unaliased, restoring the pre-alias behaviour:
 * `application/x-shellscript` reads as an unknown application-tree type and fails toward
 * [UploadRoute.PROVIDER] (this file's doctrine for anything uncertain), while
 * `text/x-shellscript` keeps the text/-tree extractability it has always had — only its
 * canonical name changes with the gate, not its outcome.
 */
private val SHELL_SCRIPT_MIME_ALIASES: Set<String> = setOf(
    "application/x-shellscript",
    "text/x-shellscript",
)

/** First server version that normalises [SHELL_SCRIPT_MIME_ALIASES] on receipt. */
private const val SHELL_SCRIPT_ALIAS_MIN_VERSION = "0.8.8-rc1"

/**
 * The excel MIME variants upstream matches with `excelMimeTypes`, which is one leg of
 * `documentParserMimeTypes`.
 */
private val EXCEL_MIME_TYPES: Set<String> = setOf(
    "application/vnd.ms-excel",
    "application/msexcel",
    "application/x-msexcel",
    "application/x-ms-excel",
    "application/x-excel",
    "application/x-dos_ms_excel",
    "application/xls",
    "application/x-xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

/**
 * `documentParserMimeTypes` upstream — the types the built-in parser can turn into text without
 * any OCR service configured.
 *
 * Deliberately *not* gated on whether OCR is configured: that lives in server-side `appConfig` and
 * is exposed by no endpoint. It doesn't need to be, because upstream's own guard is
 * `shouldUseDocumentParser = !shouldUseConfiguredOCR && documentParserMimeTypes.some(...)`
 * (`api/server/services/Files/process.js`) — the parser is precisely the no-OCR fallback, so these
 * types extract either way.
 */
private val DOCUMENT_PARSER_MIME_TYPES: Set<String> = EXCEL_MIME_TYPES + setOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.text",
)

/**
 * Types outside the `text` tree whose bytes really are text, so upstream's `parseText` fallback — a raw UTF-8
 * `readFile` with no binary sniff — produces something a model can read.
 *
 * Everything absent from this set and from [DOCUMENT_PARSER_MIME_TYPES] stays on
 * [UploadRoute.PROVIDER] *even though upstream's `defaultTextMimeTypes` would accept it*: that
 * pattern is the single regex `^[\w.-]+/[\w.-]+$`, which matches every well-formed MIME type. A
 * zip or an mp4 routed to text is decoded as UTF-8, stored, token-counted and injected into every
 * turn — silent garbage, where the provider would have returned a visible error.
 */
private val TEXTUAL_APPLICATION_MIME_TYPES: Set<String> = setOf(
    "application/json",
    "application/xml",
    "application/yaml",
    "application/sql",
    "application/csv",
    "application/typescript",
    "application/x-sh",
    "application/vnd.coffeescript",
)

/**
 * Endpoints that name a *container* rather than a provider. On these, [resolveUploadRoute] can say
 * nothing until the real provider is resolved (for agents, by fetching the agent), so an
 * unresolved one must behave exactly as mobile does today: everything to the provider.
 */
private val OPAQUE_ENDPOINTS: Set<String> = setOf(
    EndpointConstants.AGENTS,
    "assistants",
    "azureAssistants",
)

/**
 * Strips MIME parameters (`text/plain; charset=utf-8`) and applies upstream's alias table.
 *
 * @param serverVersion the detected backend version, gating the aliases that only newer servers
 *   normalise ([SHELL_SCRIPT_MIME_ALIASES]). Null means unknown and fails toward NOT aliasing.
 */
private fun normalizeMimeType(mimeType: String?, serverVersion: String? = null): String? {
    val bare = mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (bare in SHELL_SCRIPT_MIME_ALIASES &&
        (serverVersion == null || !BackendVersion.isCompatibleOrNewer(serverVersion, SHELL_SCRIPT_ALIAS_MIN_VERSION))
    ) {
        return bare
    }
    return MIME_TYPE_ALIASES[bare] ?: bare
}

/**
 * Upstream lower-cases exactly one provider name before comparing (`DragDropModal.tsx`), with a
 * comment that comparisons should become case-insensitive some day. Copy that narrowly: a blanket
 * `lowercase()` would drop `openAI` out of [DOCUMENT_SUPPORTED_PROVIDERS].
 */
private fun canonicalProvider(name: String?): String? {
    val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (trimmed.lowercase() == "openrouter") "openrouter" else trimmed
}

/**
 * The provider identity the route is decided against, mirroring upstream's `provider || endpoint`.
 *
 * Returns null when it cannot be known — no endpoint at all, or an [OPAQUE_ENDPOINTS] entry whose
 * real provider hasn't been resolved. `agents` is the default endpoint on mobile
 * (`ModelSelectionState.selectedEndpoint`), and the agent *list* response omits `provider`
 * entirely, so this null case is reached routinely rather than exceptionally.
 */
private fun effectiveProvider(endpoint: String?, agentProvider: String?): String? {
    canonicalProvider(agentProvider)?.let { return it }
    val ep = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (OPAQUE_ENDPOINTS.any { it.equals(ep, ignoreCase = true) }) return null
    return canonicalProvider(ep)
}

/**
 * True when nothing in the current selection identifies the provider that will receive the file —
 * no endpoint at all, or an [OPAQUE_ENDPOINTS] entry whose agent provider has not resolved.
 *
 * [resolveUploadRoute] answers PROVIDER here, which is what mobile has always done. A *prompt*
 * cannot reuse that answer: "unknown" is not "the provider takes it natively and nothing else",
 * and presenting it as a settled single-mode file both states something this side cannot know and
 * withholds the choice the user asked to be given.
 */
fun isProviderUnknown(
    endpoint: String?,
    endpointType: String? = null,
    agentProvider: String? = null,
): Boolean = effectiveProvider(endpoint, agentProvider) == null &&
    canonicalProvider(endpointType) == null

private fun isDocumentSupported(name: String?): Boolean =
    name != null && name in DOCUMENT_SUPPORTED_PROVIDERS

/**
 * Whether the provider can take [mimeType] natively, mirroring `isValidProviderFile` in upstream's
 * `DragDropModal.tsx`.
 *
 * Note `supportsImageDocVideoAudio` upstream is `google || openrouter` only — vertexai is excluded
 * there even though the server's encoders do handle its video and audio. Under this router video
 * and audio never route to text anyway (see [TEXTUAL_APPLICATION_MIME_TYPES]), so the discrepancy
 * changes no outcome; it is mirrored rather than "fixed" so a sync diff stays clean.
 */
fun isProviderCapable(
    mimeType: String?,
    endpoint: String?,
    endpointType: String? = null,
    agentProvider: String? = null,
    serverVersion: String? = null,
): Boolean {
    val mime = normalizeMimeType(mimeType, serverVersion) ?: return false
    val provider = effectiveProvider(endpoint, agentProvider)
    val type = canonicalProvider(endpointType)

    if (!isDocumentSupported(provider) && !isDocumentSupported(type)) {
        // Non-document providers still take images natively, and nothing else.
        return mime.startsWith("image/")
    }

    val supportsImageDocVideoAudio = provider == "google" || provider == "openrouter"
    if (supportsImageDocVideoAudio) {
        return mime.startsWith("image/") ||
            mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            mime == "application/pdf"
    }

    val isBedrock = provider == "bedrock" || type == "bedrock"
    if (isBedrock) {
        return mime.startsWith("image/") || mime in BEDROCK_DOCUMENT_MIME_TYPES
    }

    return mime.startsWith("image/") || mime == "application/pdf"
}

/**
 * Whether the server can demonstrably turn [mimeType] into text — the built-in document parser's
 * types plus genuinely textual ones. Public because the manual picker has to know whether text is
 * a real second option for a file, not just whether the router happened to choose it.
 *
 * This is a hardcoded mirror and will be wrong for an admin who narrowed
 * `fileConfig.text.supportedMimeTypes`: `GET /api/files/config` returns their raw block, the
 * built-in defaults are `RegExp` arrays that cannot cross JSON, and `mergeFileConfig` only ever
 * runs server-side. Such a file routes to text and comes back as a generic 500.
 */
fun isTextExtractable(mimeType: String?, serverVersion: String? = null): Boolean {
    val mime = normalizeMimeType(mimeType, serverVersion) ?: return false
    return mime.startsWith("text/") ||
        mime in DOCUMENT_PARSER_MIME_TYPES ||
        mime in TEXTUAL_APPLICATION_MIME_TYPES
}

/**
 * Picks the delivery mode for one attachment.
 *
 * The rule in one line: **text only where the provider can't take the file natively and the server
 * can demonstrably extract it.** Everything else — unknown provider, unknown type, zip, pptx,
 * epub, video, audio — stays on [UploadRoute.PROVIDER], which is byte-for-byte what mobile does
 * today. That makes this change a strict superset of current behaviour: the only files whose
 * treatment changes are ones the provider would have rejected or silently dropped.
 *
 * @param endpointType the `type` of a custom endpoint (`"custom"`), where [endpoint] is only the
 *   admin's display label ("Groq", "LiteLLM") and carries no provider meaning.
 * @param agentProvider the selected agent's `provider`, which must come from `GET /api/agents/:id`
 *   — the list response omits the field.
 */
fun resolveUploadRoute(
    mimeType: String?,
    endpoint: String?,
    endpointType: String? = null,
    agentProvider: String? = null,
    serverVersion: String? = null,
): UploadRoute {
    val mime = normalizeMimeType(mimeType, serverVersion) ?: return UploadRoute.PROVIDER

    // An unresolvable provider means we'd be guessing; today's behaviour is the safe guess.
    if (isProviderUnknown(endpoint, endpointType, agentProvider)) return UploadRoute.PROVIDER

    if (isProviderCapable(mime, endpoint, endpointType, agentProvider, serverVersion)) return UploadRoute.PROVIDER
    return if (isTextExtractable(mime, serverVersion)) UploadRoute.TEXT else UploadRoute.PROVIDER
}
