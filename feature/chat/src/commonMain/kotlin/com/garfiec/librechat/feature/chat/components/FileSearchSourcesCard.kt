package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_file_search_source
import com.garfiec.librechat.feature.chat.resources.file_search_pages
import com.garfiec.librechat.feature.chat.resources.file_search_relevance
import com.garfiec.librechat.feature.chat.resources.files_searched
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * One cited passage from a `file_search` attachment, already merged across the chunks that came
 * from the same file. [fileId] is the dedup key; [relevance] is 0..1.
 */
data class FileSearchCitation(
    val fileId: String,
    val fileName: String,
    val relevance: Double,
    val content: String,
    val pages: List<Int> = emptyList(),
    val fileType: String? = null,
)

/** Upstream `getFileIcon` (`RetrievalCall.tsx`), on the MIME substrings it actually branches on. */
private fun fileIconFor(mimeType: String?): ImageVector = when {
    mimeType == null -> Icons.Default.Description
    mimeType.contains("spreadsheet") || mimeType.contains("excel") || mimeType.contains("csv") ->
        Icons.Default.TableChart
    mimeType.contains("image") -> Icons.Default.Image
    mimeType.contains("javascript") || mimeType.contains("typescript") ||
        mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("html") ->
        Icons.Default.Code
    mimeType.contains("pdf") || mimeType.contains("text") || mimeType.contains("word") ->
        Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

/**
 * File-search citations, shaped like [WebSearchSourcesCard] and mirroring upstream
 * `RetrievalCall.tsx`.
 */
@Composable
fun FileSearchSourcesCard(
    citations: List<FileSearchCitation>,
    modifier: Modifier = Modifier,
    stateKey: String = "",
) {
    var isExpanded by rememberSaveable(key = "filesearch:$stateKey") { mutableStateOf(false) }
    val label = stringResource(Res.string.files_searched)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            ) {
                citations.forEachIndexed { index, citation ->
                    CitationRow(citation = citation, stateKey = "$stateKey:${citation.fileId}")
                    if (index < citations.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationRow(citation: FileSearchCitation, stateKey: String) {
    var showContent by rememberSaveable(key = "filesearchsrc:$stateKey") { mutableStateOf(false) }
    val hasContent = citation.content.isNotBlank()
    val rowCd = stringResource(Res.string.cd_file_search_source, citation.fileName)
    val relevancePercent = remember(citation.relevance) { (citation.relevance * 100).roundToInt() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasContent) { showContent = !showContent }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics {
                if (hasContent) role = Role.Button
                contentDescription = rowCd
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = fileIconFor(citation.fileType),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = citation.fileName,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (relevancePercent > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.file_search_relevance, relevancePercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (citation.pages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.file_search_pages, citation.pages.joinToString(", ")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (hasContent) {
            AnimatedVisibility(
                visible = showContent,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Text(
                    text = citation.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
