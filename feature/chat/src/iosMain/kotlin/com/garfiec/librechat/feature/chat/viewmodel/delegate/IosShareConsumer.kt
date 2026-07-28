package com.garfiec.librechat.feature.chat.viewmodel.delegate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS no-op implementation of [PlatformShareConsumer].
 * iOS doesn't use Android share intents.
 */
class IosShareConsumer : PlatformShareConsumer {
    override fun sharesFor(conversationId: String?): Flow<ShareData> = emptyFlow()
}
