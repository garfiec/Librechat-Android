# GitHub CI/CD

## Workflow: App Store Upload

File: `.github/workflows/appstore.yml`

- **Manual only** (`workflow_dispatch`) with a required `tag` input (e.g. `v2026.07.3`) —
  cutting a GitHub release never uploads to Apple.
- Single `upload` job: `macos-latest`, `environment: release` (reviewer-gated secrets).
- Xcode **cloud signing** via an App Store Connect API key — no certificates or profiles
  stored anywhere; `-allowProvisioningUpdates` manages them in Apple's cloud.
- Archives the tagged commit, then `xcodebuild -exportArchive` with
  `method: app-store-connect`, `destination: upload` uploads during export (no altool/fastlane).
- Passes `IOS_BUILD_NUMBER_SUFFIX` (run_number×100 + run_attempt) so the "Stamp Version"
  build phase emits a per-upload-unique `CFBundleVersion` (`YYYYMMPP.N`).
- Secrets (in the `release` environment): `ASC_API_KEY_P8_BASE64`, `ASC_API_KEY_ID`,
  `ASC_API_ISSUER_ID`, `APPLE_TEAM_ID`. Setup runbook: `docs/RELEASING.md`.

## Workflow: CI

File: `.github/workflows/ci.yml`

### Triggers

- Push to `develop`
- Pull requests targeting `develop`

### Jobs

#### `lint`
- `./gradlew detekt detektMetadataCommonMain :app:lint --continue`
- Uploads merged detekt SARIF to GitHub Code Scanning + lint HTML report

#### `test`
- `./gradlew test`
- Uploads test results XML

#### `android` (Build Android App)
- `./gradlew :app:assembleDebug`
- `./gradlew :feature:chat:assembleDebugAndroidTest` — compile-only gate for the chat instrumented
  suite, which *runs* on a local emulator (`connectedDebugAndroidTest`), never in CI. Scoped to the
  one module deliberately: a repo-wide `assembleDebugAndroidTest` would also compile `:app`'s stale
  `androidTest` sources.
- Uploads the debug APK as an artifact (90-day retention) and posts its download link to the PR

#### `ios` (Build iOS App)
- Selects latest stable Xcode
- Runs `./gradlew :shared:iosSimulatorArm64Test` (the iOS Koin graph test — the `ubuntu` `test` job
  cannot run Kotlin/Native tests)
- Caches `~/.konan`
- Runs `xcodebuild` for the iOS simulator (arm64-only, no signing). The Kotlin framework is built by the Xcode "Compile Kotlin Framework" build phase (`./gradlew :shared:embedAndSignAppleFrameworkForXcode`) — no separate link step.

#### `ios-release-link` (iOS Release Link (OOM canary))
- `./gradlew :shared:linkReleaseFrameworkIosArm64` — the Release device link that release.yml/appstore.yml archives run
- Exists because the Release link's DevirtualizationAnalysis pass OOM'd the 7 GB runner on every release Jul 2–25, 2026 (KT-80367) while PR CI stayed green: Debug/simulator builds skip whole-program optimization, so code growth crossing the heap ceiling was invisible until release day. This job fails the PR that crosses it instead.

### Environment

- `lint` + `test` + `android` jobs: `ubuntu-latest`, JDK 21
- `ios` job: `macos-latest` (Apple Silicon — required since there's no `iosSimulatorX64` target), JDK 21
- Concurrency: cancels in-progress runs for same branch

### Notes

- No release signing configured yet
- Instrumented/UI tests are never *executed* in CI (no emulator) — `:feature:chat`'s suite is only
  compiled, as a gate. Executed test coverage in CI is unit tests plus the iOS Koin graph test.
- The debug APK is uploaded as an artifact; the iOS app is built but not uploaded
- Detekt SARIF is uploaded to GitHub Code Scanning (requires GitHub Advanced Security for private repos)
