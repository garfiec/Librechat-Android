package com.librechat.android.feature.voice.screen

import androidx.compose.ui.graphics.Color
import com.librechat.android.feature.voice.viewmodel.VoicePhase

/**
 * Shared accent hues for [com.librechat.android.feature.voice.screen.components.VoiceOrb]
 * and [VoiceAuroraBackdrop] so top feedback and bottom gradient stay in sync.
 */
fun voiceFeedbackGlowColor(phase: VoicePhase): Color =
    when (phase) {
        VoicePhase.LISTENING -> Color(0xFF4FC3F7)
        VoicePhase.TRANSCRIBING -> Color(0xFF9575CD)
        VoicePhase.THINKING -> Color(0xFFFFB74D)
        VoicePhase.SPEAKING -> Color(0xFF81C784)
        VoicePhase.IDLE -> Color(0xFF9E9E9E)
    }

/** Matches “loading conversation” in the title while the orb is still idle. */
fun voiceFeedbackLoadingGlowColor(): Color = Color(0xFF9575CD)

/** Pulse period for orb / aurora (first channel), aligned with [VoiceOrb]. */
fun voiceFeedbackPulseMillis(phase: VoicePhase, loadingSession: Boolean): Int =
    when {
        loadingSession -> 900
        phase == VoicePhase.LISTENING -> 600
        else -> 1200
    }
