package com.garfiec.librechat.feature.chat.viewmodel.delegate

import kotlinx.coroutines.flow.Flow

/**
 * Platform-abstracted share intent consumer.
 * Android: wraps ShareIntentConsumer (share sheet intents).
 * iOS: no-op (iOS share extensions handled differently).
 */
interface PlatformShareConsumer {
    /**
     * Content shared into the app *and addressed to this chat* — `null` for the `NewChat` landing.
     * Addressed, not broadcast: several chat screens are alive at once, and an unaddressed share is
     * claimed by whichever one collects first, which is not necessarily the visible one.
     */
    fun sharesFor(conversationId: String?): Flow<ShareData>
}

data class ShareData(
    val text: String? = null,
    val fileRefs: List<Any> = emptyList(),
)
