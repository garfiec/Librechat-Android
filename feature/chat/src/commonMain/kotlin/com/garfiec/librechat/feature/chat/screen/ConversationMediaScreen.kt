package com.garfiec.librechat.feature.chat.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.garfiec.librechat.core.common.extensions.formatByteSize
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.core.ui.components.LibreChatTopBar
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.core.ui.media.MediaActionBar
import com.garfiec.librechat.core.ui.media.MediaItem
import com.garfiec.librechat.core.ui.media.ZoomableMediaPager
import com.garfiec.librechat.core.ui.media.rememberSaveImageToGallery
import com.garfiec.librechat.core.ui.media.rememberShareFile
import com.garfiec.librechat.core.ui.media.rememberShareImage
import com.garfiec.librechat.feature.chat.components.artifact.Artifact
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactButton
import com.garfiec.librechat.feature.chat.components.artifact.LocalOpenArtifact
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_close
import com.garfiec.librechat.feature.chat.resources.cd_image
import com.garfiec.librechat.feature.chat.resources.cd_save_to_device
import com.garfiec.librechat.feature.chat.resources.cd_share_image
import com.garfiec.librechat.feature.chat.resources.media_empty_artifacts
import com.garfiec.librechat.feature.chat.resources.media_empty_files
import com.garfiec.librechat.feature.chat.resources.media_empty_links
import com.garfiec.librechat.feature.chat.resources.media_empty_media
import com.garfiec.librechat.feature.chat.resources.media_gallery_title
import com.garfiec.librechat.feature.chat.resources.media_scope_this_branch
import com.garfiec.librechat.feature.chat.resources.media_scope_whole_chat
import com.garfiec.librechat.feature.chat.resources.media_tab_artifacts
import com.garfiec.librechat.feature.chat.resources.media_tab_files
import com.garfiec.librechat.feature.chat.resources.media_tab_links
import com.garfiec.librechat.feature.chat.resources.media_tab_media
import com.garfiec.librechat.feature.chat.util.ConversationFile
import com.garfiec.librechat.feature.chat.util.ConversationLink
import com.garfiec.librechat.feature.chat.viewmodel.ConversationMediaViewModel
import com.garfiec.librechat.feature.chat.viewmodel.MediaScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class MediaTab { MEDIA, FILES, LINKS, ARTIFACTS }

private val MediaThumbShape = RoundedCornerShape(4.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationMediaScreen(
    conversationId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationMediaViewModel = koinViewModel { parametersOf(conversationId) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(MediaTab.MEDIA.ordinal) }

    Scaffold(
        modifier = modifier,
        topBar = {
            LibreChatTopBar(
                title = stringResource(Res.string.media_gallery_title),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == MediaTab.MEDIA.ordinal,
                    onClick = { selectedTab = MediaTab.MEDIA.ordinal },
                    text = { Text(stringResource(Res.string.media_tab_media)) },
                )
                Tab(
                    selected = selectedTab == MediaTab.FILES.ordinal,
                    onClick = { selectedTab = MediaTab.FILES.ordinal },
                    text = { Text(stringResource(Res.string.media_tab_files)) },
                )
                Tab(
                    selected = selectedTab == MediaTab.LINKS.ordinal,
                    onClick = { selectedTab = MediaTab.LINKS.ordinal },
                    text = { Text(stringResource(Res.string.media_tab_links)) },
                )
                Tab(
                    selected = selectedTab == MediaTab.ARTIFACTS.ordinal,
                    onClick = { selectedTab = MediaTab.ARTIFACTS.ordinal },
                    text = { Text(stringResource(Res.string.media_tab_artifacts)) },
                )
            }

            ScopeToggle(
                scope = uiState.scope,
                onScopeChange = viewModel::setScope,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> LoadingIndicator(modifier = Modifier.fillMaxSize())
                    selectedTab == MediaTab.MEDIA.ordinal ->
                        MediaGrid(uiState.media, onOpen = viewModel::openMedia)
                    selectedTab == MediaTab.FILES.ordinal ->
                        FilesList(uiState.files, downloadBytes = viewModel::downloadFileBytes)
                    selectedTab == MediaTab.LINKS.ordinal -> LinksList(uiState.links)
                    else -> ArtifactsList(uiState.artifacts)
                }
            }
        }
    }

    val mediaPreview = uiState.mediaPreview
    if (mediaPreview != null) {
        val saveImage = rememberSaveImageToGallery()
        val shareImage = rememberShareImage()
        val saveDescription = stringResource(Res.string.cd_save_to_device)
        val shareDescription = stringResource(Res.string.cd_share_image)
        val imageDescription = stringResource(Res.string.cd_image)
        ZoomableMediaPager(
            items = mediaPreview.items,
            initialIndex = mediaPreview.initialIndex,
            onDismiss = viewModel::closeMedia,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeToggle(
    scope: MediaScope,
    onScopeChange: (MediaScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = scope == MediaScope.ACTIVE_BRANCH,
            onClick = { onScopeChange(MediaScope.ACTIVE_BRANCH) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text(stringResource(Res.string.media_scope_this_branch))
        }
        SegmentedButton(
            selected = scope == MediaScope.ENTIRE,
            onClick = { onScopeChange(MediaScope.ENTIRE) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text(stringResource(Res.string.media_scope_whole_chat))
        }
    }
}

@Composable
private fun MediaGrid(
    media: List<MediaItem>,
    onOpen: (url: String) -> Unit,
) {
    if (media.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.media_empty_media),
            icon = Icons.Outlined.PermMedia,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(media, key = { it.url }) { item ->
            AsyncImage(
                model = item.url,
                contentDescription = item.contentDescription.ifBlank { null },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(MediaThumbShape)
                    .clickable { onOpen(item.url) },
            )
        }
    }
}

@Composable
private fun FilesList(
    files: List<ConversationFile>,
    downloadBytes: suspend (fileId: String) -> ByteArray?,
) {
    if (files.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.media_empty_files),
            icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
        )
        return
    }
    val scope = rememberCoroutineScope()
    val shareFile = rememberShareFile()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.fileId }) { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            val bytes = downloadBytes(file.fileId)
                            if (bytes != null) shareFile(bytes, file.filename, file.type)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        text = file.filename,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(
                        file.bytes?.let { formatByteSize(it) },
                        file.type,
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinksList(links: List<ConversationLink>) {
    if (links.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.media_empty_links),
            icon = Icons.Outlined.Link,
        )
        return
    }
    val uriHandler = LocalUriHandler.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(links, key = { it.url }) { link ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri(link.url) } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        text = link.host,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = link.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactsList(artifacts: List<List<Artifact>>) {
    if (artifacts.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.media_empty_artifacts),
            icon = Icons.Outlined.Dashboard,
        )
        return
    }
    // Tapping a card hands the artifact (with its full version history) to the screen-level
    // opener, which presents it as a bottom sheet or full-screen route per the display pref.
    val openArtifact = LocalOpenArtifact.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(artifacts, key = { it.first().identifier }) { versions ->
            ArtifactButton(
                artifact = versions.last(),
                onClick = { openArtifact?.invoke(versions.last(), versions) },
                versionCount = versions.size,
            )
        }
    }
}
