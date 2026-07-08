package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.feature.chat.viewmodel.ErrorOnlyHandle
import com.garfiec.librechat.feature.chat.viewmodel.TtsHandle
import com.garfiec.librechat.feature.chat.viewmodel.VoiceHandle

/**
 * Factory that creates platform-specific delegates once the narrowed handles are available.
 * This is necessary because delegates need the ViewModel's state handle and coroutine scope,
 * which aren't available until the ViewModel is constructed. Each delegate gets only the
 * narrowed handle it may write through.
 */
interface PlatformDelegateFactory {
    fun createFileHandler(handle: ErrorOnlyHandle): PlatformFileHandler
    fun createVoiceInput(
        handle: VoiceHandle,
        onTranscriptionComplete: () -> Unit,
    ): PlatformVoiceInput
    fun createTts(
        handle: TtsHandle,
        getMessageText: (String) -> String,
    ): PlatformTts
    fun createShareConsumer(): PlatformShareConsumer
}
