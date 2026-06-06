package com.garfiec.librechat.feature.agents.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.feature.agents.viewmodel.AgentAclViewModel
import com.garfiec.librechat.feature.agents.viewmodel.AgentDetailViewModel
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorViewModel
import com.garfiec.librechat.feature.agents.viewmodel.AgentMarketplaceViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

expect val agentsPlatformModule: Module

val agentsModule = module {
    includes(agentsPlatformModule)

    viewModelOf(::AgentMarketplaceViewModel)
    viewModelOf(::AgentAclViewModel)
    // Koin's constructor-DSL (`viewModelOf`) wires every argument via `get()` and cannot read
    // values passed through `parametersOf`. Both VMs below receive `initialAgentId` from the
    // navigation layer via `parametersOf`, so the lambda-form `viewModel { params -> ... }` is
    // the only DSL that works here. Detekt's `DeprecatedKoinApi` is a blanket stylistic rule,
    // not a real `@Deprecated` API, so we suppress it in the narrow places it applies.
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        AgentDetailViewModel(
            agentRepository = get(),
            serverDataStore = get(),
            initialAgentId = params.getOrNull(),
        )
    }
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        AgentEditorViewModel(
            agentRepository = get(),
            configRepository = get(),
            mcpRepository = get(),
            agentToolsRepository = get(),
            fileRepository = get(),
            skillsRepository = get(),
            roleRepository = get(),
            contentReader = get(),
            ioDispatcher = get(KoinQualifiers.IO),
            initialAgentId = params.getOrNull(),
        )
    }
}
