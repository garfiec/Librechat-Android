package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.staticCompositionLocalOf
import co.touchlab.kermit.Logger

/**
 * Carries the "open the full-screen media viewer" callback down to the leaf image composables
 * ([ImageContentPart], message image previews, [ImageGenCard]) so each tap dispatches to
 * [com.garfiec.librechat.feature.chat.viewmodel.ChatViewModel.openMedia], which owns the
 * branch-media list and viewer open-state.
 *
 * Provided by `ChatRoot`. Default is a logging no-op (not a silent swallow) so a missing
 * provider surfaces in logs instead of dropping taps. Precedent: [LocalSubagentProgress].
 */
val LocalChatMediaViewer = staticCompositionLocalOf<(url: String) -> Unit> {
    { url -> Logger.w { "LocalChatMediaViewer not provided; ignoring media open for $url" } }
}
