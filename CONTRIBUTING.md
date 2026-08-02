# Contributing to Switchboard

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | |
| Android Studio or IntelliJ IDEA | Latest stable | Recommended IDE for all code editing (Android + iOS) |
| Xcode | 15+ | Required for iOS SDK, simulators, and `xcodebuild` CLI. IDE not needed. |
| Apple Silicon Mac | | Intel Macs not supported (no `iosSimulatorX64` target) |
| iOS Deployment Target | 16.0+ | |
| Gradle | 9.4.1 (via wrapper) | |
| Kotlin | 2.3.20 | |

## IDE Setup

**Use Android Studio or IntelliJ IDEA for all development** — both Android and iOS. Nearly all code is Kotlin (shared business logic, Compose Multiplatform UI, `iosMain` platform implementations). The 4 remaining Swift files are a thin hosting layer that rarely changes.

Xcode must be installed for the iOS toolchain (SDKs, simulators, `xcodebuild`), but you never need to open the Xcode IDE. All builds and runs are done via CLI.

## Getting Started

```bash
# Clone with submodules (upstream LibreChat server reference)
git clone --recursive https://github.com/garfiec/Librechat-Mobile.git
cd Librechat-Mobile
```

### Android

Build and install on a connected device or emulator:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or run directly via Android Studio's run configuration.

### iOS

Build and run on the iOS Simulator from CLI:

```bash
# Build the app (Xcode build phases handle the shared framework automatically)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -derivedDataPath iosApp/build build

# Boot simulator, install, and launch
xcrun simctl boot "iPhone 16"
xcrun simctl install booted iosApp/build/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch booted com.garfiec.librechat.ios
```

> **Note:** The first build takes several minutes while the Kotlin/Native toolchain downloads.

To build only the shared KMP framework (e.g., after changing shared Kotlin code):

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## Project Structure

This is a Kotlin Multiplatform (KMP) app. Business logic and UI are shared across Android and iOS via Compose Multiplatform.

### Modules

```
app/                   -> Android entry point, adaptive navigation (phone/tablet)
shared/                -> KMP umbrella module (commonMain, androidMain, iosMain)
core/common/           -> Result type, dispatcher DI, coroutine scopes, extensions
core/model/            -> @Serializable data classes (pure Kotlin)
core/network/          -> Ktor client, API services, SSE client, auth interceptor
core/data/             -> Room DB, DataStore, repositories
core/ui/               -> Material 3 theme, shared composables
feature/auth/          -> Login, Register, 2FA, OAuth, Forgot Password
feature/chat/          -> Real-time chat with SSE streaming, message rendering
feature/conversations/ -> Paginated list, CRUD, tags, search, export/import
feature/settings/      -> Account, appearance, about
feature/agents/        -> Agent marketplace with search and categories
feature/files/         -> File upload, management, image viewer
```

Source sets: `commonMain` = shared code, `androidMain` / `iosMain` = platform-specific implementations.

### Convention Plugins

`build-logic/convention/` contains 13 convention plugins (8 Android + 5 KMP) that standardize module configuration. See `CLAUDE.md` for details.

## Adding a New Feature Module

1. Create the module directory under `feature/`
2. Apply the convention plugin in `build.gradle.kts`:
   - `librechat.kmp.feature` — for KMP modules with shared iOS + Android code (most features)
   - `librechat.mobile.feature` — for Android-only modules
3. Create `di/<Feature>Module.kt` with a Koin `module { }` containing `viewModelOf(::YourViewModel)` definitions
4. Register the module in the application startup Koin configuration
5. Use `koinViewModel()` in screen composables to inject ViewModels

## Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- ViewModels live in `commonMain` only — no platform-specific ViewModels
- Handle platform differences via `expect`/`actual` declarations or delegate factories
- Use [Kermit](https://github.com/touchlab/Kermit) for logging (`co.touchlab.kermit.Logger`), not `println`, `Log.d`, or `NSLog`
- Feature modules depend on `:core:*` only, never on each other

## Testing

- Unit tests go in `commonTest` or `androidUnitTest`
- Maestro flows for E2E testing (8 existing flows in `.maestro/`)
- Run tests before submitting:

```bash
./gradlew test
```

## Architecture Rules

- Feature modules depend on `:core:*` only, never on each other
- Unidirectional data flow: UI -> ViewModel -> Repository -> API/Room
- Room is a read-through cache; server is source of truth
- Custom SSE parser over raw `ByteReadChannel` (not Ktor SSE plugin)

See `CLAUDE.md` for the full architecture overview and backend quirks.
