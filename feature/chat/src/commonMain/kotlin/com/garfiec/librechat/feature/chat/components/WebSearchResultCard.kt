package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.SubcomposeAsyncImage
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_search_result
import com.garfiec.librechat.feature.chat.resources.web_searched
import org.jetbrains.compose.resources.stringResource

/**
 * A single web search source parsed from a message's `web_search` attachment (or, as a
 * fallback, the tool-call output). [url] is the canonical dedup key.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val favicon: String? = null,
)

private const val MAX_STACKED_FAVICONS = 3

/** Google's favicon service — same source the source cards already use. */
private fun faviconUrl(host: String): String =
    "https://www.google.com/s2/favicons?domain=$host&sz=48"

/** Globe placeholder shown while a favicon loads or when it fails to load. */
@Composable
private fun GlobeIcon(size: Dp) {
    Icon(
        imageVector = Icons.Default.Language,
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A favicon image with a globe fallback while loading / on error. [model] is a fully-resolved
 * image URL — an explicit per-source favicon when the payload carries one, otherwise the
 * host-derived [faviconUrl].
 */
@Composable
private fun Favicon(model: String, size: Dp, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier.size(size).clip(CircleShape),
        error = { GlobeIcon(size) },
        loading = { GlobeIcon(size) },
    )
}

/** Overlapping stack of up to [MAX_STACKED_FAVICONS] domain favicons (first drawn on top). */
@Composable
private fun SourceFaviconStack(hosts: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { },
        horizontalArrangement = Arrangement.spacedBy((-7).dp),
    ) {
        hosts.forEachIndexed { i, host ->
            Box(
                modifier = Modifier
                    .zIndex((hosts.size - i).toFloat())
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Favicon(model = faviconUrl(host), size = 14.dp)
            }
        }
    }
}

/**
 * Web-search results rendered to match the web client: a collapsed "Searched the web" pill
 * with a stacked-favicon preview, expanding to a bordered list of sources (favicon, title,
 * domain). Each row opens its URL in an external browser. See upstream `WebSearch.tsx`.
 */
@Composable
fun WebSearchSourcesCard(
    results: List<WebSearchResult>,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var isExpanded by remember { mutableStateOf(false) }

    val previewHosts = remember(results) {
        val seen = LinkedHashSet<String>()
        results.mapNotNull { hostOf(it.url) }
            .filter { seen.add(it) }
            .take(MAX_STACKED_FAVICONS)
    }

    val label = stringResource(Res.string.web_searched)

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
            if (previewHosts.isNotEmpty()) {
                SourceFaviconStack(previewHosts)
            } else {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(8.dp),
                    ),
            ) {
                results.forEachIndexed { index, result ->
                    val domain = hostOf(result.url) ?: result.url
                    val rowCd = stringResource(Res.string.cd_search_result, result.title)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    uriHandler.openUri(result.url)
                                } catch (_: Exception) {
                                    // URL might be malformed
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = rowCd
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(model = result.favicon ?: faviconUrl(domain), size = 16.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (index < results.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
