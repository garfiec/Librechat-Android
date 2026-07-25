# feature:settings

## Screen
`SettingsScreen` -- sealed interface `SettingsRoute : NavKey` with primary route `SettingsTabbed` (`@Serializable` data object). Receives `onLogout` and `onNavigateBack` callbacks.

## Sections
- **Account**: displays user profile from `UserApi.getUser()`
- **Appearance**: theme toggle (System / Light / Dark) via `ThemeDataStore`
- **Server**: shows current server URL from `ServerDataStore`
- **About**: app version info
- **Danger Zone**: delete account with confirmation dialog

## Theme
- `ThemeMode` enum: `SYSTEM`, `LIGHT`, `DARK`
- Persisted in `ThemeDataStore` (DataStore<Preferences>)
- `setThemeMode()` writes to DataStore; the app's root composable observes `themeDataStore.themeMode` flow
- Applied via Compose `MaterialTheme` with `isSystemInDarkTheme()` for SYSTEM mode

## Logout
- `SettingsViewModel.logout()` calls `AuthRepository.logout()` which clears tokens from `EncryptedSharedPreferences`
- Sets `isLoggedOut = true` in UI state; screen calls `onLogout` callback to navigate to auth graph

## Delete Account
- `SettingsViewModel.deleteAccount()` calls `UserApi.deleteUser()` then `AuthRepository.logout()`
- Sets `isAccountDeleted = true` in UI state; triggers navigation to auth graph

## ViewModel Dependencies
- `UserApi` -- fetch/delete user profile
- `AuthRepository` -- logout (clear tokens)
- `ThemeDataStore` -- observe and set theme preference
- `ServerDataStore` -- observe current server URL

## Future Sections (from spec, not yet implemented)
- Chat tab (font size, message layout, auto-scroll toggle)
- Speech tab (STT/TTS engine config)
- Data tab (import/export, clear chats, shared links management)
- Balance tab (conditional, token credits display)
- 2FA setup/disable in Account section

### New Settings Sections
- `SettingsScreen` now organized into: Account, Appearance, General (Language, Personalization), Chat (Presets), Advanced (Fork Behavior, Commands), Server, About, Danger Zone
- Each new setting opens a dialog or navigates to a dedicated screen

### Settings Sub-screens (via SettingsNavigation)
- `MemoriesScreen` — full memory CRUD, enable/disable toggle, own ViewModel (`MemoriesViewModel`)
- `McpServersScreen` — server list with status badges, CRUD, reinitialize, tools sheet (`McpViewModel`)
- `PresetManagerScreen` — list/delete presets (`PresetManagerViewModel`)
- `CommandsConfigScreen` — enable/disable slash commands
- Routes: `Memories`, `McpServers`, `PresetManager` (all `@Serializable` data objects extending `SettingsRoute`)
- **Gotcha**: Memories and MCP navigation routes defined in their own files (`MemoriesNavigation.kt`, `McpNavigation.kt`) but wired through `SettingsNavigation.kt`
- **Gotcha**: `McpServerDialog` uses `McpServerType` enum (SSE, STREAMABLE_HTTP) — must match backend expectations
- **Gotcha**: Memory keys are immutable after creation (edit dialog disables key field)

### API Keys
- `ApiKeysScreen` — list/create/delete API keys, own ViewModel (`ApiKeysViewModel`)
- Route: `ApiKeys` (`@Serializable` data object extending `SettingsRoute`), accessible from Settings → Security section
- `ApiKeyCreateDialog` is two-phase: name input → show created key value with copy button
- **Gotcha**: The key value is only available in the creation response — it cannot be retrieved again from the server. The dialog must show it immediately after creation.

### Server connection (gateway headers, issue #287)
- `ServerHeadersDialog` + `ServerHeadersViewModel` — edits the **active** server's static request
  headers (Cloudflare Access service tokens and equivalents). Reached from **Settings → Account →
  Server connection**, grouped with the other credentials rather than with data/diagnostics.
- The dialog is **stateless** (the screen owns the ViewModel and passes state + callbacks down) —
  forwarding the ViewModel into it trips detekt-compose's `ViewModelForwarding`.
- Two things the dialog does NOT own: it closes on `saved` turning true, driven from the screen, so a
  save the store could not persist keeps it open with the typed rows intact; and dismissing with
  `isDirty` prompts before discarding, then calls `discardEdits()` — the ViewModel outlives the
  dialog, so an abandoned edit would otherwise still be there on the next open.
- **Gotcha**: `AlertDialog` clips its content rather than scrolling it, so the dialog body wraps the
  editor in its own `verticalScroll` — otherwise a few rows plus the keyboard put the fields out of
  reach.
- The same headers are editable pre-login on `ServerUrlScreen`; both render `CustomHeadersEditor` from
  `:core:ui` so the two can't drift.
- **Why it exists post-login at all**: a gateway credential can be rotated or revoked mid-session, and
  the pre-login editor is only reachable by signing out — not a step anyone would guess from the
  resulting "could not reach the server."
- **A null read is not an empty header set.** `headersForServer` returning null sets `loadFailed`: the
  editor shows a warning instead of an empty list, and a server change clears the typed rows even
  though the new server's read failed, because those rows belong to the server that just went away.
  Refusing the *empty save* that would delete an unloaded credential is deliberately **not** here —
  `ServerRepository.setHeaders` owns it, so the pre-login editor is covered by the same rule instead
  of a second copy of it. A non-empty save is the recovery path and goes through.
- **The dialog re-reads when it opens** (`reload()`, called from the row's `onClick`). The URL is
  observed, so for one server the load happens exactly once — a read that failed would otherwise keep
  warning about a store that has since recovered, for the life of the process. Unsaved edits win over
  the re-read, and that is checked on **both** sides of the read: it genuinely suspends while the
  store is recovering, and the dialog is already on screen by then, so the user is typing into it.
- `showServerHeadersDialog` is `rememberSaveable`, unlike the screen's other dialog flags. This
  ViewModel outlives the composition and holds half-typed rows, so a rotation that closed the dialog
  without running the discard prompt would strand them behind a `reload()` that (correctly) refuses to
  overwrite unsaved edits.
- **Save failures render inside the dialog**, not as a snackbar: a refused save leaves the dialog open
  and otherwise unchanged, and the Scaffold's snackbar draws behind its scrim — so the message would
  be invisible and the Save button would look dead. `saveFailure` is therefore state that clears on
  the next edit, not a one-shot event.
- Save is explicit and does **not** re-probe: `ServerRepository` patches its in-memory map under the
  same lock that performs the write (it is the table's only writer, and deliberately does *not*
  observe it), so the next ordinary request picks the values up. In-flight requests keep the headers
  they were snapshotted with — immutable by design.

### Dialogs
- `LanguageSelectorDialog` — 37+ locales with search, single-select radio
- `ForkSettingsDialog` — 3 fork modes (`DIRECT_PATH`, `INCLUDE_BRANCHES`, `TARGET_LEVEL`); labels/descriptions come from `fork_mode_*` string resources via `forkModeLabel()` / `forkModeDescription()`, not from the enum
- `PersonalizationDialog` — "About you" + "Response style" text areas with enable toggle

### MCP OAuth consent (v0.8.8)
- `reinitialize` can answer `oauthRequired` + `oauthUrl`, meaning the server is waiting on the user
  rather than connected. `McpViewModel` holds that as `pendingOAuth` and `McpServersScreen` renders
  a consent dialog.
- **Gotcha**: do NOT auto-launch the browser. The redirect hands a third-party provider this
  session, so the launch has to be an explicit user action, not a side effect of tapping reconnect.
- `connectionDeferred` is a third outcome: the ack landed but the connection is still being made.
  Reported via the `McpViewModel.DEFERRED_MARKER` sentinel, which the screen swaps for a localized
  string (the VM has no access to compose resources).
