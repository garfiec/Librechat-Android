package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.feature.chat.ShareIntentConsumer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Android implementation of [PlatformShareConsumer] wrapping [ShareIntentConsumer].
 */
class AndroidShareConsumer : PlatformShareConsumer {

    override fun sharesFor(conversationId: String?): Flow<ShareData> =
        ShareIntentConsumer.sharesFor(conversationId).map { shared ->
            ShareData(text = shared.text, fileRefs = shared.fileUris)
        }
}
