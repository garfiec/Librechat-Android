package com.librechat.android.feature.voice.screen.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.librechat.android.feature.voice.screen.voiceFeedbackGlowColor
import com.librechat.android.feature.voice.screen.voiceFeedbackLoadingGlowColor
import com.librechat.android.feature.voice.viewmodel.VoicePhase

@Composable
fun VoiceOrb(
    phase: VoicePhase,
    modifier: Modifier = Modifier,
    loadingSession: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "voice_orb")
    val pulse = transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    loadingSession -> 900
                    phase == VoicePhase.LISTENING -> 600
                    else -> 1200
                },
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb_pulse",
    ).value

    val wave = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (phase == VoicePhase.THINKING) 900 else 1500,
            ),
        ),
        label = "orb_wave",
    ).value

    Canvas(modifier = modifier.size(180.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.22f

        val glowColor =
            if (loadingSession) voiceFeedbackLoadingGlowColor() else voiceFeedbackGlowColor(phase)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor.copy(alpha = 0.34f), Color.Transparent),
                center = center,
                radius = baseRadius * 3.6f,
            ),
            radius = baseRadius * 3.6f,
            center = center,
        )

        drawCircle(
            color = glowColor.copy(alpha = 0.85f),
            radius = baseRadius * pulse,
            center = center,
        )

        if (phase == VoicePhase.THINKING || phase == VoicePhase.SPEAKING) {
            val ringRadius = baseRadius * (1.2f + wave)
            drawCircle(
                color = glowColor.copy(alpha = (1f - wave) * 0.65f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 4f),
            )
        }
    }
}
