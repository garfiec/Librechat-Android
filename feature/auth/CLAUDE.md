# feature:auth

## Screens
- **ServerUrlScreen** -- first-run server URL entry with validation and QR scan option
- **LoginScreen** -- email/password + social OAuth buttons + LDAP username mode
- **RegisterScreen** -- name, username, email, password, confirm password
- **ForgotPasswordScreen** -- email entry for password reset link
- **TwoFactorScreen** -- 6-digit OTP input with backup code fallback

## Navigation
- Sealed interface: `AuthRoute : NavKey` with typed route classes
- Routes: `ServerUrl`, `Login`, `Register`, `ForgotPassword`, `TwoFactor(tempToken)`, `VerifyEmail(email)`, `ResetPassword(userId, token)`, `Terms` (all `@Serializable`)
- Feature entries registered via `EntryProviderScope<NavKey>.authEntries()`
- Flow: `ServerUrl` → `Login` → (2FA if `tempToken` returned) → `onAuthComplete`
- Register and ForgotPassword are lateral routes from Login
- `TwoFactor(val tempToken: String)` data class carries the nav argument directly

## OAuth Flow
- Social logins open an in-app WebView (`SsoLoginScreen` + `SsoWebView` expect/actual) on the `SsoLogin(provider)` route — NOT Custom Tabs
- WebView loads `{serverUrl}/oauth/{provider}` (router mounted at `/oauth`, index.js:302 — NOT `/api/oauth`) with a browser UA string (ua-parser middleware 403s WebView agents)
- On OAuth success the SERVER sets the httpOnly `refreshToken` cookie on its own origin; page events (doUpdateVisitedHistory/onPageFinished; WKNavigationDelegate on iOS) read the native cookie store scoped to the server's origin
- `SsoLoginViewModel` consumes the token once (guard flag), clears the cookie via `OAuthCookieStore.clearRefreshTokenCookie(serverUrl)`, then calls `authRepository.loginWithOAuthToken`
- Stale cookie is wiped on screen entry (fresh identity boundary)
- Provider failure redirects to `{DOMAIN_CLIENT}/login?...&error=...` — surfaced as error state
- Supported providers configured by server: Google, GitHub, Discord, Facebook, Apple, OpenID

## Token Storage
- Tokens stored in `EncryptedSharedPreferences` via `TokenDataStore` in `:core:data`
- Refresh token sent as Cookie header (backend reads `cookies.parse(req.headers.cookie)`)
- Access token sent as Bearer header
- Token refresh is an explicit POST, not automatic cookie-based

## ViewModels
- One ViewModel per screen: `ServerUrlViewModel`, `LoginViewModel`, `RegisterViewModel`, `ForgotPasswordViewModel`, `TwoFactorViewModel`, `SsoLoginViewModel`
- All use `AuthRepository` from `:core:data`

## Key Implementation Notes
- If server URL is already stored, skip ServerUrl screen on launch
- Social logins use the in-app `SsoLogin` WebView flow (server sets the httpOnly cookie on its own origin; Custom Tabs/`ASWebAuthenticationSession` cannot read that store)
- `openidAutoRedirect` from server config triggers automatic redirect instead of showing login form
- LDAP mode: show "Username" field instead of "Email" (check server config)

### Terms Screen
- `TermsScreen` + `TermsViewModel` — displays server terms, "I Accept" button
- Route: `Terms` data object (part of `AuthRoute` sealed interface) in `AuthNavigation.kt`
- Loads terms text via `UserRepository`, posts acceptance on confirm
- `TermsViewModel.consumeAccepted()` resets navigation trigger
- **Gotcha**: Terms check should happen after login if `startupConfig.requireTerms` is true
- **Note**: `VerifyEmailScreen` already existed pre-Round 2

### Localization
- `strings.xml` created for all 8 modules (app, core/ui, feature/auth, chat, conversations, settings, agents, files)
- Contains key toolbar titles, button labels, section headers — NOT exhaustive extraction
- Full string extraction is a future pass
