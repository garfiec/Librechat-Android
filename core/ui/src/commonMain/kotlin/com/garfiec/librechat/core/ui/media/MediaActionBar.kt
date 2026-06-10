package com.garfiec.librechat.core.ui.media

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The standard save/share toolbar for [ZoomableMediaPager], shared by every surface that hosts the
 * viewer (chat, files) so the button set, icons, white tint, and the save null-guard live in one
 * place instead of being re-declared per surface.
 *
 * [onSave] is null on platforms/surfaces without gallery save (e.g. iOS), in which case the save
 * button is omitted. Content descriptions are passed in (resolved once by the caller, above the
 * pager) so paging between items doesn't re-resolve string resources on every swipe.
 *
 * No `modifier` param: this emits sibling buttons directly into the caller-owned [RowScope], so
 * there is no single root node to apply one to.
 */
@Suppress("ModifierMissing")
@Composable
fun RowScope.MediaActionBar(
    item: MediaItem,
    onSave: ((url: String) -> Unit)?,
    onShare: (url: String) -> Unit,
    saveContentDescription: String,
    shareContentDescription: String,
) {
    if (onSave != null) {
        IconButton(onClick = { onSave(item.url) }) {
            Icon(
                imageVector = Icons.Outlined.SaveAlt,
                contentDescription = saveContentDescription,
                tint = Color.White,
            )
        }
    }
    IconButton(onClick = { onShare(item.url) }) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = shareContentDescription,
            tint = Color.White,
        )
    }
}
