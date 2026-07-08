package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.feature.chat.viewmodel.ErrorOnlyHandle
import com.garfiec.librechat.feature.chat.viewmodel.TtsHandle
import com.garfiec.librechat.feature.chat.viewmodel.VoiceHandle
import kotlinx.coroutines.CoroutineDispatcher

class IosDelegateFactory(
    private val fileRepository: FileRepository,
    private val speechRepository: SpeechRepository,
    private val settingsDataStore: SettingsDataStore,
    private val ioDispatcher: CoroutineDispatcher,
) : PlatformDelegateFactory {

    override fun createFileHandler(handle: ErrorOnlyHandle): PlatformFileHandler {
        return IosFileHandler(handle, fileRepository, ioDispatcher)
    }

    override fun createVoiceInput(
        handle: VoiceHandle,
        onTranscriptionComplete: () -> Unit,
    ): PlatformVoiceInput {
        return IosVoiceInput(handle, onTranscriptionComplete)
    }

    override fun createTts(
        handle: TtsHandle,
        getMessageText: (String) -> String,
    ): PlatformTts {
        return IosTts(
            handle = handle,
            speechRepository = speechRepository,
            settingsDataStore = settingsDataStore,
            getMessageText = getMessageText,
        )
    }

    override fun createShareConsumer(): PlatformShareConsumer {
        return IosShareConsumer()
    }
}
