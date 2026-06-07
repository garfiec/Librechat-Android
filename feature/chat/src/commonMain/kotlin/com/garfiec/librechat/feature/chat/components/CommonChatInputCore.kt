package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.cd_send_message
import com.garfiec.librechat.feature.chat.resources.cd_start_voice_recording
import com.garfiec.librechat.feature.chat.resources.cd_stop_generation
import com.garfiec.librechat.feature.chat.resources.hint_message
import com.garfiec.librechat.feature.chat.resources.hint_message_model
import com.garfiec.librechat.feature.chat.resources.recording
import com.garfiec.librechat.feature.chat.viewmodel.ChatInputGates
import org.jetbrains.compose.resources.stringResource

@Immutable
data class ChatInputState(
    val inputText: String,
    val isStreaming: Boolean,
    val isRecording: Boolean,
    val isTranscribing: Boolean,
    val enabledTools: Set<String>,
    val mcpServers: List<McpServerDisplayData>,
    val selectedMcpServerNames: Set<String>,
    val selectedModelDisplay: String?,
    val isCodeInterpreterAvailable: Boolean,
    val attachedFiles: List<AttachedFile>,
    /** Ephemeral-tools gate drives local chip display; remaining gates are threaded to the sheet. */
    val gates: ChatInputGates = ChatInputGates(),
)

/**
 * Shared container for the chat input area. Renders the gradient background,
 * attachment chips row, and a slot-based input row with a shared send/stop button.
 *
 * Platform wrappers fill in [leadingButtons] (e.g. "+" button, paste button)
 * and [textFieldContent] (platform-specific text field with mic placement).
 */
@Composable
fun CommonChatInputCore(
    state: ChatInputState,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveFile: (AttachedFile) -> Unit,
    modifier: Modifier = Modifier,
    leadingButtons: @Composable RowScope.() -> Unit = {},
    trailingSpacer: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable BoxScope.() -> Unit = {},
    textFieldContent: @Composable RowScope.() -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        surfaceColor.copy(alpha = 0.7f),
                        surfaceColor.copy(alpha = 0.95f),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            AttachmentChipsRow(
                attachedFiles = state.attachedFiles,
                enabledTools = state.enabledTools,
                onRemoveFile = onRemoveFile,
                mcpServers = state.mcpServers,
                selectedMcpServerNames = state.selectedMcpServerNames,
                showEphemeralTools = state.gates.showEphemeralTools,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingButtons()
                textFieldContent()
                trailingSpacer()
                SendStopButton(
                    isStreaming = state.isStreaming,
                    canSend = state.inputText.isNotBlank() || state.attachedFiles.isNotEmpty(),
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
        bottomContent()
    }
}

/**
 * Animated send/stop toggle button shared between platforms.
 */
@Composable
fun SendStopButton(
    isStreaming: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = isStreaming,
        transitionSpec = {
            (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
        },
        label = "send_stop_toggle",
        modifier = modifier,
    ) { streaming ->
        if (streaming) {
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(Res.string.cd_stop_generation),
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                        ),
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                modifier = Modifier.size(56.dp),
                enabled = canSend,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    contentColor = if (canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(Res.string.cd_send_message),
                )
            }
        }
    }
}

/**
 * Voice mic visual indicator: shows transcription spinner, recording pulse, or idle mic icon.
 * Used inside both platform mic buttons (which differ in placement).
 */
@Composable
fun VoiceMicIndicator(
    isRecording: Boolean,
    isTranscribing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isTranscribing) {
        CircularProgressIndicator(
            modifier = modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    } else if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition(
            label = "recording_pulse",
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        )
        Box(
            modifier = modifier
                .size(20.dp)
                .alpha(pulseAlpha)
                .background(
                    color = MaterialTheme.colorScheme.error,
                    shape = CircleShape,
                ),
        )
    } else {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = stringResource(Res.string.cd_start_voice_recording),
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shared placeholder text for the chat input field.
 */
@Composable
fun ChatInputPlaceholder(
    isRecording: Boolean,
    selectedModelDisplay: String?,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (isRecording) {
            stringResource(Res.string.recording)
        } else if (!selectedModelDisplay.isNullOrBlank()) {
            stringResource(Res.string.hint_message_model, selectedModelDisplay)
        } else {
            stringResource(Res.string.hint_message)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
