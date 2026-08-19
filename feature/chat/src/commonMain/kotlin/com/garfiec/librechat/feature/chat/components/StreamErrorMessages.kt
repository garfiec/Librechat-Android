package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Composable
import com.garfiec.librechat.core.model.error.StreamErrorType
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Resolves the shared error channel's value for display, swapping a typed marker for the localized
 * sentence that says what the user can actually do about it.
 *
 * **Anything unrecognized passes through unchanged.** That is the contract, not a fallback: the
 * channel carries server-authored text and app-authored text as well as markers, a newer server
 * will emit codes this build has never heard of, and none of those may reach the user as a bare
 * identifier or an empty string.
 *
 * Both platform error surfaces call this — the value they render must not depend on which screen
 * the user is on.
 */
@Composable
internal fun localizedStreamError(raw: String): String {
    if (!raw.startsWith(StreamErrorType.MARKER_PREFIX)) return raw
    val wire = raw.removePrefix(StreamErrorType.MARKER_PREFIX)
    // Matched on the enum rather than on the marker string so a code removed from the enum stops
    // resolving here too, instead of leaving a branch that can never be reached.
    val type = StreamErrorType.entries.firstOrNull { it.wire == wire } ?: return raw
    return when (type) {
        StreamErrorType.RESOURCE_RECOVERY_REQUIRED -> stringResource(Res.string.error_resource_recovery_required)
        StreamErrorType.MODEL_NOT_FOUND -> stringResource(Res.string.error_model_not_found)
        StreamErrorType.MISSING_MODEL -> stringResource(Res.string.error_missing_model)
        StreamErrorType.MODELS_NOT_LOADED -> stringResource(Res.string.error_models_not_loaded)
        StreamErrorType.ENDPOINT_MODELS_NOT_LOADED -> stringResource(Res.string.error_endpoint_models_not_loaded)
        StreamErrorType.INVALID_AGENT_PROVIDER -> stringResource(Res.string.error_invalid_agent_provider)
        StreamErrorType.REFUSAL -> stringResource(Res.string.error_refusal)
        StreamErrorType.INPUT_LENGTH -> stringResource(Res.string.error_input_length)
        StreamErrorType.MODERATION -> stringResource(Res.string.error_moderation)
        StreamErrorType.STREAM_EXPIRED -> stringResource(Res.string.error_stream_expired)
    }
}
