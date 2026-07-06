package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.garfiec.librechat.core.data.repository.ArtifactShortcutRepository
import com.garfiec.librechat.core.model.ArtifactShortcut
import com.garfiec.librechat.core.model.artifactTypeLabel
import com.garfiec.librechat.core.model.displayGlyph
import com.garfiec.librechat.core.model.displayLabel
import com.garfiec.librechat.core.ui.components.EmptyState
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.artifact_shortcuts_disabled_message
import com.garfiec.librechat.feature.settings.resources.artifact_shortcuts_empty
import com.garfiec.librechat.feature.settings.resources.artifact_shortcuts_title
import com.garfiec.librechat.feature.settings.resources.cd_artifact_shortcut_delete
import com.garfiec.librechat.feature.settings.resources.cd_back
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Lists the artifacts the user pinned to the home screen and lets them remove one. Delete removes the
 * local snapshot and disables the launcher shortcut; since Android can't remove an already-placed icon,
 * a lingering icon becomes inert (tapping it shows the disabled message). The list reflects the app's
 * snapshots, not the launcher's exact set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactShortcutsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = koinInject<ArtifactShortcutRepository>()
    val shortcuts by repository.observeAll().collectAsStateWithLifecycle(emptyList())
    val disableShortcut = rememberDisableHomeScreenShortcut()
    val disabledMessage = stringResource(Res.string.artifact_shortcuts_disabled_message)
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.artifact_shortcuts_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (shortcuts.isEmpty()) {
            EmptyState(
                title = stringResource(Res.string.artifact_shortcuts_empty),
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                items(shortcuts, key = { it.id }) { shortcut ->
                    ArtifactShortcutRow(
                        shortcut = shortcut,
                        onDelete = {
                            // Disable the launcher icon first (best-effort), then drop the snapshot —
                            // both off the main thread (disableShortcut is a launcher binder IPC).
                            scope.launch {
                                withContext(Dispatchers.Default) { disableShortcut(shortcut.id, disabledMessage) }
                                repository.delete(shortcut.id)
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ArtifactShortcutRow(
    shortcut: ArtifactShortcut,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = shortcut.displayGlyph,
                fontSize = 22.sp,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortcut.displayLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artifactTypeLabel(shortcut.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(Res.string.cd_artifact_shortcut_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
