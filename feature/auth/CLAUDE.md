# feature:auth

## Screens
- **ServerUrlScreen** -- first-run server URL entry with validation and QR scan option
- **LoginScreen** -- email/password + social OAuth buttons + LDAP username mode
- **RegisterScreen** -- name, username, email, password, confirm password
- **ForgotPasswordScreen** -- email entry for password reset link
- **TwoFactorScreen** -- 6-digit OTP input with backup code fallback

## Navigation
- Graph route: `AUTH_GRAPH_ROUTE` ("auth_graph"), start destination: `SERVER_URL_ROUTE`
- Flow: ServerUrl -> Login -> (2FA if `tempToken` returned) -> `onAuthComplete`
- Register and ForgotPassword are lateral routes from Login
- `TWO_FACTOR_ROUTE` takes `{tempToken}` nav argument

## OAuth Flow
- `OAuthWebViewActivity` loads `{serverUrl}/oauth/{provider}` in an in-app `WebView` (same cookie jar as `CookieManager`)
- Chrome Custom Tabs were dropped: their cookies are not visible to the app’s `CookieManager`, so users stayed on the web UI
- After login, when the page is back on the LibreChat host, the activity reads the httpOnly `refreshToken` cookie and returns it via `ActivityResult` to `LoginScreen`
- Supported providers configured by server: Google, GitHub, Discord, Facebook, Apple, OpenID

## Token Storage
- Tokens stored in `EncryptedSharedPreferences` via `TokenDataStore` in `:core:data`
- Refresh token sent as Cookie header (backend reads `cookies.parse(req.headers.cookie)`)
- Access token sent as Bearer header
- Token refresh is an explicit POST, not automatic cookie-based

## ViewModels
- One ViewModel per screen: `ServerUrlViewModel`, `LoginViewModel`, `RegisterViewModel`, `ForgotPasswordViewModel`, `TwoFactorViewModel`
- All use `AuthRepository` from `:core:data`

## Key Implementation Notes
- If server URL is already stored, skip ServerUrl screen on launch
- Social logins must use Custom Tabs, not WebView (cookie sharing requirement)
- `openidAutoRedirect` from server config triggers automatic redirect instead of showing login form
- LDAP mode: show "Username" field instead of "Email" (check server config)

### Terms Screen
- `TermsScreen` + `TermsViewModel` — displays server terms, "I Accept" button
- Route: `TERMS_ROUTE = "auth/terms"` in `AuthNavigation.kt`
- Loads terms text via `UserRepository`, posts acceptance on confirm
- `TermsViewModel.consumeAccepted()` resets navigation trigger
- **Gotcha**: Terms check should happen after login if `startupConfig.requireTerms` is true
- **Note**: `VerifyEmailScreen` already existed pre-Round 2

### Localization
- `strings.xml` created for all 8 modules (app, core/ui, feature/auth, chat, conversations, settings, agents, files)
- Contains key toolbar titles, button labels, section headers — NOT exhaustive extraction
- Full string extraction is a future pass
