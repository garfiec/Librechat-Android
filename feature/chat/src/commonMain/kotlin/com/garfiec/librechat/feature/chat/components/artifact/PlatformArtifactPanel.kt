package com.garfiec.librechat.feature.chat.components.artifact

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.core.ui.theme.isSurfaceDark
import com.garfiec.librechat.feature.chat.components.CodeBlock
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.artifact_view_preview
import com.garfiec.librechat.feature.chat.resources.artifact_view_source
import com.garfiec.librechat.feature.chat.resources.cd_add_artifact_to_home_screen
import com.garfiec.librechat.feature.chat.resources.cd_close
import com.garfiec.librechat.feature.chat.resources.cd_fullscreen
import com.garfiec.librechat.feature.chat.resources.cd_share_artifact
import org.jetbrains.compose.resources.stringResource

/**
 * The platform-specific preview renderer for an artifact — an Android `WebView`
 * or an iOS `WKWebView`. Everything else about the artifact viewer (shell,
 * header, selector, code body) is shared in [ArtifactPanel] below.
 */
@Composable
expect fun ArtifactPreviewSurface(
    content: String,
    type: String,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
)

/**
 * Returns a share action for the current platform. Android routes through
 * `ArtifactDownloadHelper` (needs a `Context`); iOS uses `UIActivityViewController`.
 */
@Composable
expect fun rememberShareArtifact(): (Artifact) -> Unit

/**
 * Returns an action that pins [Artifact] to the device home screen as a launcher shortcut, or `null`
 * on platforms that can't place home-screen icons (iOS). The action snapshots the artifact into local
 * storage and requests the pin; [label]/[emoji] come from the confirmation dialog. Distributed to call
 * sites (viewer toolbar, inline card) via [LocalAddArtifactToHomeScreen] — invoked once here.
 */
@Composable
expect fun rememberAddArtifactToHomeScreen(): ((artifact: Artifact, label: String, emoji: String?) -> Unit)?

private enum class ArtifactTab { CODE, PREVIEW }

private fun isPreviewableType(type: String): Boolean =
    when (ArtifactType.from(type)) {
        ArtifactType.MERMAID,
        ArtifactType.REACT,
        ArtifactType.SVG,
        ArtifactType.MARKDOWN,
        ArtifactType.HTML,
        ArtifactType.PLAIN,
        -> true
        ArtifactType.CODE -> false
    }

/**
 * Bottom-sheet artifact viewer. Rendered at the screen root by the navigation entry's
 * artifact opener (see `LocalOpenArtifact`), not inline in the message list. Previewable
 * artifacts open on the Preview tab; the source stays reachable from the header
 * source/preview toggle. [onExpandFullscreen] (when non-null) backs the expand action,
 * promoting the currently-viewed version to the full-screen route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactPanel(
    artifact: Artifact,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onExpandFullscreen: ((Artifact, List<Artifact>) -> Unit)? = null,
    versions: List<Artifact> = listOf(artifact),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.fillMaxSize(),
        dragHandle = { LowProfileDragHandle() },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        ArtifactViewer(
            artifact = artifact,
            versions = versions,
            isFullscreen = false,
            onClose = onDismiss,
            // The expand action opens the full-screen route with the currently-viewed version.
            onExpand = onExpandFullscreen?.let { expand -> { current -> expand(current, versions); onDismiss() } },
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        )
    }
}

/**
 * Stateful artifact viewer used by both the bottom-sheet shell and the full-screen
 * route. Holds the selected version and Code/Preview tab; renders [ArtifactViewerBody].
 *
 * [onExpand] (null when no expand action is available) receives the currently-viewed
 * version so the full-screen route opens on the same one.
 */
@Composable
internal fun ArtifactViewer(
    artifact: Artifact,
    versions: List<Artifact>,
    isFullscreen: Boolean,
    onClose: () -> Unit,
    onExpand: ((Artifact) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var currentVersionIndex by remember {
        mutableIntStateOf(
            versions.indexOfFirst {
                it.identifier == artifact.identifier && it.version == artifact.version
            }.coerceAtLeast(0),
        )
    }
    val currentArtifact = versions.getOrElse(currentVersionIndex) { artifact }
    val isPreviewable = isPreviewableType(currentArtifact.type)

    // Previewable artifacts open on Preview; code-only artifacts have nothing else to show.
    var selectedTab by remember {
        mutableStateOf(if (isPreviewable) ArtifactTab.PREVIEW else ArtifactTab.CODE)
    }

    val isDarkTheme = isSurfaceDark()
    val shareArtifact = rememberShareArtifact()
    // Null unless a home-screen-pin provider is in scope (Android chat/media entries). The shortcut
    // viewer itself doesn't provide it, so a pinned artifact can't be re-pinned from its own screen.
    val addToHomeScreen = LocalAddArtifactToHomeScreen.current

    ArtifactViewerBody(
        artifact = currentArtifact,
        versionCount = versions.size,
        currentVersionIndex = currentVersionIndex,
        onPreviousVersion = { if (currentVersionIndex > 0) currentVersionIndex-- },
        onNextVersion = { if (currentVersionIndex < versions.size - 1) currentVersionIndex++ },
        isPreviewable = isPreviewable,
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        isDarkTheme = isDarkTheme,
        isFullscreen = isFullscreen,
        canExpand = onExpand != null,
        onExpand = { onExpand?.invoke(currentArtifact) },
        onClose = onClose,
        onShare = { shareArtifact(currentArtifact) },
        onAddToHomeScreen = addToHomeScreen?.let { add -> { add(currentArtifact) } },
        modifier = modifier,
    )
}

@Composable
private fun ArtifactViewerBody(
    artifact: Artifact,
    versionCount: Int,
    currentVersionIndex: Int,
    onPreviousVersion: () -> Unit,
    onNextVersion: () -> Unit,
    isPreviewable: Boolean,
    selectedTab: ArtifactTab,
    onSelectTab: (ArtifactTab) -> Unit,
    isDarkTheme: Boolean,
    isFullscreen: Boolean,
    canExpand: Boolean,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onAddToHomeScreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Compact single-row header: [close] title  [version nav]  [action buttons]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (isFullscreen) 4.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isFullscreen) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.cd_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text(
                text = artifact.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (versionCount > 1) {
                ArtifactVersionNav(
                    currentIndex = currentVersionIndex,
                    totalVersions = versionCount,
                    onPrevious = onPreviousVersion,
                    onNext = onNextVersion,
                )
            }
            ArtifactActionButtons(
                isPreviewable = isPreviewable,
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                canExpand = canExpand,
                onExpand = onExpand,
                onShare = onShare,
                onAddToHomeScreen = onAddToHomeScreen,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Keep the preview surface mounted across toggles; swapping it out would
            // recreate the WebView and reload it (a full-panel flash). Code layers on top.
            if (isPreviewable) {
                ArtifactPreviewSurface(
                    content = artifact.content,
                    type = artifact.type,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!isPreviewable || selectedTab == ArtifactTab.CODE) {
                val codeScrollState = rememberScrollState()
                // Edge-to-edge preview, but keep horizontal padding on code for readability.
                var codeModifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp)
                if (!isFullscreen) {
                    // Isolate code scrolling from the bottom sheet's drag gesture.
                    codeModifier = codeModifier.nestedScroll(remember { SheetScrollBlocker(codeScrollState) })
                }
                Box(modifier = codeModifier.verticalScroll(codeScrollState)) {
                    CodeBlock(
                        code = artifact.content,
                        language = artifact.language,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ArtifactActionButtons(
    isPreviewable: Boolean,
    selectedTab: ArtifactTab,
    onSelectTab: (ArtifactTab) -> Unit,
    canExpand: Boolean,
    onExpand: () -> Unit,
    onShare: () -> Unit,
    onAddToHomeScreen: (() -> Unit)?,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    // Source/preview toggle for previewable artifacts.
    if (isPreviewable) {
        val toCode = selectedTab == ArtifactTab.PREVIEW
        IconButton(onClick = { onSelectTab(if (toCode) ArtifactTab.CODE else ArtifactTab.PREVIEW) }) {
            Icon(
                imageVector = if (toCode) Icons.Default.Code else Icons.Default.Visibility,
                contentDescription = stringResource(
                    if (toCode) Res.string.artifact_view_source else Res.string.artifact_view_preview,
                ),
                tint = tint,
            )
        }
    }
    if (onAddToHomeScreen != null) {
        IconButton(onClick = onAddToHomeScreen) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.AddToHomeScreen,
                contentDescription = stringResource(Res.string.cd_add_artifact_to_home_screen),
                tint = tint,
            )
        }
    }
    IconButton(onClick = onShare) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = stringResource(Res.string.cd_share_artifact),
            tint = tint,
        )
    }
    if (canExpand) {
        IconButton(onClick = onExpand) {
            Icon(
                imageVector = Icons.Default.Fullscreen,
                contentDescription = stringResource(Res.string.cd_fullscreen),
                tint = tint,
            )
        }
    }
}

/**
 * Intercepts all vertical scroll and manually dispatches it to the given [ScrollState],
 * then reports it all as consumed so the parent ModalBottomSheet never receives it.
 * This isolates the content's scrolling from the sheet's drag gesture.
 */
private class SheetScrollBlocker(private val scrollState: ScrollState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y != 0f) {
            scrollState.dispatchRawDelta(-available.y)
        }
        return Offset(0f, available.y)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        return Offset(0f, available.y)
    }
}
