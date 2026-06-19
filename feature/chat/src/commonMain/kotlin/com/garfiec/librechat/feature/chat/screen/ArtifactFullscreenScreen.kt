package com.garfiec.librechat.feature.chat.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactViewer
import com.garfiec.librechat.feature.chat.components.artifact.ArtifactViewerHandoff
import org.koin.compose.koinInject

/**
 * Full-screen artifact viewer, presented as a navigation route so it inherits the
 * NavDisplay's predictive-back gestures. The artifact payload is read from the
 * in-process [ArtifactViewerHandoff] keyed by the route's [identifier]/[version];
 * if the slot is empty (e.g. process death restored the route) the screen pops itself.
 */
@Composable
fun ArtifactFullscreenScreen(
    identifier: String,
    version: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handoff = koinInject<ArtifactViewerHandoff>()
    val entry = handoff.peek(identifier, version)
    val latestOnBack by rememberUpdatedState(onBack)

    if (entry == null) {
        LaunchedEffect(Unit) { latestOnBack() }
        return
    }

    // Clear the slot when the route leaves so a stale payload can't linger.
    DisposableEffect(Unit) {
        onDispose { handoff.clear() }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        ArtifactViewer(
            artifact = entry.artifact,
            versions = entry.versions,
            isFullscreen = true,
            onClose = onBack,
            onExpand = null,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        )
    }
}
