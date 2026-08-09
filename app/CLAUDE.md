# App Module

Single Activity architecture. `MainActivity` is the sole entry point.

## Navigation

`LibreChatNavHost` (in this module) is the Android-specific entry point. It wraps the shared module's `LibreChatNavHost` via a `content` lambda, adding:

- **Deep link handling** (`librechat://conversation/{conversationId}`)
- **Share intent routing** (addresses the share to the chat on screen; navigates to NewChat first if
  the user is not on one — see Share Intents below)
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

## Share Intents

- `MainActivity.handleShareIntent()` only **stages** the payload on `ShareIntentConsumer`; it does
  not deliver it. This module's `LibreChatNavHost` then reads `navigator.currentRoute` and calls
  `dispatchTo(conversationId)` — `null` for the `NewChat` landing — navigating there first if the
  user is not on a chat at all.
- **A share must be addressed, never broadcast.** Several `ChatViewModel`s are alive at once (the
  landing sits in the back stack beneath an open `Chat`), so an unaddressed share is claimed by
  whichever one collects first — usually the invisible landing, which put the user's text in a
  composer they weren't looking at. `ShareIntentConsumer.sharesFor(conversationId)` gives each
  screen its own channel; the nav host is the only place that knows both that a share is waiting
  and which chat is on screen, so addressing lives there.
- Delivery is driven off `ShareIntentConsumer.undelivered`, not an activity-held counter: the launch
  intent is processed only on a fresh start, so a recreation during the theme/locale warm-up gate
  would otherwise strand a share nothing had addressed yet.

## Connectivity

- `ConnectivityObserver` injected into `MainActivity`
- Offline banner animates in/out at top of screen when connection is lost

## Dependencies

This module depends on `:shared`, all `:core:*`, and all `:feature:*` modules.
It applies convention plugins: `librechat.mobile.application`, `librechat.mobile.compose`, `librechat.mobile.koin`.

### Server Banners
- `GET /api/banner` returns **one banner object, or a 200 with an empty body and no `Content-Type`** — never an array. `BannerApi` reads the raw body for that reason; anything else still throws, so a proxy answering for the API can't read as "no banner configured"
- `NavHostViewModel` (shared) fetches the banner via `BannerRepository` on init, on account switch and on auth-complete. There is no timer
- The server applies the `displayFrom`/`displayTo` window and filters `type: 'banner'` itself, so the client does neither — a client-side re-check can only ever subtract from the server's decision, and did so on device-clock skew
- Dismissal is resolved in `BannerStateHolder`, not the UI: it nulls the banner and records a **(serverId, bannerId)** key, in-memory for the process. Both halves of that key are load-bearing and pull opposite ways — keyed by banner alone, a fleet seeding one bannerId across its servers delivers the next server's banner pre-dismissed; cleared on switch, dismissing A's banner and switching away and back brings A's right back
- `BannerStateHolder.clearForAccountChange()` drops the banner (scoped to a deployment) on switch and sign-out. It deliberately does **not** touch dismissals
- `BannerDisplay` composable shown at top of content in both `PhoneLayout` (shared) and `TabletLayout` (app). It takes a nullable banner and decides visibility itself — hoisting the null check to the callers would add/remove it from the tree and kill its enter/exit animation
- One visual treatment, from `secondaryContainer`/`onSecondaryContainer`. Do not re-introduce a `type`-keyed palette or hardcoded hex: the server only ever sends `type: "banner"`, and fixed colours ignore the accent seed and break in dark mode
- A `persistable` banner hides the dismiss control (matching web); a banner with no `bannerId` is not rendered at all, since nothing could ever remove it

### Preset Navigation
- `PresetManager` route registered in `SettingsNavigation.kt`
- `PresetManagerScreen` accessible from Settings → Chat section
- Navigation wired through shared `MainNavDisplay` entry provider, accessible from both phone and tablet layouts
