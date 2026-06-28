package com.garfiec.librechat.core.data.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.data.datastore.ConfigCacheDataStore
import com.garfiec.librechat.core.data.datastore.RoleCacheDataStore
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.datastore.ThemeDataStore
import com.garfiec.librechat.core.data.db.LibreChatDatabase
import com.garfiec.librechat.core.data.repository.AccountClaimReconciler
import com.garfiec.librechat.core.data.repository.AccountDataPurger
import com.garfiec.librechat.core.data.repository.AccountSessionEstablisher
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.AgentRepositoryImpl
import com.garfiec.librechat.core.data.repository.AgentToolsRepository
import com.garfiec.librechat.core.data.repository.AgentToolsRepositoryImpl
import com.garfiec.librechat.core.data.repository.ApiKeyRepository
import com.garfiec.librechat.core.data.repository.ApiKeyRepositoryImpl
import com.garfiec.librechat.core.data.repository.AuthRepository
import com.garfiec.librechat.core.data.repository.AuthRepositoryImpl
import com.garfiec.librechat.core.data.repository.BalanceRepository
import com.garfiec.librechat.core.data.repository.BalanceRepositoryImpl
import com.garfiec.librechat.core.data.repository.BannerRepository
import com.garfiec.librechat.core.data.repository.BannerRepositoryImpl
import com.garfiec.librechat.core.data.repository.ChatRepository
import com.garfiec.librechat.core.data.repository.ChatRepositoryImpl
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConfigRepositoryImpl
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.ConversationRepositoryImpl
import com.garfiec.librechat.core.data.repository.DraftRepository
import com.garfiec.librechat.core.data.repository.DraftRepositoryImpl
import com.garfiec.librechat.core.data.repository.FavoritesRepository
import com.garfiec.librechat.core.data.repository.FavoritesRepositoryImpl
import com.garfiec.librechat.core.data.repository.FileRepository
import com.garfiec.librechat.core.data.repository.FileRepositoryImpl
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.data.repository.KeyRepositoryImpl
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.repository.McpRepositoryImpl
import com.garfiec.librechat.core.data.repository.MemoryRepository
import com.garfiec.librechat.core.data.repository.MemoryRepositoryImpl
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.data.repository.MessageRepositoryImpl
import com.garfiec.librechat.core.data.repository.PermissionsRepository
import com.garfiec.librechat.core.data.repository.PermissionsRepositoryImpl
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.PresetRepositoryImpl
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.data.repository.PromptRepositoryImpl
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.data.repository.RoleRepositoryImpl
import com.garfiec.librechat.core.data.repository.SearchRepository
import com.garfiec.librechat.core.data.repository.SearchRepositoryImpl
import com.garfiec.librechat.core.data.repository.ShareRepository
import com.garfiec.librechat.core.data.repository.ShareRepositoryImpl
import com.garfiec.librechat.core.data.repository.SkillsRepository
import com.garfiec.librechat.core.data.repository.SkillsRepositoryImpl
import com.garfiec.librechat.core.data.repository.SpeechRepository
import com.garfiec.librechat.core.data.repository.SpeechRepositoryImpl
import com.garfiec.librechat.core.data.repository.TagRepository
import com.garfiec.librechat.core.data.repository.TagRepositoryImpl
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.repository.UserRepositoryImpl
import com.garfiec.librechat.core.data.util.EndpointConfigFetchSessionTask
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.data.util.RefreshTagsSessionTask
import com.garfiec.librechat.core.data.util.RoleFetchSessionTask
import com.garfiec.librechat.core.data.util.SessionTask
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.data.util.SyncFavoritesSessionTask
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

expect val dataPlatformModule: Module

val dataModule = module {

    includes(dataPlatformModule)

    // --- DAOs ---

    single { get<LibreChatDatabase>().conversationDao() }
    single { get<LibreChatDatabase>().messageDao() }
    single { get<LibreChatDatabase>().agentDao() }
    single { get<LibreChatDatabase>().presetDao() }
    single { get<LibreChatDatabase>().conversationTagDao() }
    single { get<LibreChatDatabase>().draftDao() }
    single { get<LibreChatDatabase>().accountClaimDao() }

    // --- Account identity (row-tenancy) ---

    // The in-memory active-account holder every identity-dependent subsystem will read.
    single<ActiveAccountProvider> { InMemoryActiveAccountProvider() }
    // Eager: at cold start it must seed the provider from the persisted id (chaining the URL
    // warm-up) even before any consumer asks for it.
    single(createdAtStart = true) {
        AccountRegistry(
            dataStore = get(),
            activeAccountProvider = get(),
            serverUrlProvider = get(),
            appScope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
    single {
        AccountClaimReconciler(
            claimDao = get(),
            dataStore = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
    single {
        AccountSessionEstablisher(
            accountRegistry = get(),
            claimReconciler = get(),
            serverUrlProvider = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
    single {
        AccountDataPurger(
            conversationDao = get(),
            messageDao = get(),
            draftDao = get(),
            tagDao = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
    // Sole owner of account-Session transitions. Lazy (not createdAtStart): instantiated when the
    // logout path (AuthRepositoryImpl) first resolves it, at which point its collector starts. Its
    // `current` session flow has no consumer yet — the SessionWriter facade that would is deferred.
    single {
        SessionManager(
            activeAccountProvider = get(),
            appScope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
        )
    }

    // --- Datastores ---

    single {
        ServerDataStore(
            dataStore = get(),
            appScope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
            ioDispatcher = get(KoinQualifiers.IO),
            keychainFallback = getOrNull(),
        )
    } bind ServerUrlProvider::class
    single {
        ThemeDataStore(
            dataStore = get(),
            appScope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
    singleOf(::ConfigCacheDataStore)
    singleOf(::RoleCacheDataStore)
    single {
        SettingsDataStore(
            dataStore = get(),
            appScope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }

    // --- Repositories (special wiring) ---

    single<AuthRepository> {
        AuthRepositoryImpl(
            authApi = get(),
            userApi = get(),
            tokenManager = get(),
            sessionCacheCleaner = get(),
            sessionTaskRunner = get(),
            accountSessionEstablisher = get(),
            accountRegistry = get(),
            activeAccountProvider = get(),
            sessionManager = get(),
            accountDataPurger = get(),
        )
    }

    single<RoleRepository> {
        RoleRepositoryImpl(
            rolesApi = get(),
            userRepository = get(),
            cacheDataStore = get(),
            applicationScope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
        )
    }

    singleOf(::PermissionGate)

    // --- Session tasks ---
    // Work that runs whenever the app transitions into an authenticated session.
    // Fires from two places only: AuthRepositoryImpl's login/OAuth/2FA success paths
    // and NavHostViewModel.init when a session is restored at cold-start.
    // Add new tasks here.
    singleOf(::RoleFetchSessionTask) bind SessionTask::class
    singleOf(::RefreshTagsSessionTask) bind SessionTask::class
    singleOf(::SyncFavoritesSessionTask) bind SessionTask::class
    singleOf(::EndpointConfigFetchSessionTask) bind SessionTask::class
    single {
        SessionTaskRunner(
            tasks = getAll<SessionTask>(),
            applicationScope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
        )
    }

    single<ChatRepository> {
        ChatRepositoryImpl(
            chatApi = get(),
            sseClient = get(),
            connectivityObserver = get(),
            dispatcher = get(KoinQualifiers.Default),
        )
    }

    single<MessageRepository> {
        MessageRepositoryImpl(
            messagesApi = get(),
            messageDao = get(),
            activeAccountProvider = get(),
            dispatcher = get(KoinQualifiers.Default),
        )
    }

    single<DraftRepository> {
        DraftRepositoryImpl(
            draftDao = get(),
            activeAccountProvider = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }

    // --- Repositories (simple auto-wiring) ---

    singleOf(::BalanceRepositoryImpl) bind BalanceRepository::class
    singleOf(::ConfigRepositoryImpl) bind ConfigRepository::class
    singleOf(::ConversationRepositoryImpl) bind ConversationRepository::class
    singleOf(::FileRepositoryImpl) bind FileRepository::class
    singleOf(::AgentRepositoryImpl) bind AgentRepository::class
    singleOf(::AgentToolsRepositoryImpl) bind AgentToolsRepository::class
    singleOf(::TagRepositoryImpl) bind TagRepository::class
    singleOf(::SearchRepositoryImpl) bind SearchRepository::class
    singleOf(::KeyRepositoryImpl) bind KeyRepository::class
    singleOf(::ApiKeyRepositoryImpl) bind ApiKeyRepository::class
    singleOf(::McpRepositoryImpl) bind McpRepository::class
    singleOf(::MemoryRepositoryImpl) bind MemoryRepository::class
    singleOf(::PermissionsRepositoryImpl) bind PermissionsRepository::class
    singleOf(::PresetRepositoryImpl) bind PresetRepository::class
    singleOf(::PromptRepositoryImpl) bind PromptRepository::class
    singleOf(::ShareRepositoryImpl) bind ShareRepository::class
    singleOf(::SkillsRepositoryImpl) bind SkillsRepository::class
    singleOf(::SpeechRepositoryImpl) bind SpeechRepository::class
    singleOf(::UserRepositoryImpl) bind UserRepository::class
    singleOf(::BannerRepositoryImpl) bind BannerRepository::class
    singleOf(::FavoritesRepositoryImpl) bind FavoritesRepository::class
}
