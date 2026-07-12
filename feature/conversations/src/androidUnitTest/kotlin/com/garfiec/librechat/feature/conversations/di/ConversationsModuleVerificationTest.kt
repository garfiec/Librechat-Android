package com.garfiec.librechat.feature.conversations.di

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.data.repository.ProjectRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SearchRepository
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.TagRepository
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Test
import org.koin.test.verify.verify

class ConversationsModuleVerificationTest {
    @Test
    fun verifyConversationsModule() {
        conversationsModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                ConversationRepository::class,
                MessageRepository::class,
                TagRepository::class,
                ShareRepository::class,
                SearchRepository::class,
                ConfigRepository::class,
                ProjectRepository::class,
                RoleRepository::class,
                ServerDataStore::class,
                CoroutineDispatcher::class,
                // DrawerViewModel deps sourced from other modules.
                ActiveAccountProvider::class,
                SettingsDataStore::class,
            ),
        )
    }
}
