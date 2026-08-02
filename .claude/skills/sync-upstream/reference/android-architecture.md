# Switchboard Architecture Reference

Condensed architecture guide for Switchboard, a third-party native mobile client for LibreChat. Read module-specific `CLAUDE.md` files for full details.

## Module Dependency Graph

```
app
├── feature/auth          → core/common, core/model, core/network, core/data, core/ui
├── feature/chat          → core/common, core/model, core/network, core/data, core/ui
├── feature/conversations → core/common, core/model, core/network, core/data, core/ui
├── feature/settings      → core/common, core/model, core/data, core/ui
├── feature/agents        → core/common, core/model, core/data, core/ui
├── feature/files         → core/common, core/model, core/data, core/ui
└── core/data             → core/common, core/model, core/network
    core/network          → core/common, core/model
    core/ui               → core/common, core/model
    core/model            → (none — pure Kotlin, no Android deps)
    core/common           → (none — lowest layer)
```

**Rules:**
- Feature modules depend on `:core:*` only, never on each other.
- Feature modules currently live under `feature/{auth, chat, conversations, settings, agents, files}`. There is no `feature/prompts` yet — prompts UI is not implemented.

## Data Flow Pattern (Unidirectional)

```
UI (Composable) → ViewModel (StateFlow) → Repository (interface) → API (Ktor) / Room (cache)
```

- ViewModels expose `StateFlow<UiState>` to Compose
- Repositories define interfaces in `:core:data`, implemented with Koin DI
- Room is a read-through cache; server is always source of truth

## safeApiCall Pattern

All repository network calls use this wrapper (defined in `:core:common`):

```kotlin
suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (e: ClientRequestException) {      // 4xx
        Result.Error(e, parseErrorMessage(e.response.bodyAsText()))
    } catch (e: ServerResponseException) {      // 5xx
        Result.Error(e, "Server error. Please try again.")
    } catch (e: HttpRequestTimeoutException) {
        Result.Error(e, "Request timed out.")
    } catch (e: IOException) {
        Result.Error(e, "Network unavailable. Check your connection.")
    } catch (e: SerializationException) {
        Result.Error(e, "Unexpected response format.")
    }
```

## API Service Pattern (core/network)

Each API service is a Ktor-backed class with a primary constructor that takes an `HttpClient`.
It is registered in a Koin module — no annotations.

```kotlin
class ConversationsApi(private val client: HttpClient) {
    suspend fun getConversations(pageNumber: Int, limit: Int, isArchived: Boolean): PaginatedConversations =
        client.get("/api/convos") {
            parameter("pageNumber", pageNumber.toString())
            parameter("limit", limit.toString())
            parameter("isArchived", isArchived.toString())
        }.body()
}
```

DI registration (in `core/network/src/commonMain/.../di/NetworkModule.kt`):
```kotlin
val networkModule = module {
    singleOf(::ConversationsApi)
    // ...
}
```

**Backend quirk:** Mutation endpoints wrap body in `arg` field: `{ "arg": { ... } }`

## Repository Pattern (core/data)

Interface in `:core:data`, implementation in the same module, registered with Koin:

```kotlin
interface ConversationRepository {
    fun observeConversations(...): Flow<Result<List<Conversation>>>
    suspend fun updateTitle(id: String, title: String): Result<Conversation>
}

class ConversationRepositoryImpl(
    private val api: ConversationsApi,
    private val dao: ConversationDao,
    private val mapper: EntityMapper,
    private val ioDispatcher: CoroutineDispatcher,
) : ConversationRepository { ... }
```

DI registration:
```kotlin
val dataModule = module {
    single<ConversationRepository> {
        ConversationRepositoryImpl(
            api = get(),
            dao = get(),
            mapper = get(),
            ioDispatcher = get(qualifier = named("io")),
        )
    }
}
```

Dispatchers come from `:core:common` via Koin named qualifiers (`named("io")`, `named("default")`, `named("main")`).

## Room Entity Pattern (core/data)

```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val title: String?,
    val endpoint: String?,
    val tagsJson: String?,  // Complex fields stored as JSON strings via TypeConverters
)
```

DAO read methods return `Flow<T>` for reactive observation.

## ViewModel Pattern (feature modules)

ViewModels extend `ViewModel` (or `androidx.lifecycle.ViewModel` for KMP) and are registered
with `viewModelOf(::X)` in the feature's Koin module:

```kotlin
class ConversationsViewModel(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    fun loadConversations() {
        viewModelScope.launch {
            conversationRepository.observeConversations(...)
                .collect { result ->
                    _uiState.update { state ->
                        when (result) {
                            is Result.Success -> state.copy(conversations = result.data)
                            is Result.Error -> state.copy(error = result.message)
                            is Result.Loading -> state.copy(isLoading = true)
                        }
                    }
                }
        }
    }
}
```

DI:
```kotlin
val conversationsModule = module {
    viewModelOf(::ConversationsViewModel)
}
```

Registered in `LibreChatApplication.kt`:
```kotlin
startKoin {
    modules(
        commonModule, networkModule, dataModule, uiModule,
        authModule, chatModule, conversationsModule, settingsModule,
        agentsModule, filesModule,
    )
}
```

In the screen composable:
```kotlin
@Composable
fun ConversationsScreen(viewModel: ConversationsViewModel = koinViewModel()) { ... }
```

**Do NOT use Hilt (`@HiltViewModel`, `@Inject`, `hiltViewModel()`) anywhere — the project is Koin-only and KMP-ready.**

## SSE Streaming (core/network)

Custom line parser over raw `ByteReadChannel` (do NOT use Ktor SSE plugin).

Two-phase protocol:
1. `POST /api/agents/chat` → returns `{ streamId }` (streamId === conversationId)
2. `GET /api/agents/chat/stream/:streamId` opens SSE stream

`SseConnectionManager` handles lifecycle: start, reconnect (exponential backoff), abort, `StateFlow<StreamingState>`.

**iOS note:** iOS uses a custom `NWConnection`-based HTTP/1.1 transport
(`core/network/src/iosMain/.../sse/SseHttpTransport.ios.kt`) to bypass NSURLSession's
undocumented `text/*` content-type buffering. When editing SSE logic, always check iosMain
sources — the iOS path is NOT just commonMain + a thin shim.

## KMP Source Set Layout

Every KMP module has (minimally):
- `src/commonMain/kotlin/` — shared logic
- `src/androidMain/kotlin/` — Android-specific actuals / platform bridges
- `src/iosMain/kotlin/` — iOS-specific actuals / platform bridges (Network.framework, Keychain, etc.)

When an `expect` class or function is declared in commonMain, matching `actual` declarations
must exist in BOTH `androidMain` and `iosMain`. Forgetting iosMain compiles locally
(because `./gradlew assembleDebug` only runs the Android targets) but breaks
`./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`.

## Naming Conventions

- API classes: `{Domain}Api.kt` (e.g., `ConversationsApi.kt`)
- Repository: `{Domain}Repository.kt` (interface) + `{Domain}RepositoryImpl.kt`
- ViewModel: `{Feature}ViewModel.kt`
- Screen composable: `{Feature}Screen.kt`
- UI state: `{Feature}UiState` data class
- Room entity: `{Domain}Entity.kt`
- Room DAO: `{Domain}Dao.kt`
- Koin module: `{Feature}Module.kt` (feature) / `{Layer}Module.kt` (core)
- Convention plugins: `librechat.kmp.{type}` (KMP) or `librechat.mobile.{type}` (Android-only)

## Build System

- Gradle 9.4.1, AGP 9.1.0, Kotlin 2.3.20, compileSdk 36, minSdk 26
- Compose Multiplatform 1.11.0-beta01, Compose BOM 2025.05.00
- 13 convention plugins under `build-logic/convention/src/main/kotlin/`
- Version catalog: `gradle/libs.versions.toml`
- Navigation 3 (Nav3): `NavDisplay` + `NavBackStack<NavKey>` + `entryProvider`
- DI: Koin (KMP-ready — no Hilt anywhere)
- Convention plugin `apply()` uses `override fun apply(target: Project) { with(target) { ... } }`
