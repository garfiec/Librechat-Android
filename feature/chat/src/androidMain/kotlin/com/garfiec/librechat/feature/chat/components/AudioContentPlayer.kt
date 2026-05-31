package com.garfiec.librechat.feature.chat.components

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.Locale

/** Plays audio via MediaPlayer with play/pause and seekbar. Polls progress every 250ms. Releases on dispose. */
@Composable
actual fun AudioContentPlayer(
    audioUrl: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentMs by remember { mutableIntStateOf(0) }
    var isPrepared by remember { mutableStateOf(false) }

    val mediaPlayer = remember {
        MediaPlayer().apply {
            setOnCompletionListener {
                isPlaying = false
                progress = 0f
                currentMs = 0
                seekTo(0)
            }
            setOnPreparedListener {
                isPrepared = true
                durationMs = it.duration
            }
            setOnErrorListener { _, what, extra ->
                Logger.e { "MediaPlayer error: what=$what, extra=$extra" }
                isPlaying = false
                true
            }
        }
    }

    // Prepare media source
    LaunchedEffect(audioUrl) {
        try {
            mediaPlayer.reset()
            isPrepared = false
            mediaPlayer.setDataSource(audioUrl)
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Logger.e(e) { "Failed to set data source" }
        }
    }

    // Progress polling while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            try {
                val pos = mediaPlayer.currentPosition
                currentMs = pos
                val dur = mediaPlayer.duration
                if (dur > 0) {
                    progress = pos.toFloat() / dur
                }
            } catch (_: IllegalStateException) {
                break
            }
            delay(250L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer.release()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (!isPrepared) return@IconButton
                    if (isPlaying) {
                        mediaPlayer.pause()
                        isPlaying = false
                    } else {
                        mediaPlayer.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) Res.string.cd_pause else Res.string.cd_play),
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = formatDuration(currentMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatDuration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Variant of [AudioContentPlayer] that writes raw bytes to a temp file before playback. */
@Composable
actual fun AudioContentPlayerFromBytes(
    audioBytes: ByteArray,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // Writing the temp file is blocking; do it off the composition thread.
    val tempFile by produceState<File?>(initialValue = null, audioBytes) {
        value = withContext(Dispatchers.IO) {
            val audioDir = File(context.cacheDir, "audio").apply { mkdirs() }
            File.createTempFile("audio_", ".mp3", audioDir).apply {
                writeBytes(audioBytes)
            }
        }
    }
    val file = tempFile ?: return
    DisposableEffect(file) {
        onDispose {
            file.delete()
        }
    }
    AudioContentPlayer(
        audioUrl = file.absolutePath,
        modifier = modifier,
    )
}

private fun formatDuration(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
