package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.ui.media.rememberShareFile
import com.garfiec.librechat.feature.chat.components.artifact.Artifact
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactButton
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.components.artifact.LocalOpenArtifact
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.attachment_fallback
import com.garfiec.librechat.feature.chat.resources.cd_open_pdf
import com.garfiec.librechat.feature.chat.resources.pdf_document
import com.garfiec.librechat.feature.chat.util.resolveAttachmentUrl
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// ─── ToolCallAttachments ────────────────────────────────────────────
//
// Renders the files a tool call generated (code-interpreter output, etc.) below its card,
// matching the web client which passes each tool call's attachments to every tool component.
// The data already arrives via SSE `attachment` events / persisted `message.attachments`; this
// is the render layer.

/**
 * The three ways a generated file is surfaced, after filtering to one tool call and skipping
 * the non-file pseudo-attachments. Order within a partition is the attachments' arrival order.
 */
internal sealed interface ToolAttachment {
    val attachment: Attachment

    /** Bitmap output (e.g. a matplotlib PNG) — inline preview, tap for fullscreen. */
    data class Image(override val attachment: Attachment) : ToolAttachment

    /** Text-bearing source/doc the server extracted (`.py`, `.md`, `.html`, `.mmd`, …) —
     *  opens readably in the artifacts panel. */
    data class ArtifactContent(override val attachment: Attachment, val artifact: Artifact) : ToolAttachment

    /** A PDF (with a downloadable fileId) — a card that opens the native full-screen preview. */
    data class Pdf(override val attachment: Attachment) : ToolAttachment

    /** Anything else (CSV, binary) — a download chip that shares the bytes on tap. */
    data class File(override val attachment: Attachment) : ToolAttachment
}

/**
 * Attachment `type` values that are NOT files — they carry structured payloads (search sources,
 * memory artifacts, MCP UI resources) that mobile renders from tool *output* elsewhere. They must
 * never render as a file chip. Mirrors upstream `TAttachmentMetadata` (schemas.ts).
 */
private val PSEUDO_ATTACHMENT_TYPES = setOf("web_search", "file_search", "memory", "ui_resources")

/** Sandbox placeholder leaves the backend injects to keep empty dirs; never shown. Upstream
 *  `SANDBOX_PLACEHOLDER_LEAVES` (attachmentTypes.ts). */
private val SANDBOX_PLACEHOLDER = Regex("""^_\.(?:dirkeep|gitkeep)-[0-9a-f]{6}$""", RegexOption.IGNORE_CASE)

/** Sanitized-dotfile name the sandbox produces (`_.config-abcdef.txt`); shown as `.config.txt`.
 *  Upstream `SANITIZED_DOTFILE_PATTERN`. */
private val SANITIZED_DOTFILE = Regex("""^_\.(.+?)-[0-9a-f]{6}(\.[^.]+)?$""", RegexOption.IGNORE_CASE)

/** Extensions rendered as syntax-highlighted code (fenced markdown) in the artifacts panel. */
private val CODE_EXTENSIONS = setOf(
    "py", "js", "jsx", "ts", "tsx", "kt", "kts", "java", "c", "cc", "cpp", "h", "hpp",
    "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "sql", "json", "yaml", "yml",
    "xml", "css", "scss", "r", "pl", "lua", "dart", "scala", "groovy", "gradle", "toml", "ini",
)

/**
 * Splits a message's [attachments] to the given [toolCallId] into renderable buckets, skipping
 * pseudo-types, office-preview docs (rendered by `OfficePreviewAttachments`), and sandbox
 * placeholders. Deduped by `fileId ?: filename`. Pure — unit-tested.
 */
internal fun partitionToolCallAttachments(
    attachments: List<Attachment>,
    toolCallId: String?,
): List<ToolAttachment> {
    if (toolCallId == null) return emptyList()
    val seen = HashSet<String>()
    return attachments.asSequence()
        .filter { it.toolCallId == toolCallId }
        .filter { it.type !in PSEUDO_ATTACHMENT_TYPES }
        .filter { !ArtifactType.isOfficePreviewMime(it.type) }
        .filter { att ->
            val name = attachmentName(att)
            name == null || !SANDBOX_PLACEHOLDER.matches(name)
        }
        .filter { att ->
            val key = att.fileId ?: attachmentName(att) ?: return@filter false
            seen.add(key)
        }
        .map { att ->
            when {
                att.type?.startsWith("image/") == true -> ToolAttachment.Image(att)
                // A PDF needs its downloadable fileId to preview; without one it stays a file chip.
                isPdf(att) && att.fileId != null -> ToolAttachment.Pdf(att)
                else -> attachmentToArtifact(att)?.let { ToolAttachment.ArtifactContent(att, it) }
                    ?: ToolAttachment.File(att)
            }
        }
        .toList()
}

private fun isPdf(attachment: Attachment): Boolean =
    attachment.type == "application/pdf" ||
        attachmentName(attachment)?.endsWith(".pdf", ignoreCase = true) == true

/** How far into the bytes to scan for the PDF header; some servers prepend a BOM or whitespace. */
private const val PDF_HEADER_SCAN_LIMIT = 1024

/** The PDF magic number, `%PDF-`. */
private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)

/**
 * True if [bytes] contains the PDF header (`%PDF-`) within the first [PDF_HEADER_SCAN_LIMIT] bytes.
 * Guards the native parsers against non-PDF bodies that arrive with a 200 status (e.g. an HTML error
 * page), which would otherwise blank the Android surface or crash iOS's failable initializer.
 * Pure — unit-tested.
 */
internal fun looksLikePdf(bytes: ByteArray): Boolean {
    if (bytes.size < PDF_MAGIC.size) return false
    val lastStart = minOf(bytes.size, PDF_HEADER_SCAN_LIMIT) - PDF_MAGIC.size
    for (start in 0..lastStart) {
        var matched = true
        for (j in PDF_MAGIC.indices) {
            if (bytes[start + j] != PDF_MAGIC[j]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}

private fun attachmentName(attachment: Attachment): String? =
    attachment.filename ?: attachment.filepath?.substringAfterLast('/')

/**
 * Cleans a sandbox-sanitized filename for display: `_.config-abcdef.txt` → `.config.txt`,
 * `_.dirkeep-88b30b` → `.dirkeep`. Ordinary names (incl. `report-deadbe.csv`) pass through.
 * Upstream `getDisplayFileName`. Pure — unit-tested.
 */
internal fun displayFilename(name: String): String {
    val match = SANITIZED_DOTFILE.matchEntire(name) ?: return name
    return ".${match.groupValues[1]}${match.groupValues[2]}"
}

/**
 * Mobile equivalent of upstream `fileToArtifact`: maps a text-bearing attachment to an [Artifact]
 * the existing `ArtifactPanel` can render, or `null` to fall back to a download chip. Content is the
 * server-extracted `attachment.text`; a blank/absent text can't be rendered (mobile has no deferred
 * preview polling), so it falls back. Code files are wrapped in a fenced block so the markdown
 * artifact renderer highlights them. Pure — unit-tested.
 */
internal fun attachmentToArtifact(attachment: Attachment): Artifact? {
    val text = attachment.text
    if (text.isNullOrBlank()) return null
    val name = attachmentName(attachment) ?: return null
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return null

    val type: String
    val content: String
    val language: String?
    when {
        ext == "html" || ext == "htm" -> {
            type = "text/html"; content = text; language = "html"
        }
        ext == "md" || ext == "mdx" || ext == "markdown" -> {
            type = "text/markdown"; content = text; language = "markdown"
        }
        ext == "mmd" || ext == "mermaid" -> {
            type = "application/vnd.mermaid"; content = text; language = "mermaid"
        }
        ext == "txt" -> {
            type = "text/plain"; content = text; language = null
        }
        ext in CODE_EXTENSIONS -> {
            type = "text/markdown"; content = "```$ext\n$text\n```"; language = ext
        }
        else -> return null
    }
    return Artifact(
        identifier = attachment.fileId ?: name,
        type = type,
        title = displayFilename(name),
        language = language,
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolCallAttachments(
    attachments: List<Attachment>,
    toolCallId: String?,
    baseUrl: String,
    modifier: Modifier = Modifier,
) {
    val items = remember(attachments, toolCallId) {
        partitionToolCallAttachments(attachments, toolCallId)
    }
    if (items.isEmpty()) return

    val openArtifact = LocalOpenArtifact.current
    val openPdf = LocalOpenPdf.current
    val fallbackName = stringResource(Res.string.attachment_fallback)
    val images = items.filterIsInstance<ToolAttachment.Image>()
    val artifacts = items.filterIsInstance<ToolAttachment.ArtifactContent>()
    val pdfs = items.filterIsInstance<ToolAttachment.Pdf>()
    val files = items.filterIsInstance<ToolAttachment.File>()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { item ->
            val url = resolveAttachmentUrl(item.attachment, baseUrl)
            if (url != null) {
                MessageImagePreview(
                    imageUrl = url,
                    altText = item.attachment.filename?.let { displayFilename(it) } ?: fallbackName,
                )
            }
        }

        artifacts.forEach { item ->
            ArtifactButton(
                artifact = item.artifact,
                onClick = { openArtifact?.invoke(item.artifact, listOf(item.artifact)) },
            )
        }

        pdfs.forEach { item ->
            val fileId = item.attachment.fileId ?: return@forEach
            val name = attachmentName(item.attachment)?.let { displayFilename(it) } ?: fallbackName
            PdfAttachmentCard(
                title = name,
                onClick = { openPdf(fileId, name) },
            )
        }

        if (files.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                files.forEach { item ->
                    AttachmentDownloadChip(attachment = item.attachment)
                }
            }
        }
    }
}

/** Cached per core/ui Compose perf rule 13 (don't allocate `RoundedCornerShape` per composition). */
private val PDF_CARD_SHAPE = RoundedCornerShape(12.dp)

/**
 * A card for a generated PDF, styled like [ArtifactButton] (icon + title + "PDF Document" subtitle
 * + open affordance). Tapping opens the native full-screen preview via [LocalOpenPdf] — the
 * download-on-open + rendering happen there, mirroring how the web client opens PDFs in a viewer
 * rather than forcing a download.
 */
@Composable
private fun PdfAttachmentCard(title: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = PDF_CARD_SHAPE,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.pdf_document),
                    style = MaterialTheme.typography.bodySmall,
                    // Opaque theme role rather than copy(alpha=…) per core/ui Compose perf rule 15.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(Res.string.cd_open_pdf),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A file chip that downloads (authenticated) the attachment's bytes on tap and hands them to the
 * platform share sheet, showing a spinner while in flight. Reuses [FileChip]; the download goes
 * through [LocalAttachmentDownloader].
 */
@Composable
private fun AttachmentDownloadChip(attachment: Attachment) {
    val downloader = LocalAttachmentDownloader.current
    val shareFile = rememberShareFile()
    val scope = rememberCoroutineScope()
    var isDownloading by remember(attachment.fileId, attachment.filepath) { mutableStateOf(false) }

    val rawName = attachmentName(attachment)
    val displayName = rawName?.let { displayFilename(it) }
    val fileId = attachment.fileId

    FileChip(
        filename = displayName ?: stringResource(Res.string.attachment_fallback),
        type = attachment.type,
        isLoading = isDownloading,
        onClick = if (fileId != null && !isDownloading) {
            {
                scope.launch {
                    isDownloading = true
                    val bytes = downloader(fileId)
                    isDownloading = false
                    if (bytes != null) {
                        shareFile(bytes, displayName ?: fileId, attachment.type)
                    }
                }
            }
        } else {
            null
        },
    )
}
