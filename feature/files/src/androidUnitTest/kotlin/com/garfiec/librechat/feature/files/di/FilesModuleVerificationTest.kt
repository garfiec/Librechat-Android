package com.garfiec.librechat.feature.files.di

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.feature.files.platform.FileReader
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Test
import org.koin.test.verify.verify

class FilesModuleVerificationTest {
    @Test
    fun verifyFilesModule() {
        filesModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                FileRepository::class,
                ServerDataStore::class,
                SettingsDataStore::class,
                FileReader::class,
                CoroutineDispatcher::class,
            ),
        )
    }
}
