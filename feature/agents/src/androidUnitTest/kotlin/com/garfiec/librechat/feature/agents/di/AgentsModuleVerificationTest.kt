package com.garfiec.librechat.feature.agents.di

import android.app.Application
import android.content.Context
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.AgentToolsRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.PermissionsRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Test
import org.koin.test.verify.verify

class AgentsModuleVerificationTest {
    @Test
    fun verifyAgentsModule() {
        agentsModule.verify(
            extraTypes = listOf(
                Context::class,
                Application::class,
                AgentRepository::class,
                AgentToolsRepository::class,
                ConfigRepository::class,
                FileRepository::class,
                McpRepository::class,
                PermissionsRepository::class,
                RoleRepository::class,
                SkillsRepository::class,
                PermissionGate::class,
                ServerDataStore::class,
                // Provided by core:common CommonModule via KoinQualifiers.IO.
                CoroutineDispatcher::class,
            ),
        )
    }
}
