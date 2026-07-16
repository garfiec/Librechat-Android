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

### Dialogs
- `LanguageSelectorDialog` — 37+ locales with search, single-select radio
- `ForkSettingsDialog` — 3 fork modes (`DIRECT_PATH`, `INCLUDE_BRANCHES`, `TARGET_LEVEL`); labels/descriptions come from `fork_mode_*` string resources via `forkModeLabel()` / `forkModeDescription()`, not from the enum
- `PersonalizationDialog` — "About you" + "Response style" text areas with enable toggle
