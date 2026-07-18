# core:network

Ktor HttpClient, API service classes, SSE streaming client, auth interceptor. All HTTP communication lives here.

## What This Module Provides

- **Ktor HttpClient factory** (`di/NetworkModule.kt`): Provides singleton `HttpClient(OkHttp)` with ContentNegotiation, Logging, HttpTimeout, HttpRequestRetry, and AuthInterceptorPlugin via Koin module.
- **AuthInterceptorPlugin** (`client/AuthInterceptor.kt`): Custom Ktor plugin that injects `Authorization: Bearer` on outgoing requests and retries on 401 after refreshing tokens. Skips auth endpoints (`auth/login`, `auth/register`, `auth/refresh`, etc.).
- **TokenManager interface** (`client/TokenManager.kt`): `getAccessToken()`, `setTokens()`, `refreshAccessToken()` (single-flighted, epoch-guarded), `clearTokens()`, `sessionExpiredFlow`, plus the account-keyed `getAccessTokenFor()` / `selectAccount()` / `removeAccount()` / `refreshAccessTokenFor()`. Implemented in `:core:data`.
- **ServerUrlProvider interface** (`client/ServerUrlProvider.kt`): Resolves the user-configured base URL. Implemented in `:core:data`.
- **API services** (`api/`): One class per domain -- `AuthApi`, `ConversationsApi`, `MessagesApi`, `ChatStreamApi`, `FilesApi`, `AgentsApi`, `PresetsApi`, `PromptsApi`, `TagsApi`, `ShareApi`, `ConfigApi`, `EndpointsApi`, `BalanceApi`, `UserApi`. Each takes `HttpClient` as a constructor parameter, wired via Koin.
- **SSE client** (`sse/`): `SseClient`, `SseEvent`, `SseEventParser`, `SseConnectionManager`.
- **DTO mappers** (`mapper/`): Convert network DTOs to domain models from `:core:model`.

## SSE Streaming Architecture

**Two layers of buffering have to be worked around. Don't undo either of them.**

### Layer 1: Ktor `client.get()` buffers the body (both platforms)

Use `prepareGet { } + execute { response -> response.bodyAsChannel() }` to stream the response incrementally. `client.get()` materializes the full body in memory before returning, which defeats SSE. This was the original Android workaround introduced in commit `f182b2b`. The explanatory comment was deleted in commit `770603e` during the KMP iOS migration; it has since been re-added in `SseHttpTransport.android.kt`.

### Layer 2: NSURLSession buffers `text/*` responses (iOS only)

NSURLSession has an undocumented behavior where `URLSession:dataTask:didReceiveData:` is not called for `text/event-stream` responses until ~512 bytes are received OR the connection closes. `application/json` and `application/octet-stream` are exempt. LibreChat hardcodes `text/event-stream` on its SSE endpoint, so on iOS this would cause chat streaming to appear frozen until the user pressed stop. Tracked as **KTOR-6378** (status: Unresolved on Ktor's side; see also Apple Developer Forums thread 64875, open since 2016).

This **cannot** be fixed in Ktor or `commonMain`. Ktor's Darwin engine faithfully forwards every `didReceiveData` callback it gets, but NSURLSession isn't calling it. The Layer 1 `prepareGet + execute` workaround does not reach Layer 2 — it addresses Ktor-level body materialization, not OS-level callback withholding.

### Architecture: `expect class SseHttpTransport`

Both platforms wire the network graph the same way: `networkModule` (commonMain) is
platform-agnostic and both Android (`LibreChatApplication`) and iOS (`IosSharedModule`)
`includes(networkModule)`. The only platform-specific bindings — the engine factory
(OkHttp/Darwin) and `SseHttpTransport` — live in `networkPlatformModule` (expect/actual),
so iOS's engine + `SseHttpTransport` come from `networkPlatformModule.ios`.

Both layers are addressed via an `SseHttpTransport` abstraction:

- **`SseHttpTransport.android.kt`** — thin Ktor `prepareGet + execute` shim. Layer 1 fix only; Layer 2 doesn't apply on Android.
- **`SseHttpTransport.ios.kt`** — custom HTTP/1.1 client built on `Network.framework`'s `NWConnection`. Bypasses NSURLSession entirely for the SSE GET only, which sidesteps Layer 2 at the OS level. All other iOS HTTP traffic still uses the Darwin engine.
- **`HttpResponseParser.kt`** — `commonMain` HTTP/1.1 status-line + headers + chunked-body parser. Pure Kotlin, JVM-testable, 18 unit tests.
- **`SseClient`** — wraps the transport with retry, connectivity-flow handling, mapper state reset, and SKIE-safe error handling. Platform-agnostic.

### iOS cinterop bridge gotcha

The iOS transport requires a `.def` cinterop bridge at `core/network/src/iosMain/cinterop/nwparams_defaults.def`. Kotlin/Native 2.3.20's `platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION` binding wraps the block pointer in a Kotlin lambda (`knifunptr_getter`), which crashes on `block_destroy_helper` when Network.framework hands the cleanup path a Swift-generic `Network.ProtocolOptions<TLSProtocol>` that doesn't descend from NSObject. The `.def` bridge calls the macro at C compile time so the block pointer stays entirely inside Network.framework's memory management.

**Do NOT remove the `.def` bridge thinking the platform binding will work.** It won't. The 4-round Phase 2b debug cycle that introduced this fix is documented in commit `0ad21b6`.

### Two-phase protocol

Two-phase SSE protocol:
1. `POST /api/agents/chat` with the message payload → returns `{ streamId }` (where `streamId === conversationId`)
2. `GET /api/agents/chat/stream/:streamId` opens the SSE event stream

Legacy single-phase path (OpenAI Assistants): POST body is the SSE stream itself. Both paths use the custom transport via `SseClient`.

`SseConnectionManager` handles lifecycle: start, reconnect with exponential backoff (1s/2s/4s/8s, max 5 retries), abort via `POST /api/agents/chat/abort`, exposes `StateFlow<StreamingState>`. On reconnection, append `?resume=true` to get a `sync` event with `runSteps[]` + `aggregatedContent[]`.

### Don't use Ktor's SSE plugin

The Ktor SSE plugin uses the same `NSURLSessionDataTask` code path as the regular Darwin engine, so it would have the same Layer 2 bug on iOS. The custom transport is mandatory.

## Key Configuration

- `Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; explicitNulls = false; coerceInputValues = true }`
- `socketTimeoutMillis = 120_000` (SSE streams override to `Long.MAX_VALUE`)
- Browser-like `User-Agent` header required -- backend `ua-parser-js` middleware may reject non-browser UAs.

## Error Handling

- `HttpResponseValidator` in the client converts non-2xx to exceptions.
- API services throw on error. Repositories in `:core:data` catch via `safeApiCall` from `:core:common`.
- Respect `429 Too Many Requests` and parse `Retry-After` header on auth endpoints.

## Rules

- Dependencies: `:core:model`, `:core:common`, Ktor bundles, kotlinx-serialization, Timber, Koin.
- Convention plugins: `librechat.mobile.library` + `librechat.mobile.koin` + `librechat.kotlin.serialization`.
- API services must not contain business logic -- they are thin HTTP wrappers.
- All `arg`-wrapped endpoints must match the backend pattern: `setBody(mapOf("arg" to mapOf(...)))`.
