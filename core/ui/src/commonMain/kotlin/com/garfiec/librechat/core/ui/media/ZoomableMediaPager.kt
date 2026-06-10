package com.garfiec.librechat.core.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState

/**
 * A single zoomable/pannable media item displayed by [ZoomableMediaPager].
 *
 * [url] is the resolved, ready-to-load image URL and doubles as the stable pager key,
 * so callers must dedupe by URL before passing the list in.
 */
data class MediaItem(
    val url: String,
    val contentDescription: String,
    val filename: String? = null,
)

/**
 * Open-state for the media viewer, held in a ViewModel's UI state so it survives rotation.
 * `null` (the absence of this object) means the viewer is closed.
 */
data class MediaPreviewState(
    val items: List<MediaItem>,
    val initialIndex: Int,
)

/**
 * Full-screen, Google-Photos-style media viewer.
 *
 * - Fit-to-screen is the rest state ([ContentScale.Fit]); pinch / double-tap zooms in,
 *   drag pans, and at fit scale a horizontal swipe pages to the previous/next item.
 *   Edge-of-image → pager handoff is handled by ZoomImage's nested-scroll integration.
 * - Subsampling (large-image tiling) is auto-enabled by the Coil integration.
 *
 * Images load through the app's Coil singleton ([SingletonImageLoader]); auth lives in that
 * loader's Ktor fetcher, exactly like every other `AsyncImage` call site. The pager therefore
 * takes no `imageLoader`/auth params.
 *
 * Each surface supplies its own toolbar buttons (save / share / download) via [actions];
 * core/ui owns only the close button + page counter.
 */
@Composable
fun ZoomableMediaPager(
    items: List<MediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    closeContentDescription: String = "",
    defaultContentDescription: String = "",
    actions: @Composable RowScope.(MediaItem) -> Unit = {},
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { currentOnDismiss() }
        return
    }
    val startIndex = initialIndex.coerceIn(0, items.size - 1)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
        val platformContext = LocalPlatformContext.current
        val imageLoader = remember(platformContext) { SingletonImageLoader.get(platformContext) }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { items[it].url },
            ) { page ->
                val item = items[page]
                val zoomState = rememberCoilZoomState()
                // Reset zoom on pages that have scrolled off-screen so returning shows the
                // fit-to-screen rest state rather than a stale zoomed view.
                LaunchedEffect(pagerState.settledPage) {
                    if (pagerState.settledPage != page) {
                        zoomState.zoomable.reset()
                    }
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (item.url.isNotBlank()) {
                        var loadState by remember(item.url) { mutableStateOf(MediaLoadState.LOADING) }
                        CoilZoomAsyncImage(
                            model = item.url,
                            contentDescription = item.contentDescription.ifBlank { defaultContentDescription },
                            imageLoader = imageLoader,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            zoomState = zoomState,
                            onLoading = { loadState = MediaLoadState.LOADING },
                            onSuccess = { loadState = MediaLoadState.SUCCESS },
                            onError = { loadState = MediaLoadState.ERROR },
                        )
                        // Loading spinner / failure placeholder so a slow or failed load isn't an
                        // indefinitely blank black screen (the old viewers had explicit states).
                        when (loadState) {
                            MediaLoadState.LOADING ->
                                CircularProgressIndicator(color = Color.White)
                            MediaLoadState.ERROR -> BrokenImagePlaceholder()
                            MediaLoadState.SUCCESS -> Unit
                        }
                    } else {
                        // A blank URL has nothing to load and no callbacks fire, so show the same
                        // failure placeholder rather than an uninterpretable empty black page.
                        BrokenImagePlaceholder()
                    }
                }
            }

            val currentItem = items[pagerState.currentPage.coerceIn(0, items.size - 1)]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = closeContentDescription,
                        tint = Color.White,
                    )
                }
                if (items.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${items.size}",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions(currentItem)
                }
            }
        }
    }
}

private enum class MediaLoadState { LOADING, SUCCESS, ERROR }

/** Shown when a page fails to load or has no URL — the viewer's single failure affordance. */
@Composable
private fun BrokenImagePlaceholder() {
    Icon(
        imageVector = Icons.Filled.BrokenImage,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(64.dp),
    )
}
