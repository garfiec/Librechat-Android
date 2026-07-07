# core:data

Room database, DataStore preferences, EncryptedSharedPreferences for tokens, and all repository implementations. This is the coordination layer between network and local storage.

## What This Module Provides

- **Room database** (`db/LibreChatDatabase.kt`): 6 entities, 6 DAOs, version 1, `exportSchema = true`.
- **Entities**: `ConversationEntity`, `MessageEntity`, `FileEntity`, `AgentEntity`, `PresetEntity`, `ConversationTagEntity`. Complex fields (lists, nested objects) stored as JSON strings via `Converters`.
- **DAOs**: `ConversationDao`, `MessageDao`, `FileDao`, `AgentDao`, `PresetDao`, `ConversationTagDao`. Read methods return `Flow<T>` for reactive observation.
- **DataStore**: `ServerDataStore` (server URL prefs), `SettingsDataStore` (user preferences).
- **Token storage** (`datastore/TokenDataStore.kt`): Implements `TokenManager` from `:core:network` using `EncryptedSharedPreferences` with AES-256. Uses `Mutex` to ensure only one refresh runs at a time when multiple 401s arrive concurrently.
- **Repositories** (`repository/`): Interface + Impl for each domain: `AuthRepository`, `ConversationRepository`, `MessageRepository`, `ChatRepository`, `FileRepository`, `AgentRepository`, `PresetRepository`, `PromptRepository`, `TagRepository`, `ShareRepository`, `ConfigRepository`, `UserRepository`, `SettingsRepository`.
- **Entity mappers** (`db/mapper/EntityMapper.kt`): Convert between Room entities and domain models.
- **Sync manager** (`sync/ConversationSyncManager.kt`): Orchestrates cache invalidation.

## Key Patterns

### Repository: Interface + Impl

```kotlin
interface ConversationRepository {
    fun observeConversations(...): Flow<Result<List<Conversation>>>
    suspend fun updateTitle(id: String, title: String): Result<Conversation>
}
```

All impls take constructor parameters (api, dao, mapper, dispatcher) wired via Koin named qualifiers.

### Read-Through Cache

1. Emit cached data from Room immediately (first page).
2. Fetch fresh data from network via API service.
3. Upsert into Room. The Room `Flow` auto-emits the updated list.
4. On network error, the cached data remains visible; error is surfaced separately.

### Account-keyed token store (`CommonTokenDataStore`)

Tokens are namespaced by account (`acct:<accountId>:access_token`) and several accounts' tokens are
retained at rest at once (multi-account, issue #179). Concurrency uses three cooperating pieces, not a
single refresh mutex:

- **`stateMutex`** — guards the in-memory identity + cached bearer and short storage reads/writes of
  it. Held only for brief critical sections, **never across the refresh network POST**, so a switch or
  logout never stalls behind a slow refresh.
- **per-account `flightMutex`/`flights`** — single-flights refreshes of the *same* account across their
  POST, so a second 401 reads the rotated token instead of re-POSTing a spent one.
- **`tokenEpoch`** — bumped by every op that invalidates token truth (logout, clear, removal, a new
  authentication, an identity re-home). A refresh captures it before its POST and discards its result
  if it changed, so a refresh racing a teardown can't resurrect a cleared session even with no lock
  held across the POST.

Interactive sign-in (`setTokens`) **stages** the pair under the bare keys and drops the active binding;
`onAccountResolved` re-homes it into the account's keyed slot. This makes a re-login while another
account is active unable to corrupt/leak into that account's slot.

Wrap `EncryptedSharedPreferences` access in try/catch -- some OEM devices have broken Keystore
implementations. `TokenDataStore` recovers in place rather than crashing: a single undecryptable entry
is dropped (other retained accounts stay logged in), a broken keyset is wiped and rebuilt with a fresh
master key, and if even a rebuild can't produce a working store it degrades to an in-memory session for
the process. Construction never throws into `startKoin`. The next request then 401s and routes to
re-login through the normal expired-session flow.

### DataModule Bindings

`DataModule.kt` defines a Koin `module { }` that binds repository interfaces to their implementations via `singleOf(::Impl) bind Interface::class` and provides the Room database, DAOs, and DataStore instances.

## Room TypeConverters

`Converters.kt` handles JSON serialization for complex Room fields: `List<String>`, `MessageContentPart` lists, `Feedback`, `FileReference` lists, etc. Uses the same `Json` instance from the Koin graph.

## Rules

- Dependencies: `:core:network`, `:core:model`, `:core:common`, Room, DataStore, security-crypto, Ktor, kotlinx-serialization, Timber, Koin.
- Convention plugins: `librechat.mobile.library` + `librechat.mobile.koin` + `librechat.mobile.room` + `librechat.kotlin.serialization`.
- Repositories must use `safeApiCall` from `:core:common` for all network calls.
- Entities are internal to this module -- feature modules work with domain models from `:core:model`.
- All DAO read methods that the UI observes should return `Flow`, not suspend functions.

### New Repositories (Round 2)
- `MemoryRepository` / `MemoryRepositoryImpl` — wraps `MemoriesApi` for memory CRUD + preferences
- `McpRepository` / `McpRepositoryImpl` — wraps `McpApi` for server CRUD, tools, connection status, reinitialize
- Both bound in `DataModule.kt` via `singleOf`
- Both use `safeApiCall` — no local caching (server is sole source of truth for memories and MCP)
- **Gotcha**: Memory delete/update use `key` as identifier (not a separate ID field)
- **Gotcha**: MCP server operations use `serverName` as identifier

### New Repositories (Round 3)
- `BannerRepository` / `BannerRepositoryImpl` — wraps `BannerApi` for fetching server banners
- `AgentRepository.getAgentsPaginated()` — server-side paginated agent fetch, maps response to `PaginatedAgents` domain model
- Both bound in `DataModule.kt` via `singleOf`, both use `safeApiCall`, no local caching
