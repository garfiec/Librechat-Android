package com.librechat.android.feature.voice.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.util.concurrent.atomic.AtomicBoolean

/** Hold the mic control this long before recording starts (reduces accidental triggers). */
private const val PushToTalkActivationDelayMs = 220L

/**
 * Unlike [waitForUpOrCancellation], only returns when [pointerId] actually lifts off the screen.
 * [waitForUpOrCancellation] can finish early on gesture cancellation (recomposition, conflicts)
 * even while the finger is still down, which was stopping recording prematurely.
 */
private suspend fun AwaitPointerEventScope.awaitPointerUp(pointerId: PointerId) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.changes.any { change ->
                change.id == pointerId && !change.pressed && change.previousPressed
            }
        ) {
            return
        }
    }
}

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
    val sessionReadyState = rememberUpdatedState(state.isSessionReady)
    var isMicHeld by remember { mutableStateOf(false) }
    /** True while finger is down on the mic (incl. activation delay); drives "Listening" vs released (no label). */
    var micFingerDown by remember { mutableStateOf(false) }
    val gestureScope = rememberCoroutineScope()

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

    val micProcessing =
        !state.isSessionReady ||
            state.phase == VoicePhase.TRANSCRIBING ||
            state.phase == VoicePhase.THINKING ||
            state.phase == VoicePhase.SPEAKING
    /** After release: transcribing / thinking / speaking — compact mic + cancel. */
    val showCompactProcessing =
        state.isSessionReady &&
            (state.phase == VoicePhase.TRANSCRIBING ||
                state.phase == VoicePhase.THINKING ||
                state.phase == VoicePhase.SPEAKING)
    val micDiameter = when {
        state.phase == VoicePhase.THINKING || state.phase == VoicePhase.SPEAKING -> 80.dp
        showCompactProcessing -> 104.dp
        else -> 152.dp
    }
    val micPressedVisual =
        micFingerDown || isMicHeld || state.phase == VoicePhase.LISTENING
    val showPushToTalkLabel =
        state.isSessionReady && state.phase == VoicePhase.IDLE && !micFingerDown
    val showListeningLabel = micFingerDown
    val loadingSession = !state.isSessionReady && !conversationId.isNullOrBlank()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        VoiceAuroraBackdrop(
            phase = state.phase,
            loadingSession = loadingSession,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.42f),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = {
                    viewModel.stopPlayback()
                    onClose()
                },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.voice_close),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            VoiceOrb(phase = state.phase, loadingSession = loadingSession)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when {
                    loadingSession -> stringResource(R.string.voice_loading_session)
                    else -> phaseLabel(state.phase)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = when {
                    loadingSession -> voiceFeedbackLoadingGlowColor()
                    else -> voiceFeedbackGlowColor(state.phase)
                },
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

            val transcriptScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(transcriptScroll),
            ) {
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
            }

            Spacer(modifier = Modifier.height(8.dp))

            val innerIconSize = if (showCompactProcessing) 32.dp else 40.dp
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-40).dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (micPressedVisual) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shadowElevation = if (micPressedVisual) 10.dp else 3.dp,
                    modifier = Modifier
                        .size(micDiameter)
                        .pointerInput(Unit) {
                            while (true) {
                                awaitEachGesture {
                                    val phase = phaseState.value
                                    if (phase == VoicePhase.TRANSCRIBING ||
                                        phase == VoicePhase.THINKING ||
                                        phase == VoicePhase.SPEAKING
                                    ) {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        viewModel.cancelVoicePipeline()
                                        awaitPointerUp(down.id)
                                        return@awaitEachGesture
                                    }
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    if (!sessionReadyState.value) {
                                        awaitPointerUp(down.id)
                                        return@awaitEachGesture
                                    }
                                    val canRecord = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!canRecord) {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        awaitPointerUp(down.id)
                                        return@awaitEachGesture
                                    }

                                    micFingerDown = true
                                    try {
                                        val recordingActivated = AtomicBoolean(false)
                                        val activationJob = gestureScope.launch {
                                            delay(PushToTalkActivationDelayMs)
                                            if (!isActive) return@launch
                                            recordingActivated.set(true)
                                            isMicHeld = true
                                            viewModel.startListening()
                                        }
                                        try {
                                            awaitPointerUp(down.id)
                                        } finally {
                                            activationJob.cancel()
                                        }
                                        if (recordingActivated.get()) {
                                            isMicHeld = false
                                            viewModel.stopListening()
                                        }
                                    } finally {
                                        micFingerDown = false
                                    }
                                }
                            }
                        },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when {
                            showListeningLabel -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        modifier = Modifier.size(40.dp),
                                        contentDescription = stringResource(R.string.voice_listening_button),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.voice_listening_button),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                            showPushToTalkLabel -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        modifier = Modifier.size(40.dp),
                                        contentDescription = stringResource(R.string.voice_push_to_talk),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.voice_push_to_talk),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            !state.isSessionReady -> {
                                Icon(
                                    imageVector = Icons.Outlined.MicOff,
                                    modifier = Modifier.size(44.dp),
                                    contentDescription = stringResource(R.string.voice_loading_session),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            state.phase == VoicePhase.TRANSCRIBING -> {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    modifier = Modifier.size(48.dp),
                                    contentDescription = stringResource(R.string.voice_cancel_processing_cd),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            state.phase == VoicePhase.THINKING ||
                                state.phase == VoicePhase.SPEAKING -> {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    modifier = Modifier.size(36.dp),
                                    contentDescription = stringResource(R.string.voice_cancel_processing_cd),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    modifier = Modifier.size(innerIconSize),
                                    contentDescription = stringResource(R.string.voice_push_to_talk),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun VoiceAuroraBackdrop(
    phase: VoicePhase,
    loadingSession: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent =
        if (loadingSession) voiceFeedbackLoadingGlowColor() else voiceFeedbackGlowColor(phase)
    val pulseMillis = voiceFeedbackPulseMillis(phase, loadingSession)
    val transition = rememberInfiniteTransition(label = "voice_aurora")
    val pulse = transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aurora_pulse",
    ).value

    val p = pulse
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.22f to accent.copy(alpha = 0.05f * p),
                    0.42f to accent.copy(alpha = 0.10f * p),
                    0.62f to accent.copy(alpha = 0.14f * p),
                    0.82f to accent.copy(alpha = 0.10f * p),
                    1f to accent.copy(alpha = 0.08f + 0.05f * p),
                ),
            ),
        ),
    )
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
        VoiceTranscriptMarkdown(
            text = text,
            modifier = Modifier.fillMaxWidth(),
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
