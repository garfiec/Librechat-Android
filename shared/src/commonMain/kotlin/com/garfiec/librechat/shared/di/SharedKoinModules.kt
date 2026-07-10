package com.garfiec.librechat.shared.di

import com.garfiec.librechat.core.common.di.commonModule
import com.garfiec.librechat.core.data.di.dataModule
import com.garfiec.librechat.core.logging.di.loggingModule
import com.garfiec.librechat.core.network.di.networkModule
import com.garfiec.librechat.feature.agents.di.agentsModule
import com.garfiec.librechat.feature.auth.di.authModule
import com.garfiec.librechat.feature.chat.di.chatModule
import com.garfiec.librechat.feature.conversations.di.conversationsModule
import com.garfiec.librechat.feature.files.di.filesModule
import com.garfiec.librechat.feature.settings.di.settingsModule
import com.garfiec.librechat.feature.skills.di.skillsFeatureModule
import com.garfiec.librechat.shared.navigation.sharedAppModule
import org.koin.core.module.Module

/**
 * The single Koin module list both platforms start from — the fix for the
 * cross-platform DI divergence where Android and iOS each hand-maintained a
 * separate list and a definition present on one but missing on the other
 * compiled, linked, and passed CI, then failed at runtime on device.
 *
 * Android (`LibreChatApplication`) loads this list directly; iOS
 * (`IosSharedModule`) `includes(sharedKoinModules)` and adds only its one
 * iOS-only binding (`LibreChatSDK`). Adding a feature module means editing
 * this one list, so whole-module divergence is impossible by construction.
 *
 * `authPlatformModule` is intentionally absent: `authModule` already
 * `includes(authPlatformModule)`, so listing it here would double-add it.
 * Engine + `SseHttpTransport` are platform-specific but arrive via
 * `networkModule.includes(networkPlatformModule)` (expect/actual), so they
 * need no per-platform entry here.
 */
val sharedKoinModules: List<Module> = listOf(
    commonModule,
    loggingModule,
    networkModule,
    dataModule,
    authModule,
    chatModule,
    conversationsModule,
    settingsModule,
    agentsModule,
    filesModule,
    skillsFeatureModule,
    sharedAppModule,
)
