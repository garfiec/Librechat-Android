package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.garfiec.librechat.core.model.FileReference
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.util.resolveFileReferenceUrl
import org.jetbrains.compose.resources.stringResource

/**
 * Renders files attached to a message. Splits files into images (shown as
 * inline previews) and non-image files (shown as file chips with icon and name).
 *
 * This mirrors the official LibreChat web app's `Files` component which renders
 * `message.files` above the message text for user messages.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageFiles(
    files: List<FileReference>,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
) {
    if (files.isEmpty()) return

    val imageFiles = remember(files) {
        files.filter { it.type?.startsWith("image/") == true }
    }
    val otherFiles = remember(files) {
        files.filter { it.type?.startsWith("image/") != true }
    }

    Column(modifier = modifier) {
        // Non-image file chips
        if (otherFiles.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                otherFiles.forEach { file ->
                    FileChip(
                        filename = file.filename ?: file.filepath?.substringAfterLast('/') ?: stringResource(Res.string.file_fallback),
                        type = file.type,
                    )
                }
            }
            if (imageFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Image previews
        imageFiles.forEach { file ->
            val imageUrl = resolveFileReferenceUrl(file, baseUrl)
            if (imageUrl != null) {
                MessageImagePreview(
                    imageUrl = imageUrl,
                    altText = file.filename ?: "Attached image",
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Inline image preview that can be tapped to open fullscreen.
 */
@Composable
internal fun MessageImagePreview(
    imageUrl: String,
    altText: String,
    modifier: Modifier = Modifier,
) {
    val openMedia = LocalChatMediaViewer.current

    SubcomposeAsyncImage(
        model = imageUrl,
        contentDescription = altText,
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .widthIn(max = 300.dp)
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { openMedia(imageUrl) }
            .semantics {
                role = Role.Image
                contentDescription = altText
            },
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = stringResource(Res.string.cd_failed_to_load_image),
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * A chip that shows a non-image file with an icon and filename.
 *
 * When [onClick] is non-null the chip is tappable (e.g. to download + share a generated file);
 * [isLoading] swaps the leading icon for a spinner while that action is in flight.
 */
@Composable
internal fun FileChip(
    filename: String,
    type: String?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = fileTypeIcon(type),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = filename,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}

/**
 * Returns an appropriate icon for the given MIME type.
 */
private fun fileTypeIcon(type: String?): ImageVector {
    if (type == null) return Icons.AutoMirrored.Filled.InsertDriveFile
    return when {
        type.startsWith("application/pdf") -> Icons.Default.PictureAsPdf
        type.startsWith("text/") -> Icons.Default.Description
        type.contains("spreadsheet") || type.contains("csv") || type.contains("excel") -> Icons.Default.TableChart
        type.contains("document") || type.contains("word") -> Icons.Default.Description
        else -> Icons.Default.AttachFile
    }
}
