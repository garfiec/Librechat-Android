package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class IosDelegateFactory(
    private val fileRepository: FileRepository,
    private val speechRepository: SpeechRepository,
    private val settingsDataStore: SettingsDataStore,
    private val ioDispatcher: CoroutineDispatcher,
) : PlatformDelegateFactory {

    override fun createFileHandler(stateHandle: ChatStateHandle): PlatformFileHandler {
        return IosFileHandler(stateHandle, fileRepository, ioDispatcher)
    }

    override fun createVoiceInput(
        stateHandle: ChatStateHandle,
        onTranscriptionComplete: () -> Unit,
    ): PlatformVoiceInput {
        return IosVoiceInput(
            stateHandle = stateHandle,
            autoSendAfterStt = settingsDataStore.autoSendAfterStt
                .stateIn(stateHandle.scope, SharingStarted.Eagerly, false),
            sttOnDevice = settingsDataStore.sttOnDevice
                .stateIn(stateHandle.scope, SharingStarted.Eagerly, true),
            sttEndOfSpeech = settingsDataStore.sttEndOfSpeech
                .stateIn(stateHandle.scope, SharingStarted.Eagerly, false),
            sttLanguage = settingsDataStore.sttLanguage
                .stateIn(stateHandle.scope, SharingStarted.Eagerly, ""),
            onTranscriptionComplete = onTranscriptionComplete,
        )
    }

    override fun createTts(
        stateHandle: ChatStateHandle,
        getMessageText: (String) -> String,
    ): PlatformTts {
        return IosTts(
            stateHandle = stateHandle,
            speechRepository = speechRepository,
            settingsDataStore = settingsDataStore,
            getMessageText = getMessageText,
        )
    }

    override fun createShareConsumer(): PlatformShareConsumer {
        return IosShareConsumer()
    }
}
