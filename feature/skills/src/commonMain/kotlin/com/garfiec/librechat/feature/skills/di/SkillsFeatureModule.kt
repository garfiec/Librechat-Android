package com.garfiec.librechat.feature.skills.di

import com.garfiec.librechat.feature.skills.viewmodel.SkillAclViewModel
import com.garfiec.librechat.feature.skills.viewmodel.SkillDetailViewModel
import com.garfiec.librechat.feature.skills.viewmodel.SkillEditorViewModel
import com.garfiec.librechat.feature.skills.viewmodel.SkillFilesViewModel
import com.garfiec.librechat.feature.skills.viewmodel.SkillsListViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val skillsFeatureModule = module {
    viewModelOf(::SkillsListViewModel)
    viewModelOf(::SkillAclViewModel)

    // SkillDetail/SkillEditor VMs receive skillId via parametersOf from the nav
    // layer, so the lambda-form viewModel { params -> ... } DSL is required
    // (viewModelOf can't read parametersOf). Detekt's DeprecatedKoinApi is a
    // blanket stylistic rule, not a real @Deprecated API.
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        SkillDetailViewModel(
            skillsRepository = get(),
            roleRepository = get(),
            skillId = params.get(),
        )
    }
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        SkillEditorViewModel(
            skillsRepository = get(),
            configRepository = get(),
            initialSkillId = params.getOrNull(),
        )
    }
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        SkillFilesViewModel(
            skillsRepository = get(),
            roleRepository = get(),
            skillId = params.get(),
        )
    }
}
