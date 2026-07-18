package com.garfiec.librechat.core.data.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.datastore.AccountScopedPrefsPurger
import com.garfiec.librechat.core.data.datastore.ConfigCacheDataStore
import com.garfiec.librechat.core.data.datastore.RoleCacheDataStore
import com.garfiec.librechat.core.data.datastore.ServerDataStore
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.datastore.ThemeDataStore
import com.garfiec.librechat.core.data.db.LibreChatDatabase
import com.garfiec.librechat.core.data.repository.AccountClaimReconciler
import com.garfiec.librechat.core.data.repository.AccountDataPurger
import com.garfiec.librechat.core.data.repository.AccountSessionEstablisher
import com.garfiec.librechat.core.data.repository.AccountSwitcher
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.AgentRepositoryImpl
import com.garfiec.librechat.core.data.repository.AgentToolsRepository
import com.garfiec.librechat.core.data.repository.AgentToolsRepositoryImpl
import com.garfiec.librechat.core.data.repository.ApiKeyRepository
import com.garfiec.librechat.core.data.repository.ApiKeyRepositoryImpl
import com.garfiec.librechat.core.data.repository.ArtifactShortcutRepository
import com.garfiec.librechat.core.data.repository.ArtifactShortcutRepositoryImpl
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
import com.garfiec.librechat.core.data.repository.EndpointTokenRepository
import com.garfiec.librechat.core.data.repository.EndpointTokenRepositoryImpl
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
import com.garfiec.librechat.core.data.repository.ProjectRepository
import com.garfiec.librechat.core.data.repository.ProjectRepositoryImpl
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
import com.garfiec.librechat.core.data.util.AccountLabelBackfillSessionTask
import com.garfiec.librechat.core.data.util.EndpointConfigFetchSessionTask
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.data.util.RefreshTagsSessionTask
import com.garfiec.librechat.core.data.util.RoleFetchSessionTask
import com.garfiec.librechat.core.data.util.SessionTask
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.data.util.SyncFavoritesSessionTask
import com.garfiec.librechat.core.network.client.AccountReadyGate
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
    single { get<LibreChatDatabase>().artifactShortcutDao() }

    // --- Account identity (row-tenancy) ---

    // The in-memory active-account holder every identity-dependent subsystem will read.
    single<ActiveAccountProvider> { InMemoryActiveAccountProvider() }
    // Persisted account roster (list + single active pointer). Pure storage.
    single { AccountRoster(dataStore = get(), json = get()) }
    // Eager: at cold start it must migrate + reconcile + seed the provider (driving the URL from the
    // active roster entry) even before any consumer asks for it. Bound as the AccountReadyGate the
    // HTTP clients + first-frame routing await.
    single(createdAtStart = true) {
        AccountRegistry(
            roster = get(),
            activeAccountProvider = get(),
            serverDataStore = get(),
            tokenManager = get(),
            appScope = get<CoroutineScope>(KoinQualifiers.ApplicationScope),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    } bind AccountReadyGate::class
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
            tokenManager = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }
    // Coordinates switch-without-re-login, the add-account flow, and account removal: URL + token key
    // + roster pointer + identity flip, atomic under the SwitchGate barrier. The auth repository
    // routes add-mode sign-ins through it; the switcher UI drives switch/add/remove.
    single {
        AccountSwitcher(
            roster = get(),
            serverDataStore = get(),
            tokenManager = get(),
            activeAccountProvider = get(),
            switchGate = get(),
            claimReconciler = get(),
            switchCacheCleaner = get(),
            accountDataPurger = get(),
            prefsPurger = get(),
            sessionCacheCleaner = get(),
        )
    }
    single { AccountScopedPrefsPurger(dataStore = get()) }
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
            activeAccountProvider = get(),
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
            accountSwitcher = get(),
            switchGate = get(),
        )
    }

    single<RoleRepository> {
        RoleRepositoryImpl(
            rolesApi = get(),
            userRepository = get(),
            cacheDataStore = get(),
            activeAccountProvider = get(),
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
    singleOf(::AccountLabelBackfillSessionTask) bind SessionTask::class
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
            json = get(),
        )
    }

    single<ConversationRepository> {
        ConversationRepositoryImpl(
            conversationsApi = get(),
            conversationDao = get(),
            messageDao = get(),
            activeAccountProvider = get(),
            roster = get(),
            json = get(),
            dispatcher = get(KoinQualifiers.Default),
        )
    }

    single<MessageRepository> {
        MessageRepositoryImpl(
            messagesApi = get(),
            messageDao = get(),
            activeAccountProvider = get(),
            roster = get(),
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

    single<ArtifactShortcutRepository> {
        ArtifactShortcutRepositoryImpl(
            dao = get(),
            ioDispatcher = get(KoinQualifiers.IO),
        )
    }

    // --- Repositories (simple auto-wiring) ---

    singleOf(::BalanceRepositoryImpl) bind BalanceRepository::class
    single {
        ConfigRepositoryImpl(
            configApi = get(),
            configCache = get(),
            dispatcher = get(KoinQualifiers.Default),
        )
    } bind ConfigRepository::class
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
    singleOf(::EndpointTokenRepositoryImpl) bind EndpointTokenRepository::class
    singleOf(::PresetRepositoryImpl) bind PresetRepository::class
    singleOf(::ProjectRepositoryImpl) bind ProjectRepository::class
    singleOf(::PromptRepositoryImpl) bind PromptRepository::class
    singleOf(::ShareRepositoryImpl) bind ShareRepository::class
    singleOf(::SkillsRepositoryImpl) bind SkillsRepository::class
    singleOf(::SpeechRepositoryImpl) bind SpeechRepository::class
    singleOf(::UserRepositoryImpl) bind UserRepository::class
    singleOf(::BannerRepositoryImpl) bind BannerRepository::class
    singleOf(::FavoritesRepositoryImpl) bind FavoritesRepository::class
}
