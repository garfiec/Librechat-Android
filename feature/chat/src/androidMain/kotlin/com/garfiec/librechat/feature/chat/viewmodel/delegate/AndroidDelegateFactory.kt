package com.garfiec.librechat.feature.chat.viewmodel.delegate

import android.content.Context
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.feature.chat.viewmodel.ErrorOnlyHandle
import com.garfiec.librechat.feature.chat.viewmodel.TtsHandle
import com.garfiec.librechat.feature.chat.viewmodel.VoiceHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class AndroidDelegateFactory(
    private val appContext: Context,
    private val fileRepository: FileRepository,
    private val speechRepository: SpeechRepository,
    private val settingsDataStore: SettingsDataStore,
    private val ioDispatcher: CoroutineDispatcher,
) : PlatformDelegateFactory {

    override fun createFileHandler(handle: ErrorOnlyHandle): PlatformFileHandler {
        return AndroidFileHandler(
            FileAttachmentDelegate(
                handle = handle,
                appContext = appContext,
                fileRepository = fileRepository,
                ioDispatcher = ioDispatcher,
            ),
        )
    }

    override fun createVoiceInput(
        handle: VoiceHandle,
        onTranscriptionComplete: () -> Unit,
    ): PlatformVoiceInput {
        return AndroidVoiceInput(
            VoiceInputDelegate(
                handle = handle,
                appContext = appContext,
                speechRepository = speechRepository,
                autoSendAfterStt = settingsDataStore.autoSendAfterStt
                    .stateIn(handle.scope, SharingStarted.Eagerly, false),
                sttEngine = settingsDataStore.sttEngine
                    .stateIn(handle.scope, SharingStarted.Eagerly, ""),
                sttLanguage = settingsDataStore.sttLanguage
                    .stateIn(handle.scope, SharingStarted.Eagerly, ""),
                sttOnDevice = settingsDataStore.sttOnDevice
                    .stateIn(handle.scope, SharingStarted.Eagerly, true),
                sttEndOfSpeech = settingsDataStore.sttEndOfSpeech
                    .stateIn(handle.scope, SharingStarted.Eagerly, false),
                ioDispatcher = ioDispatcher,
                onTranscriptionComplete = onTranscriptionComplete,
            ),
        )
    }

    override fun createTts(
        handle: TtsHandle,
        getMessageText: (String) -> String,
    ): PlatformTts {
        return AndroidTts(
            TextToSpeechDelegate(
                handle = handle,
                appContext = appContext,
                speechRepository = speechRepository,
                settingsDataStore = settingsDataStore,
                ioDispatcher = ioDispatcher,
                getMessageText = getMessageText,
            ),
        )
    }

    override fun createShareConsumer(): PlatformShareConsumer {
        return AndroidShareConsumer()
    }
}
