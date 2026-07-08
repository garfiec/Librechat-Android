# Shared Module

KMP umbrella module that exports all core and feature modules as a single `Shared.framework` for iOS. On Android, this module is a dependency of `:app` but the framework export is iOS-only.

## Framework Export

`shared/build.gradle.kts` configures iOS targets (`iosArm64`, `iosSimulatorArm64`) and exports:
- `core:common`, `core:model`, `core:network`, `core:data` — exported via `api()` so iOS sees all public types
- `core:ui` + all `feature:*` modules — included via `implementation()` for Compose Multiplatform screen sharing

The framework is static (`isStatic = true`) and named `Shared`.

## iOS Platform Files (`src/iosMain/`)

| File | Purpose |
|------|---------|
| `IosKoinHelper.kt` | `startIosKoin()` — called from Swift `iOSApp.init()`. Sets up Kermit logging via NSLog, installs crash reporting, starts Koin with iOS modules. |
| `IosSharedModule.kt` | iOS Koin graph: `includes(sharedKoinModules)` (which brings in `networkModule` → Darwin engine + iOS `SseHttpTransport` via `networkPlatformModule.ios`, HTTP clients, all API services, SSE client) and adds only the one iOS-only binding, `LibreChatSDK`. |
| `IosKoinAccessor.kt` | Swift-accessible Koin resolver. `KoinHelper.swift` calls these to get SDK, repos, etc. |
| `IosCrashReporting.kt` | Unhandled exception hook — logs via Kermit + raises NSException for readable iOS crash logs. |
| `MainViewController.kt` | `MainViewController()` — the Compose Multiplatform entry point wrapped by `ComposeView.swift`. |

## Common Files (`src/commonMain/`)

- `LibreChatSDK.kt` — Facade class aggregating all API services, token manager, and SSE client
- `di/SharedKoinModules.kt` — `sharedKoinModules`, the single Koin module list both platforms start from (Android loads it directly; iOS `includes` it). Add a feature module here, not in the per-platform entry points. Verified against Android actuals by `:app` `KoinGraphVerificationTest` and against iOS actuals by `iosTest/IosKoinGraphTest` (`:shared:iosSimulatorArm64Test`).
- `navigation/` — Nav 3 route definitions and entry providers shared across platforms
- `app/` — Shared app-level composables (root navigation host)

## SKIE

The SKIE Gradle plugin is applied here. All features are enabled by default:
- Sealed classes → Swift exhaustive enums (`onEnum(of:)`)
- `Flow<T>` → `AsyncSequence`
- `suspend fun` → `async throws`

No explicit SKIE configuration is needed unless disabling a specific feature.

## Adding Platform-Specific Code

- iOS implementations go in `src/iosMain/` with `actual` declarations matching `expect` in `commonMain`
- For feature-level platform code, prefer adding `iosMain` source sets in the feature module itself rather than here
- This module should only contain app-level iOS wiring (DI bootstrap, framework entry point)
