package com.garfiec.librechat.feature.skills.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.SkillFile
import com.garfiec.librechat.feature.skills.resources.*
import com.garfiec.librechat.feature.skills.resources.Res
import com.garfiec.librechat.feature.skills.viewmodel.SkillFilesUiState
import org.jetbrains.compose.resources.stringResource

/**
 * Flat skill-file attachments list (NOT a folder tree — that's not implemented upstream).
 * Upload/delete affordances are gated by the caller on [SkillFilesUiState.canEditFiles].
 */
@Composable
fun SkillFilesSection(
    state: SkillFilesUiState,
    onAddFile: () -> Unit,
    onRemoveFile: (SkillFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.skill_files_title),
            style = MaterialTheme.typography.titleSmall,
        )

        when {
            state.isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            state.files.isEmpty() -> Text(
                text = stringResource(Res.string.skill_files_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> state.files.forEach { file ->
                SkillFileRow(
                    file = file,
                    canRemove = state.canEditFiles,
                    onRemove = { onRemoveFile(file) },
                )
            }
        }

        state.error?.let { err ->
            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (state.canEditFiles) {
            OutlinedButton(
                onClick = onAddFile,
                enabled = !state.isUploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isUploading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.skill_files_add))
            }
        }
    }
}

@Composable
private fun SkillFileRow(
    file: SkillFile,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.relativePath,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    file.mimeType?.takeIf { it.isNotBlank() },
                    file.bytes.takeIf { it > 0 }?.let { "$it B" },
                ).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(Res.string.skill_files_remove, file.relativePath),
                    )
                }
            }
        }
    }
}
