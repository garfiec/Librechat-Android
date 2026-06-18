# GitHub CI/CD

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

#### `ios` (Build iOS App)
- Selects latest stable Xcode
- Caches `~/.konan`
- Runs `xcodebuild` for the iOS simulator (arm64-only, no signing). The Kotlin framework is built by the Xcode "Compile Kotlin Framework" build phase (`./gradlew :shared:embedAndSignAppleFrameworkForXcode`) — no separate link step.

### Environment

- `lint` + `test` + `android` jobs: `ubuntu-latest`, JDK 21
- `ios` job: `macos-latest` (Apple Silicon — required since there's no `iosSimulatorX64` target), JDK 21
- Concurrency: cancels in-progress runs for same branch

### Notes

- No release signing configured yet
- No instrumented/UI tests in CI (only unit tests)
- APK / iOS app are built but NOT uploaded as artifacts
- Detekt SARIF is uploaded to GitHub Code Scanning (requires GitHub Advanced Security for private repos)
