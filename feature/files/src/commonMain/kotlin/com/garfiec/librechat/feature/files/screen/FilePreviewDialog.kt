package com.garfiec.librechat.feature.files.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.touchlab.kermit.Logger
import com.garfiec.librechat.feature.files.FilePreviewDisplayData
import com.garfiec.librechat.feature.files.platform.PdfPreview
import com.garfiec.librechat.feature.files.resources.*
import com.garfiec.librechat.feature.files.resources.Res
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewDialog(
    file: FilePreviewDisplayData,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDownloadFile: (suspend (fileId: String, userId: String?) -> ByteArray?)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = file.filename,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(Res.string.cd_close_preview),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            when {
                file.type == "application/pdf" -> {
                    PdfPreview(
                        file = file,
                        onDownloadFile = onDownloadFile,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
                file.type.startsWith("text/") || file.type == "application/json" ||
                    file.type == "application/xml" || file.type == "application/javascript" -> {
                    TextContentPreview(
                        file = file,
                        onDownloadFile = onDownloadFile,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
                else -> {
                    FileInfoCard(
                        file = file,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
        }
    }
}

// -- Text Content Preview --

private sealed interface TextLoadState {
    data object Loading : TextLoadState
    data class Success(val content: String) : TextLoadState
    data class Error(val message: String) : TextLoadState
}

@Composable
private fun TextContentPreview(
    file: FilePreviewDisplayData,
    onDownloadFile: (suspend (fileId: String, userId: String?) -> ByteArray?)?,
    modifier: Modifier = Modifier,
) {
    var loadState by remember { mutableStateOf<TextLoadState>(TextLoadState.Loading) }

    LaunchedEffect(file.fileId) {
        loadState = TextLoadState.Loading
        try {
            val bytes = onDownloadFile?.invoke(file.fileId, file.userId)
            if (bytes == null) {
                loadState = TextLoadState.Error("Failed to download file")
                return@LaunchedEffect
            }
            val content = bytes.decodeToString()
            val truncated = if (content.length > 500_000) {
                content.take(500_000) + "\n\n--- Content truncated (file too large) ---"
            } else {
                content
            }
            loadState = TextLoadState.Success(truncated)
        } catch (e: Exception) {
            Logger.e(e) { "TextContentPreview: failed to load text content" }
            loadState = TextLoadState.Error(
                e.message ?: "Failed to load file content",
            )
        }
    }

    when (val state = loadState) {
        is TextLoadState.Loading -> {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(Res.string.loading_file_content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is TextLoadState.Error -> {
            TextErrorFallback(
                file = file,
                errorMessage = state.message,
                modifier = modifier,
            )
        }
        is TextLoadState.Success -> {
            val verticalScrollState = rememberScrollState()
            val horizontalScrollState = rememberScrollState()

            Column(
                modifier = modifier
                    .verticalScroll(verticalScrollState)
                    .padding(16.dp),
            ) {
                Text(
                    text = file.filename,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${file.type} - ${file.formattedSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScrollState),
                ) {
                    Text(
                        text = state.content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.widthIn(min = 600.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TextErrorFallback(
    file: FilePreviewDisplayData,
    errorMessage: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error,
                )

                Text(
                    text = file.filename,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = stringResource(Res.string.could_not_load_file_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfoRow(stringResource(Res.string.info_type), file.type)
                    InfoRow(stringResource(Res.string.info_size), file.formattedSize)
                    file.createdAt?.let { InfoRow(stringResource(Res.string.info_created), it) }
                    file.source?.let { InfoRow(stringResource(Res.string.info_source), it) }
                }
            }
        }
    }
}

// -- Shared Components --

@Composable
private fun FileInfoCard(
    file: FilePreviewDisplayData,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = fileTypeIconLarge(file.type),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = file.filename,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfoRow(stringResource(Res.string.info_type), file.type)
                    InfoRow(stringResource(Res.string.info_size), file.formattedSize)
                    file.createdAt?.let { InfoRow(stringResource(Res.string.info_created), it) }
                    file.source?.let { InfoRow(stringResource(Res.string.info_source), it) }
                }
            }
        }
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private fun fileTypeIconLarge(type: String): ImageVector = when {
    type.startsWith("image/") -> Icons.Default.Image
    type.startsWith("video/") -> Icons.Default.VideoFile
    type.startsWith("audio/") -> Icons.Default.AudioFile
    type == "application/pdf" -> Icons.Default.PictureAsPdf
    type.startsWith("text/") || type == "application/json" -> Icons.Default.Description
    else -> Icons.Default.Description
}
