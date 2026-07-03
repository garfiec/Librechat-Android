package com.garfiec.librechat.feature.auth.di

import com.garfiec.librechat.feature.auth.viewmodel.ForgotPasswordViewModel
import com.garfiec.librechat.feature.auth.viewmodel.LoginViewModel
import com.garfiec.librechat.feature.auth.viewmodel.RegisterViewModel
import com.garfiec.librechat.feature.auth.viewmodel.ResetPasswordViewModel
import com.garfiec.librechat.feature.auth.viewmodel.ServerUrlViewModel
import com.garfiec.librechat.feature.auth.viewmodel.TermsViewModel
import com.garfiec.librechat.feature.auth.viewmodel.TwoFactorViewModel
import com.garfiec.librechat.feature.auth.viewmodel.VerifyEmailViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

expect val authPlatformModule: Module

val authModule = module {
    includes(authPlatformModule)
    // Lambda form (not viewModelOf) for the addAccount mode flag the add-account nav entry passes
    // via parametersOf — see the DeprecatedKoinApi note below.
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        ServerUrlViewModel(
            serverDataStore = get(),
            configRepository = get(),
            accountSwitcher = get(),
            addAccount = params.getOrNull() ?: false,
        )
    }
    viewModelOf(::LoginViewModel)
    viewModelOf(::RegisterViewModel)
    // Koin's constructor-DSL (`viewModelOf`) wires every argument via `get()` and cannot read
    // values passed through `parametersOf`. The VMs below receive initial seeds (email, user
    // id, token, temp token) from the navigation layer via `parametersOf`, so the lambda-form
    // `viewModel { params -> ... }` is the only DSL that works here. Detekt's `DeprecatedKoinApi`
    // is a blanket stylistic rule, not a real `@Deprecated` API, so we suppress it in the
    // narrow places it applies.
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        VerifyEmailViewModel(
            userRepository = get(),
            initialEmail = params.getOrNull(),
        )
    }
    viewModelOf(::ForgotPasswordViewModel)
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        ResetPasswordViewModel(
            authRepository = get(),
            initialUserId = params.getOrNull(),
            initialToken = params.getOrNull(),
        )
    }
    @Suppress("DeprecatedKoinApi")
    viewModel { params ->
        TwoFactorViewModel(
            authRepository = get(),
            initialTempToken = params.getOrNull(),
        )
    }
    viewModelOf(::TermsViewModel)
}
