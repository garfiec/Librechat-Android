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

## Custom per-server headers (issue #287)

`ServerHeadersPlugin` attaches the user's static gateway headers (Cloudflare Access service tokens and
equivalents) and is installed on **all three** clients — main, streaming, and refresh. The store lives
in `:core:data` (`ServerRepository`, the `servers` table keyed by `serverId`, plaintext); iOS SSE
gets them off the `RequestIdentity` snapshot and renders them via `customHeaderLines`.

Two rules that are load-bearing and easy to undo by accident:

- **The headers must NOT reuse `AuthInterceptorPlugin`'s `skipPaths`.** Those exist to keep the bearer
  off `auth/login` and `auth/refresh`; gating the gateway credential the same way leaves the app unable
  to sign in at all.
- **The cross-authority strip must stay in the `HttpSend` interceptor.** Ktor's `HttpRedirect` copies
  every header to a redirect target and strips only `Authorization`, and it re-executes below the
  request pipeline — so a `State`-phase host check cannot see the new host. `FilesApi.downloadFromUrl`
  fetches server-supplied absolute URLs, so this is reachable. `KtorRedirectContractTest` pins the Ktor
  behaviour; without it the guard's own test would be unfalsifiable.

Host-scoping for these is stricter than for the bearer (`isSameServerAuthority` vs
`isSameHostAsServer`, both in `HostScoping.kt`): scheme + host + port, and fail-closed. A gateway token
is long-lived and never rotates, so an `http://` downgrade or an unknown base URL must not carry it.

### Detecting the gateway (as opposed to sending headers to it)

`GatewayDetectionPlugin` turns a rejection into a typed `AccessGatewayException`, and is installed on
**all three** Ktor clients. `AccessGatewaySignal` holds the one predicate every transport shares.

- **It must stay an `HttpSend` interceptor.** Ktor installs `HttpResponseValidator` before
  `HttpRedirect` and reverses the `HttpSend` chain, so the validator is outermost and sees only the
  final 200. The 302 carrying `WWW-Authenticate: Cloudflare-Access` is visible *inside* the redirect
  loop and nowhere else. Moved to the validator, the branch is dead code no test would catch.
- **Never read the body.** The interceptor runs for every response including streaming ones.
- **Read every `WWW-Authenticate` line, not one of them.** It is a list, and a gateway in front of a
  server that already challenges with `Bearer` puts its own challenge on a second line. The two
  transports read the header from opposite ends by default — Ktor's `headers[name]` returns the
  *first* line, a naive raw parser keeps the *last* — so either one alone detects a two-line
  challenge only when it happens to land on the line that transport kept. Ktor scans `getAll(...)`;
  `HttpResponseParser` folds repeats into one comma-separated value per RFC 7230 §3.2.2. A shared
  predicate does not make the transports agree if they disagree about what to feed it.
- **Install it on every new client.** The absence is silent and platform-specific:
  `GatewayDetectionInstallTest` asserts the presence on all three from the real Koin graph, because a
  per-client test installs the plugin itself and so can never observe it missing from the module.
- **Cloudflare Access only, deliberately.** Cookie-based gateways (Authelia, oauth2-proxy) send no
  comparable header and degrade to the generic message. The rejected alternative — inferring a gateway
  from `text/html` served off the server's authority — has to stay correct against every legitimate
  off-authority redirect the app makes (presigned downloads, object storage). Reconsider on a real
  report, not pre-emptively.
- **Terminal, never retried — and that takes three separate opt-outs, not one.** A rejection is
  deterministic until the user edits the credential, so a backoff ladder only delays the report and
  then names the wrong cause. `SseClient` and the refresh loop each exit on it, *and*
  `configureRetryPolicy` excludes it from `retryOnExceptionIf`: `HttpRequestRetry` is installed
  before `GatewayDetectionPlugin` and Ktor runs the first-installed `HttpSend` interceptor outermost,
  so it sees the thrown exception and would re-send every retry-safe GET. Excluded in the predicate
  rather than by install order, so the plugin stays outermost for the transient failures it exists
  to absorb.
- **Match the gateway error on the cause chain, not the exception type.** `SseClient` learns of it by
  the byte channel being cancelled with it, and Ktor re-throws that wrapped in a
  `ClosedByteChannelException` — which form arrives depends on whether the parse side or the pump job
  loses the race to fail the scope. A type-exact `catch` therefore works most of the time, which is
  the worst failure rate to debug: use `accessGatewayCause()`.
- **A gateway block is not an expired session.** The refresh path maps it to
  `RefreshAttempt.GatewayBlocked` → `Transient`, and deliberately not through `settle()`: the request
  never reached LibreChat, so it is no evidence the session is dead, and logging out over it costs the
  user a re-login on top of the header fix. Nothing is surfaced from there — every other request rides
  the main client, which raises the same typed error on the screen the user is looking at.

The iOS SSE transport is not a Ktor client and carries its own check against `AccessGatewaySignal`,
placed **before** its status check: a rejection is a non-2xx, so left to the status branch it becomes a
bare `SseHttpStatusException(302)` that `SseClient` retries five times before blaming the network.

The editor UI is shared: `CustomHeadersEditor` in `:core:ui` backs both the pre-login server screen
(`feature/auth`) and the post-login Settings → Account → Server connection dialog (`feature/settings`),
and its strings live in `core/ui`'s `composeResources`. `:core:ui` deliberately does **not** depend on
`:core:network`, so each caller maps `HeaderRejection` to the editor's own `CustomHeaderRowError` —
three lines, versus a UI-to-network module dependency taken on just to name three cases.

Values are **masked** (`PasswordVisualTransformation`) with a per-row reveal toggle, matching how MCP
server headers and provider API keys are already treated: these end up in screenshots and
accessibility dumps otherwise. The reveal set is keyed by row index, so adding or removing a row
re-masks everything rather than letting a stale index alias onto a different row's credential.

The editor **branches on the width it is given** (`BoxWithConstraints`, stacking below 420dp), not on
the device or a caller flag — the same phone needs stacking inside a dialog but has room on the
full-screen pre-login form, and a tablet's dialog is narrow despite the tablet. Side by side, each
field gets under half the width, which rendered both `CF-Access-Client-Id` and
`CF-Access-Client-Secret` as `CF-Access-C…`: two different headers looking identical, next to a
masked value.

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
