package com.garfiec.librechat.feature.chat.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.data.repository.ArtifactShortcutRepository
import com.garfiec.librechat.core.model.ArtifactShortcut
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.core.ui.components.LoadingIndicator
import com.garfiec.librechat.feature.chat.components.artifact.Artifact
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactViewer
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.artifact_shortcut_back
import com.garfiec.librechat.feature.chat.resources.artifact_shortcut_unavailable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private sealed interface ShortcutLoad {
    data object Loading : ShortcutLoad

    // versions is the single-element list ArtifactViewer wants, built once here rather than per frame.
    data class Loaded(val artifact: Artifact, val versions: List<Artifact>) : ShortcutLoad
    data object NotFound : ShortcutLoad
}

/**
 * Full-screen viewer for a home-screen-pinned artifact. Loads the self-contained Room snapshot by
 * [snapshotId] (so it works on cold start / logged out) and renders it through the shared
 * [ArtifactViewer]. A missing snapshot (the user deleted it from the management screen while a launcher
 * icon lingered) shows an "unavailable" message rather than a blank screen.
 */
@Composable
fun ArtifactShortcutViewerScreen(
    snapshotId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = koinInject<ArtifactShortcutRepository>()
    var state by remember(snapshotId) { mutableStateOf<ShortcutLoad>(ShortcutLoad.Loading) }

    LaunchedEffect(snapshotId) {
        val snapshot = repository.get(snapshotId)
        state = if (snapshot == null) {
            ShortcutLoad.NotFound
        } else {
            val artifact = snapshot.toArtifact()
            ShortcutLoad.Loaded(artifact, listOf(artifact))
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (val current = state) {
            ShortcutLoad.Loading -> LoadingIndicator(modifier = Modifier.fillMaxSize())

            is ShortcutLoad.Loaded -> ArtifactViewer(
                artifact = current.artifact,
                versions = current.versions,
                isFullscreen = true,
                onClose = onBack,
                onExpand = null,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            )

            ShortcutLoad.NotFound -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    title = stringResource(Res.string.artifact_shortcut_unavailable),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(Res.string.artifact_shortcut_back))
                }
            }
        }
    }
}

private fun ArtifactShortcut.toArtifact() = Artifact(
    identifier = identifier,
    type = type,
    title = title,
    language = language,
    content = content,
    version = version,
)
