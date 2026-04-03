package com.librechat.android.feature.voice.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librechat.android.feature.voice.R
import com.librechat.android.feature.voice.screen.components.VoiceOrb
import com.librechat.android.feature.voice.viewmodel.VoicePhase
import com.librechat.android.feature.voice.viewmodel.VoiceSessionViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VoiceSessionScreen(
    conversationId: String?,
    endpoint: String?,
    model: String?,
    agentId: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceSessionViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phaseState = rememberUpdatedState(state.phase)
    var isMicHeld by remember { mutableStateOf(false) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            viewModel.onMicrophonePermissionDenied()
        }
    }

    LaunchedEffect(conversationId, endpoint, model, agentId) {
        viewModel.initializeSession(
            conversationId = conversationId,
            endpoint = endpoint,
            model = model,
            agentId = agentId,
        )
    }

    val micBusy = state.phase == VoicePhase.TRANSCRIBING || state.phase == VoicePhase.THINKING
    val micPressedVisual = isMicHeld || state.phase == VoicePhase.LISTENING

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = {
                viewModel.stopPlayback()
                onClose()
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.voice_close),
            )
        }

        Spacer(modifier = Modifier.height(40.dp))
        VoiceOrb(phase = state.phase)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = phaseLabel(state.phase),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(12.dp))
        if (state.error != null) {
            Text(
                text = state.error ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.lastUserText.isNotBlank()) {
            TranscriptLine(
                label = stringResource(R.string.voice_last_you),
                text = state.lastUserText,
            )
        }
        if (state.streamingText.isNotBlank() || state.lastAssistantText.isNotBlank()) {
            TranscriptLine(
                label = stringResource(R.string.voice_last_assistant),
                text = state.streamingText.ifBlank { state.lastAssistantText },
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Surface(
            shape = CircleShape,
            color = when {
                micBusy -> MaterialTheme.colorScheme.surfaceVariant
                micPressedVisual -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            shadowElevation = if (micPressedVisual) 8.dp else 2.dp,
            modifier = Modifier.pointerInput(Unit) {
                while (true) {
                    awaitEachGesture {
                        val phase = phaseState.value
                        if (phase == VoicePhase.SPEAKING) {
                            awaitFirstDown(requireUnconsumed = false)
                            viewModel.bargeIn()
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        if (phase == VoicePhase.TRANSCRIBING || phase == VoicePhase.THINKING) {
                            awaitFirstDown(requireUnconsumed = false)
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        awaitFirstDown(requireUnconsumed = false)
                        val canRecord = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!canRecord) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        isMicHeld = true
                        viewModel.startListening()
                        waitForUpOrCancellation()
                        isMicHeld = false
                        viewModel.stopListening()
                    }
                }
            },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
            ) {
                if (micBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (state.phase) {
                            VoicePhase.THINKING -> stringResource(R.string.voice_thinking)
                            else -> stringResource(R.string.voice_transcribing)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.voice_hold_to_talk),
                        tint = if (micPressedVisual) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                    Text(
                        text = stringResource(R.string.voice_hold_to_talk),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (micPressedVisual) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }
        }

        if (state.phase == VoicePhase.SPEAKING) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::stopPlayback,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.voice_stop_playback))
            }
        }
    }
}

@Composable
private fun TranscriptLine(
    label: String,
    text: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
        )
    }
}

@Composable
private fun phaseLabel(phase: VoicePhase): String = when (phase) {
    VoicePhase.IDLE -> stringResource(R.string.voice_idle)
    VoicePhase.LISTENING -> stringResource(R.string.voice_listening)
    VoicePhase.TRANSCRIBING -> stringResource(R.string.voice_transcribing)
    VoicePhase.THINKING -> stringResource(R.string.voice_thinking)
    VoicePhase.SPEAKING -> stringResource(R.string.voice_speaking)
}
