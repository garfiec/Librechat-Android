# App Module

Single Activity architecture. `MainActivity` is the sole entry point.

## Navigation

`LibreChatNavHost` (in this module) is the Android-specific entry point. It wraps the shared module's `LibreChatNavHost` via a `content` lambda, adding:

- **Deep link handling** (`librechat://conversation/{conversationId}`)
- **Share intent routing** (navigates to NewChat if not already on a chat screen)
- **Tablet layout branching** based on `WindowSizeClass`

The shared module owns the core navigation: `Navigator`, `NavHostViewModel`, `MainNavDisplay`, `PhoneLayout`, sidebar/drawer composables, and all feature entry providers. See `shared/CLAUDE.md` for details.

### Layout Modes

- **Phone**: Delegates to shared `PhoneLayout` — `ModalNavigationDrawer` sidebar-first pattern.
- **Tablet** (600dp+ width): Uses `TabletLayout` (Android-only, in this module) — persistent side panel with swipe gesture and `BackHandler`.

Feature modules provide entries via `EntryProviderScope<NavKey>` extensions (e.g., `authEntries()`, `chatEntries()`). Navigation is driven by `onNavigate: (NavKey) -> Unit` and `onBack: () -> Unit` lambdas — feature modules never receive `NavBackStack` directly.

## Android-Only Files in This Module

- `LibreChatNavHost.kt` — Thin wrapper adding deep links, share intents, and tablet/phone branching
- `TabletLayout.kt` — Persistent sidebar with `BackHandler`, `android.net.Uri`, and custom swipe gesture

## Top-Level Destinations

Top-level routes: `NewChat` (Chat), `Conversations`, `AgentMarketplace` (Agents), `Files`, `SettingsTabbed` (Settings).
Conversations are integrated into the drawer body. Agents, Files, and Settings are accessible via drawer footer links. All routes are registered in the shared `MainNavDisplay` entry provider.

## Auth & Session

- `NavHostViewModel` (in shared) observes auth state via `isLoggedIn` flow
- Start destination is `NewChat` if logged in, `ServerUrl` otherwise
- `sessionExpired` flow triggers nav to auth flow with full back stack clear
- Drawer gestures are hidden during auth flow

## Deep Linking

- Scheme `librechat://`. Hosts: `conversation/{id}`, `artifact/{uuid}`, `oauth` (cookie-consumed; see below).
- **Single source of truth**: `DeepLinks.resolve()` in `:shared/commonMain` maps a URI to a
  `DeepLinkResolution` (`Route(target, requiresAuth)` / `Consumed` / `None`). Add a new deep link by
  adding one branch there — do NOT reintroduce a per-host allowlist here.
- `MainActivity.handleDeepLink()` calls the resolver to accept/drop the intent (via the Android
  `Uri → DeepLinkUri` adapter in `DeepLinkUriAdapter.kt`), then forwards to this module's
  `LibreChatNavHost`, which resolves again to place the target on the back stack.
- `requiresAuth` links (conversation) redirect to login when logged out; non-auth links (device-scoped
  artifact) open logged-out. `oauth` is `Consumed` — its token returns via cookie read by the login
  screen (`checkOAuthResult`), so the link only brings the app forward.
- `onNewIntent` handles deep links when the app is already running.

## Connectivity

- `ConnectivityObserver` injected into `MainActivity`
- Offline banner animates in/out at top of screen when connection is lost

## Dependencies

This module depends on `:shared`, all `:core:*`, and all `:feature:*` modules.
It applies convention plugins: `librechat.mobile.application`, `librechat.mobile.compose`, `librechat.mobile.koin`.

### Server Banners
- `NavHostViewModel` (shared) fetches banners from `BannerRepository` on init, filters expired via `displayFrom`/`displayTo` with `Instant.parse()`
- Dismissed banner IDs tracked in-memory via `dismissedBannerIds: StateFlow<Set<String>>` (session-scoped, not persisted)
- `BannerDisplay` composable shown at top of content in both `PhoneLayout` (shared) and `TabletLayout` (app)
- Banner types: "info" (blue), "warning" (amber), "error" (red) — defaults to "info" if type is null
- **Gotcha**: `displayFrom`/`displayTo` are ISO 8601 strings parsed with `Instant.parse()` — wrap in `runCatching` since the format from the server is not guaranteed

### Preset Navigation
- `PresetManager` route registered in `SettingsNavigation.kt`
- `PresetManagerScreen` accessible from Settings → Chat section
- Navigation wired through shared `MainNavDisplay` entry provider, accessible from both phone and tablet layouts
