package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.Attachment
import com.garfiec.librechat.core.model.response.FilePreviewResponse
import com.garfiec.librechat.feature.chat.components.artifact.Artifact
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactType
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactWebContent
import com.garfiec.librechat.feature.chat.components.artifact.InlineArtifactView
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.office_preview_failed
import com.garfiec.librechat.feature.chat.resources.office_preview_preparing
import com.garfiec.librechat.feature.chat.resources.office_preview_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * Renders a deferred office-doc preview attachment (v0.8.6) by lifecycle status:
 *  - `pending`  → "Preparing preview…" with a spinner.
 *  - `ready` + text → the extracted document as an HTML artifact, built through
 *    [ArtifactWebContent.buildOfficePreviewHtml] (which enforces the
 *    textFormat==html security gate — HTML only when html, escaped otherwise)
 *    and rendered via the SAME [InlineArtifactView] WebView other artifacts use.
 *  - `ready` with no text (binary/oversized) → a download-only chip.
 *  - `failed` → a chip with the [Attachment.previewError] reason.
 *
 * Works for both the live stream (the delegate folds status/text in) and a
 * reloaded message (the persisted attachment already carries them).
 */
@Composable
internal fun OfficePreviewCard(
    attachment: Attachment,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
) {
    val status = attachment.status ?: FilePreviewResponse.STATUS_READY
    val filename = attachment.filename.orEmpty()
    val previewText = attachment.text

    when {
        status == FilePreviewResponse.STATUS_PENDING ->
            OfficeStatusChip(
                icon = { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) },
                label = stringResource(Res.string.office_preview_preparing, filename),
                modifier = modifier,
            )

        status == FilePreviewResponse.STATUS_FAILED ->
            OfficeStatusChip(
                icon = {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                label = stringResource(
                    Res.string.office_preview_failed,
                    filename,
                    attachment.previewError ?: "",
                ),
                modifier = modifier,
            )

        !previewText.isNullOrBlank() -> {
            // Build the safe document HERE (where textFormat is known) so the
            // security gate is honored, then hand the complete document to the
            // shared artifact WebView (buildHtml passes office content unchanged).
            val artifact = Artifact(
                identifier = attachment.fileId ?: filename,
                type = attachment.type ?: ArtifactType.DEFAULT_OFFICE_PREVIEW_MIME,
                title = filename,
                language = null,
                content = ArtifactWebContent.buildOfficePreviewHtml(
                    text = previewText,
                    textFormat = attachment.textFormat,
                    isDarkTheme = isDarkTheme,
                    inline = true,
                ),
            )
            InlineArtifactView(
                artifact = artifact,
                onTap = { /* inline preview; full-screen open handled elsewhere if needed */ },
                modifier = modifier.fillMaxWidth(),
            )
        }

        else ->
            // ready but no inline content (binary/oversized) → download-only.
            OfficeStatusChip(
                icon = {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                label = stringResource(Res.string.office_preview_unavailable, filename),
                modifier = modifier,
            )
    }
}

/**
 * Renders every office-doc preview attachment in [attachments], de-duplicated by
 * file_id (a live record and a persisted record for the same file must not
 * double-render). Non-office attachments are ignored — they keep their existing
 * render paths untouched.
 */
@Composable
internal fun OfficePreviewAttachments(
    attachments: List<Attachment>,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
) {
    val officeAttachments = attachments
        .filter { ArtifactType.isOfficePreviewMime(it.type) }
        .distinctBy { it.fileId ?: it.filename }
    if (officeAttachments.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        officeAttachments.forEach { attachment ->
            Spacer(modifier = Modifier.padding(top = 4.dp))
            OfficePreviewCard(attachment = attachment, isDarkTheme = isDarkTheme)
        }
    }
}

@Composable
private fun OfficeStatusChip(
    icon: @Composable () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            icon()
        }
    }
}
