package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_memory_artifacts
import com.garfiec.librechat.feature.chat.resources.memory_already_exceeded
import com.garfiec.librechat.feature.chat.resources.memory_deleted
import com.garfiec.librechat.feature.chat.resources.memory_deleted_items
import com.garfiec.librechat.feature.chat.resources.memory_error
import com.garfiec.librechat.feature.chat.resources.memory_storage_full
import com.garfiec.librechat.feature.chat.resources.memory_updated
import com.garfiec.librechat.feature.chat.resources.memory_updated_items
import com.garfiec.librechat.feature.chat.resources.memory_would_exceed
import org.jetbrains.compose.resources.stringResource

/** What a `memory` attachment records. Upstream `MemoryArtifact.type`. */
enum class MemoryChangeKind { UPDATE, DELETE, ERROR }

/**
 * The parsed contents of a [MemoryChangeKind.ERROR] artifact's `value`, which the server sends as
 * a JSON blob rather than a sentence. Absent when the blob was unparseable or carried an
 * `errorType` this client has no message for; the generic error label covers both, exactly as
 * upstream `MemoryInfo` does.
 */
data class MemoryErrorInfo(
    val errorType: String?,
    val tokenCount: Int?,
)

/** The message for a storage-limit error, or the generic label when it is not one we know. */
@Composable
internal fun memoryErrorMessage(error: MemoryErrorInfo?): String {
    val tokens = error?.tokenCount
    return when {
        tokens == null -> stringResource(Res.string.memory_error)
        error.errorType == MEMORY_ERROR_ALREADY_EXCEEDED ->
            stringResource(Res.string.memory_already_exceeded, tokens)
        error.errorType == MEMORY_ERROR_WOULD_EXCEED ->
            stringResource(Res.string.memory_would_exceed, tokens)
        else -> stringResource(Res.string.memory_error)
    }
}

/**
 * The memory writes no tool card in this message accounts for (see
 * [collectUnrenderedMemoryArtifacts]), as a collapsed "Updated saved memory" line — red, "Memory
 * Error", when any entry failed — expanding to the keys written, deleted, and refused. Mirrors
 * upstream `MemoryArtifacts.tsx` + `MemoryInfo.tsx`.
 */
@Composable
fun MemoryArtifactsSection(
    artifacts: List<MemoryArtifact>,
    modifier: Modifier = Modifier,
    stateKey: String = "",
) {
    if (artifacts.isEmpty()) return

    var isExpanded by rememberSaveable(key = "memoryartifacts:$stateKey") { mutableStateOf(false) }

    val updated = artifacts.filter { it.kind == MemoryChangeKind.UPDATE }
    val deleted = artifacts.filter { it.kind == MemoryChangeKind.DELETE }
    val failed = artifacts.filter { it.kind == MemoryChangeKind.ERROR }

    val accent = if (failed.isEmpty()) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.error
    }
    val label = if (failed.isEmpty()) {
        stringResource(Res.string.memory_updated)
    } else {
        stringResource(Res.string.memory_error)
    }
    val sectionCd = stringResource(Res.string.cd_memory_artifacts, artifacts.size)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = sectionCd
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = accent,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent,
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            MemoryArtifactDetail(updated = updated, deleted = deleted, failed = failed)
        }
    }
}

/** The expanded body: written keys, deleted keys, then refusals. Upstream `MemoryInfo.tsx`. */
@Composable
private fun MemoryArtifactDetail(
    updated: List<MemoryArtifact>,
    deleted: List<MemoryArtifact>,
    failed: List<MemoryArtifact>,
) {
    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        if (updated.isNotEmpty()) {
            GroupHeader(stringResource(Res.string.memory_updated_items))
            updated.forEach { artifact ->
                MemoryKeyRow(
                    key = artifact.title,
                    body = artifact.content,
                    bodyColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (deleted.isNotEmpty()) {
            if (updated.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
            }
            GroupHeader(stringResource(Res.string.memory_deleted_items))
            deleted.forEach { artifact ->
                MemoryKeyRow(
                    key = artifact.title,
                    body = stringResource(Res.string.memory_deleted),
                    bodyColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    italicBody = true,
                )
            }
        }
        if (failed.isNotEmpty()) {
            if (updated.isNotEmpty() || deleted.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
            }
            GroupHeader(
                text = stringResource(Res.string.memory_storage_full),
                color = MaterialTheme.colorScheme.error,
            )
            failed.forEach { artifact ->
                Text(
                    text = memoryErrorMessage(artifact.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun MemoryKeyRow(
    key: String?,
    body: String?,
    bodyColor: Color,
    italicBody: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (!key.isNullOrBlank()) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = if (italicBody) FontStyle.Italic else FontStyle.Normal,
                ),
                color = bodyColor,
            )
        }
    }
}
