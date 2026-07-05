package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.components.PlatformBackHandler
import com.garfiec.librechat.core.ui.media.rememberShareFile
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_close
import com.garfiec.librechat.feature.chat.resources.cd_share_pdf
import com.garfiec.librechat.feature.chat.resources.pdf_load_failed
import com.garfiec.librechat.feature.chat.resources.retry
import org.jetbrains.compose.resources.stringResource

private sealed interface PdfLoadState {
    data object Loading : PdfLoadState

    /** Download produced no bytes (network/auth failure) — retryable. */
    data object DownloadFailed : PdfLoadState

    // Not data classes: ByteArray has no structural equals, and these are only ever compared by
    // reference (the produceState value swap), never by content.

    /** Bytes downloaded but they don't look like a PDF (e.g. an HTML error body). Not previewable,
     *  but the bytes are still shareable — re-downloading won't change them, so no retry. */
    class Unrenderable(val bytes: ByteArray) : PdfLoadState

    /** Bytes downloaded and look like a PDF — handed to the native viewer. */
    class Loaded(val bytes: ByteArray) : PdfLoadState
}

/**
 * Full-screen PDF preview rendered at the chat screen root (by [ChatRoot]). Downloads the file's
 * bytes via [onDownload], checks they actually look like a PDF, then hands them to the platform-native
 * [PdfViewer]. A slim top bar carries close + share; system back dismisses on Android, and the
 * explicit close covers iOS (which has no system back). Mirrors the media-viewer overlay pattern.
 *
 * Share stays available whenever bytes were downloaded — even if they aren't previewable or the
 * native engine rejects them — so a file that can't be rendered can still be exported (the escape
 * hatch the plain download chip used to provide). Retry only re-downloads; failures that re-downloading
 * can't fix (non-PDF bytes, an unrenderable PDF) don't offer it, to avoid a no-progress loop.
 */
@Composable
internal fun PdfPreviewOverlay(
    fileId: String,
    filename: String,
    onDownload: suspend (fileId: String) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    PlatformBackHandler(enabled = true, onBack = onDismiss)

    // Bumping `attempt` re-runs the download (Retry) and resets the per-attempt render-failed flag.
    var attempt by remember(fileId) { mutableIntStateOf(0) }
    var renderFailed by remember(fileId, attempt) { mutableStateOf(false) }

    // rememberUpdatedState so the download effect keys only on (fileId, attempt), not the (stable) lambda.
    val currentDownload by rememberUpdatedState(onDownload)
    val loadState by produceState<PdfLoadState>(PdfLoadState.Loading, fileId, attempt) {
        value = PdfLoadState.Loading
        val bytes = currentDownload(fileId)
        value = when {
            bytes == null -> PdfLoadState.DownloadFailed
            !looksLikePdf(bytes) -> PdfLoadState.Unrenderable(bytes)
            else -> PdfLoadState.Loaded(bytes)
        }
    }
    val shareFile = rememberShareFile()

    // Bytes are shareable whenever the download produced them, even if the native engine later
    // failed to render them or they aren't a PDF — the user can still get the file out to another app.
    val shareBytes = when (val state = loadState) {
        is PdfLoadState.Loaded -> state.bytes
        is PdfLoadState.Unrenderable -> state.bytes
        else -> null
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.cd_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = filename,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { shareBytes?.let { shareFile(it, filename, "application/pdf") } },
                    enabled = shareBytes != null,
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(Res.string.cd_share_pdf),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = loadState) {
                    is PdfLoadState.Loading -> CircularProgressIndicator()
                    // Only a failed download is worth retrying; re-downloading won't fix bad bytes.
                    is PdfLoadState.DownloadFailed -> PdfErrorContent(onRetry = { attempt++ })
                    is PdfLoadState.Unrenderable -> PdfErrorContent(onRetry = null)
                    is PdfLoadState.Loaded ->
                        if (renderFailed) {
                            PdfErrorContent(onRetry = null)
                        } else {
                            PdfViewer(
                                bytes = state.bytes,
                                onRenderError = { renderFailed = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                }
            }
        }
    }
}

/** Error text, plus a Retry button when [onRetry] is non-null (retry re-downloads). */
@Composable
private fun PdfErrorContent(onRetry: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.pdf_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.retry))
            }
        }
    }
}
