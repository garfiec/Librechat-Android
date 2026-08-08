package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.common.extensions.RelativeTimeReference
import com.garfiec.librechat.core.data.prefetch.PrefetchConversationStatus
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.prefetch_activity_title
import com.garfiec.librechat.feature.settings.resources.prefetch_cache_header
import com.garfiec.librechat.feature.settings.resources.prefetch_cache_images
import com.garfiec.librechat.feature.settings.resources.prefetch_cache_messages
import com.garfiec.librechat.feature.settings.resources.prefetch_cache_messages_value
import com.garfiec.librechat.feature.settings.resources.prefetch_conditions_header
import com.garfiec.librechat.feature.settings.resources.prefetch_pending_empty
import com.garfiec.librechat.feature.settings.resources.prefetch_pending_header
import com.garfiec.librechat.feature.settings.resources.prefetch_row_never_warmed
import com.garfiec.librechat.feature.settings.resources.prefetch_row_pinned
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_last_run
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_last_run_never
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_status
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_warmed
import com.garfiec.librechat.feature.settings.resources.prefetch_summary_warmed_value
import com.garfiec.librechat.feature.settings.resources.prefetch_warm_now
import com.garfiec.librechat.feature.settings.resources.prefetch_warm_now_desc
import com.garfiec.librechat.feature.settings.resources.prefetch_warmed_empty
import com.garfiec.librechat.feature.settings.resources.prefetch_warmed_header
import com.garfiec.librechat.feature.settings.viewmodel.PrefetchActivityViewModel
import com.garfiec.librechat.feature.settings.viewmodel.PrefetchConditionRow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The detail behind the prefetch summary in Data settings: why prefetching is or is not running,
 * what it has cached, and what it still intends to fetch.
 *
 * Every figure is derived live from the watermark table and the gate — nothing is recorded for this
 * screen's benefit — so it cannot report a pass that did not happen, and equally cannot show any
 * history beyond what the watermarks still hold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrefetchActivityScreen(onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: PrefetchActivityViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timeReference = rememberTickingTimeReference()

    // Read here rather than on construction: Data settings resolves this ViewModel for the status
    // summary alone, and these two reads are a recursive directory walk and a full table scan.
    LaunchedEffect(Unit) { viewModel.loadCacheFigures() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.prefetch_activity_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "summary") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailRow(stringResource(Res.string.prefetch_summary_status), uiState.status.label())
                    DetailRow(
                        stringResource(Res.string.prefetch_summary_last_run),
                        uiState.lastWarmedAt?.relativeLabel(timeReference)
                            ?: stringResource(Res.string.prefetch_summary_last_run_never),
                    )
                    DetailRow(
                        stringResource(Res.string.prefetch_summary_warmed),
                        stringResource(
                            Res.string.prefetch_summary_warmed_value,
                            uiState.warmedCount,
                            uiState.eligibleCount,
                        ),
                    )
                }
            }

            item(key = "warm_now") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = viewModel::warmNow,
                        enabled = uiState.canWarmNow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.prefetch_warm_now))
                    }
                    Text(
                        text = stringResource(Res.string.prefetch_warm_now_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "conditions_header") {
                PrefetchSectionHeader(stringResource(Res.string.prefetch_conditions_header))
            }
            items(uiState.conditions, key = { "condition_${it.condition.name}" }) { row ->
                ConditionRow(row)
            }

            item(key = "warmed_header") {
                PrefetchSectionHeader(stringResource(Res.string.prefetch_warmed_header))
            }
            if (uiState.warmed.isEmpty()) {
                item(key = "warmed_empty") {
                    EmptyLine(stringResource(Res.string.prefetch_warmed_empty))
                }
            }
            items(uiState.warmed, key = { "warmed_${it.conversationId}" }) { row ->
                ConversationRow(row, timeReference)
            }

            item(key = "pending_header") {
                PrefetchSectionHeader(stringResource(Res.string.prefetch_pending_header))
            }
            if (uiState.pending.isEmpty()) {
                item(key = "pending_empty") {
                    EmptyLine(stringResource(Res.string.prefetch_pending_empty))
                }
            }
            items(uiState.pending, key = { "pending_${it.conversationId}" }) { row ->
                ConversationRow(row, timeReference)
            }

            item(key = "cache_header") {
                PrefetchSectionHeader(stringResource(Res.string.prefetch_cache_header))
            }
            item(key = "cache_figures") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailRow(
                        stringResource(Res.string.prefetch_cache_messages),
                        stringResource(
                            Res.string.prefetch_cache_messages_value,
                            uiState.cachedMessageCount,
                        ),
                    )
                    // Only shown once measured: a "0 B" on a cache that simply has not been walked
                    // yet reads as a broken feature rather than an empty one.
                    uiState.imageCacheBytes?.let { bytes ->
                        DetailRow(
                            stringResource(Res.string.prefetch_cache_images),
                            formatBytes(bytes),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrefetchSectionHeader(text: String) {
    Column {
        HorizontalDivider()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConditionRow(row: PrefetchConditionRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (row.met) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (row.met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = row.condition.label(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        row.detail()?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConversationRow(row: PrefetchConversationStatus, reference: RelativeTimeReference) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (row.pinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = stringResource(Res.string.prefetch_row_pinned),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.warmedAt?.relativeLabel(reference)
                ?: stringResource(Res.string.prefetch_row_never_warmed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
