package com.garfiec.librechat.feature.auth.di

import com.garfiec.librechat.feature.auth.oauth.IosOAuthCookieStore
import com.garfiec.librechat.feature.auth.oauth.OAuthCookieStore
import org.koin.core.module.Module
import org.koin.dsl.module

actual val authPlatformModule: Module = module {
    single<OAuthCookieStore> { IosOAuthCookieStore() }
}