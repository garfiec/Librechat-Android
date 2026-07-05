package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.garfiec.librechat.core.data.datastore.InlineArtifactPrefs
import com.garfiec.librechat.core.ui.media.MediaActionBar
import com.garfiec.librechat.core.ui.media.MediaPreviewState
import com.garfiec.librechat.core.ui.media.ZoomableMediaPager
import com.garfiec.librechat.core.ui.media.rememberSaveImageToGallery
import com.garfiec.librechat.core.ui.media.rememberShareImage
import com.garfiec.librechat.feature.chat.components.artifact.LocalInlineArtifactPrefs
import com.garfiec.librechat.feature.chat.components.artifact.LocalMermaidRenderCache
import com.garfiec.librechat.feature.chat.components.artifact.MermaidRenderCache
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_close
import com.garfiec.librechat.feature.chat.resources.cd_image
import com.garfiec.librechat.feature.chat.resources.cd_save_to_device
import com.garfiec.librechat.feature.chat.resources.cd_share_image
import com.garfiec.librechat.feature.chat.viewmodel.SubagentTrace
import org.jetbrains.compose.resources.stringResource

/**
 * Wraps the chat screen content with all chat-scoped CompositionLocals so that
 * each platform `Scaffold` doesn't repeat the provider list. Also hosts the full-screen
 * zoomable media viewer overlay (driven by [mediaPreview]) so both platforms share one wiring.
 */
@Composable
fun ChatRoot(
    inlineArtifactPrefs: InlineArtifactPrefs,
    mermaidRenderCache: MermaidRenderCache,
    parsedMarkdownCache: ParsedMarkdownCache,
    subagentProgress: Map<String, SubagentTrace>,
    mediaPreview: MediaPreviewState?,
    onOpenMedia: (url: String) -> Unit,
    onCloseMedia: () -> Unit,
    onDownloadAttachment: suspend (fileId: String) -> ByteArray?,
    content: @Composable () -> Unit,
) {
    // Hosted here (not in the VM) so the tapped PDF card can scroll off-screen without dismissing
    // the viewer. rememberSaveable so the open preview survives configuration changes (rotation);
    // the opener just records the request and the overlay re-downloads + renders below.
    var pdfRequest by rememberSaveable(stateSaver = PdfRequestSaver) { mutableStateOf<PdfRequest?>(null) }
    val openPdf = remember { { fileId: String, filename: String -> pdfRequest = PdfRequest(fileId, filename) } }

    CompositionLocalProvider(
        LocalInlineArtifactPrefs provides inlineArtifactPrefs,
        LocalMermaidRenderCache provides mermaidRenderCache,
        LocalParsedMarkdownCache provides parsedMarkdownCache,
        LocalSubagentProgress provides subagentProgress,
        LocalChatMediaViewer provides onOpenMedia,
        LocalAttachmentDownloader provides onDownloadAttachment,
        LocalOpenPdf provides openPdf,
    ) {
        content()

        // Remembered above the `if` so the save/share coroutine scope (and the permission
        // launcher) live as long as the chat screen, not just while the viewer is open — a
        // save/share in flight survives the user dismissing the viewer mid-operation.
        val saveImage = rememberSaveImageToGallery()
        val shareImage = rememberShareImage()
        if (mediaPreview != null) {
            // Resolved once here (not inside the per-item actions slot) so paging doesn't
            // re-resolve string resources on every swipe.
            val saveDescription = stringResource(Res.string.cd_save_to_device)
            val shareDescription = stringResource(Res.string.cd_share_image)
            val imageDescription = stringResource(Res.string.cd_image)
            ZoomableMediaPager(
                items = mediaPreview.items,
                initialIndex = mediaPreview.initialIndex,
                onDismiss = onCloseMedia,
                closeContentDescription = stringResource(Res.string.cd_close),
                defaultContentDescription = imageDescription,
                actions = { item ->
                    MediaActionBar(
                        item = item,
                        onSave = saveImage,
                        onShare = shareImage,
                        saveContentDescription = saveDescription,
                        shareContentDescription = shareDescription,
                    )
                },
            )
        }

        pdfRequest?.let { req ->
            PdfPreviewOverlay(
                fileId = req.fileId,
                filename = req.filename,
                onDownload = onDownloadAttachment,
                onDismiss = { pdfRequest = null },
            )
        }
    }
}

/** The file the PDF preview overlay should open. */
private data class PdfRequest(val fileId: String, val filename: String)

/** Saves [PdfRequest] across configuration changes as a `[fileId, filename]` pair (empty = null). */
private val PdfRequestSaver = listSaver<PdfRequest?, String>(
    save = { req -> req?.let { listOf(it.fileId, it.filename) } ?: emptyList() },
    restore = { list -> list.takeIf { it.size == 2 }?.let { PdfRequest(it[0], it[1]) } },
)
