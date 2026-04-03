package com.librechat.android.feature.auth.di

import com.librechat.android.feature.auth.viewmodel.ForgotPasswordViewModel
import com.librechat.android.feature.auth.viewmodel.LoginViewModel
import com.librechat.android.feature.auth.viewmodel.RegisterViewModel
import com.librechat.android.feature.auth.viewmodel.ResetPasswordViewModel
import com.librechat.android.feature.auth.viewmodel.ServerUrlViewModel
import com.librechat.android.feature.auth.viewmodel.TermsViewModel
import com.librechat.android.feature.auth.viewmodel.TwoFactorViewModel
import com.librechat.android.feature.auth.viewmodel.VerifyEmailViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val authModule = module {
    viewModelOf(::ServerUrlViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::RegisterViewModel)
    viewModelOf(::VerifyEmailViewModel)
    viewModelOf(::ForgotPasswordViewModel)
    viewModelOf(::ResetPasswordViewModel)
    viewModelOf(::TwoFactorViewModel)
    viewModelOf(::TermsViewModel)
}
