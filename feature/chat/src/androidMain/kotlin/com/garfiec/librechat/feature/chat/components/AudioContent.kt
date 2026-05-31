package com.garfiec.librechat.feature.chat.components

import android.util.Base64
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
actual fun AudioContent(
    data: String?,
    format: String?,
    modifier: Modifier,
) {
    if (data.isNullOrBlank()) return

    val context = LocalContext.current
    val mimeType = when (format?.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        else -> "audio/wav"
    }

    // Base64 decode is heavy for large clips; do it off the composition thread.
    val audioBytes by produceState<ByteArray?>(initialValue = null, data) {
        value = withContext(Dispatchers.Default) {
            try {
                Base64.decode(data, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    // ExoPlayer must be created/accessed on the thread it will be used (Main); keep it here,
    // built only once the decoded bytes are available.
    val exoPlayer = remember(audioBytes) {
        val bytes = audioBytes ?: return@remember null
        val dataSource = ByteArrayDataSource(bytes)
        ExoPlayer.Builder(context).build().apply {
            val mediaSource = ProgressiveMediaSource.Factory { dataSource }
                .createMediaSource(MediaItem.fromUri("data:$mimeType;base64,placeholder"))
            setMediaSource(mediaSource)
            prepare()
        }
    }

    if (exoPlayer == null) return

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        factory = {
            PlayerView(it).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                player = exoPlayer
                useController = true
                controllerShowTimeoutMs = 0
                showController()
            }
        },
    )
}
