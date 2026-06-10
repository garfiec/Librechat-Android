# LibreChat Mobile

[![LibreChat](https://img.shields.io/badge/LibreChat-v0.8.4_–_v0.8.6-blue)](https://github.com/danny-avila/LibreChat/releases/tag/v0.8.6)

A third-party native mobile client for [LibreChat](https://www.librechat.ai/) (Android & iOS). Not affiliated with the official LibreChat project — this is an independent app that connects to any self-hosted LibreChat server, no backend modifications required.

> **Backend compatibility:** Tested against LibreChat **v0.8.4 – v0.8.6**. Older releases may work but are not guaranteed; newer releases are supported on a best-effort basis until the next sync.

## Features

- **Chat** — Real-time streaming (SSE), message branching & sibling navigation, stop/regenerate/continue, markdown with syntax highlighting, LaTeX math rendering, code blocks with copy, image display, file attachments, tool call progress cards
- **Inline Artifacts** — Render Mermaid, SVG, HTML, React, and Markdown artifacts directly in chat messages at full message width with content-fit height. Per-type toggle in Settings → Chat → Artifacts.
- **Model Selection** — Searchable bottom sheet grouped by endpoint, model comparison mode
- **Agents** — Marketplace with search and categories, MCP server configuration
- **Conversations** — Paginated list with date grouping, tags, search, rename, archive, delete, share, fork, duplicate, export/import
- **Presets & Prompts** — Save/load chat presets, prompts library with @mention insertion
- **Authentication** — Login, registration, forgot password, two-factor (TOTP + backup codes), OAuth (Google, GitHub, Discord, Facebook, Apple, OpenID)
- **Files** — Upload, list, delete, inline image rendering with pinch-to-zoom
- **Voice** — Speech-to-text input, text-to-speech playback (device and server engines)
- **Settings** — Theme (system/light/dark), account management, data controls
- **Tablet** — Adaptive dual-pane layout (600dp+) with persistent sidebar
- **Accessibility** — Semantic headings, content descriptions, 48dp touch targets, live regions

## Screenshots

| Feature | Phone | Tablet / Foldable |
|---|---|---|
| **Server Connect** — Point the app at any self-hosted LibreChat server | <img src="docs/screenshots/server-url-phone.png" width="280"> | <img src="docs/screenshots/server-url-tablet.png" width="380"> |
| **Home Screen** — Clean welcome screen with voice input and quick access | <img src="docs/screenshots/home-phone.png" width="280"> | <img src="docs/screenshots/home-tablet.png" width="380"> |
| **Conversations Sidebar** — Swipe to open your chat history with search, tags, and date grouping | <img src="docs/screenshots/sidebar-phone.gif" width="280"> | <img src="docs/screenshots/sidebar-tablet.gif" width="380"> |
| **Predictive Back** — Native Android back gesture with peek animation | <img src="docs/screenshots/predictive-back-phone.gif" width="280"> | <img src="docs/screenshots/predictive-back-tablet.gif" width="380"> |
| **Mermaid Diagrams** — Interactive flowcharts and diagrams rendered in-chat | <img src="docs/screenshots/mermaid-phone.png" width="280"> | <img src="docs/screenshots/mermaid-tablet.png" width="380"> |
| **LaTeX Math** — Beautifully typeset equations and formulas | <img src="docs/screenshots/latex-phone.png" width="280"> | <img src="docs/screenshots/latex-tablet.png" width="380"> |
| **Code Blocks** — Syntax-highlighted code with language badge and copy button | <img src="docs/screenshots/code-phone.png" width="280"> | <img src="docs/screenshots/code-tablet.png" width="380"> |
| **Tables** — Clean, scrollable data tables | <img src="docs/screenshots/table-phone.png" width="280"> | <img src="docs/screenshots/table-tablet.png" width="380"> |
| **Extended Thinking** — See the model's reasoning process | <img src="docs/screenshots/thinking-phone.png" width="280"> | <img src="docs/screenshots/thinking-tablet.png" width="380"> |
| **Image Generation** — AI-generated images via agents | <img src="docs/screenshots/imagegen-phone.png" width="280"> | <img src="docs/screenshots/imagegen-tablet.png" width="380"> |
| **Chat Options** — Attach files, switch models, toggle tools, and tune parameters | <img src="docs/screenshots/chat-options-phone.png" width="280"> | <img src="docs/screenshots/chat-options-tablet.png" width="380"> |
| **Model Selection** — Searchable bottom sheet with models grouped by provider | <img src="docs/screenshots/model-selection-phone.png" width="280"> | <img src="docs/screenshots/model-selection-tablet.png" width="380"> |
| **Model Parameters** — Fine-tune temperature, top-p, tokens, and custom instructions | <img src="docs/screenshots/model-params-phone.png" width="280"> | <img src="docs/screenshots/model-params-tablet.png" width="380"> |
| **Photo Upload** — Attach images from your gallery or camera | <img src="docs/screenshots/photo-upload-phone.png" width="280"> | <img src="docs/screenshots/photo-upload-tablet.png" width="380"> |
| **Settings** — Theme, language, layout, and personalization options | <img src="docs/screenshots/settings-phone.png" width="280"> | <img src="docs/screenshots/settings-tablet.png" width="380"> |

## Install (Android)

Pre-built, signed APKs are published on the [Releases](https://github.com/garfiec/Librechat-Mobile/releases) page. No need to build from source.

### Obtainium (recommended)

[Obtainium](https://github.com/ImranR98/Obtainium) installs the app straight from GitHub Releases and keeps it updated automatically.

[![Add to Obtainium](https://img.shields.io/badge/Add%20to-Obtainium-1e88e5?logo=android&logoColor=white)](https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/garfiec/Librechat-Mobile)

1. Install Obtainium from its [releases](https://github.com/ImranR98/Obtainium/releases).
2. Tap the **Add to Obtainium** badge above (or, in Obtainium, *Add App* → paste `https://github.com/garfiec/Librechat-Mobile`).
3. Obtainium tracks new releases and prompts you to update when one ships.

Release-candidate builds (versions like `2026.07.1-rc1`) are published as GitHub *pre-releases* and are ignored unless you enable **Include prereleases** for the app in Obtainium.

### Manual install

Download the latest `librechat-vYYYY.MM.P.apk` from [Releases](https://github.com/garfiec/Librechat-Mobile/releases) and open it on your device (you may need to allow installs from your browser/file manager).

### Verifying the signing key

All releases are signed with the same key, so updates install in place. Verify a downloaded APK matches the published certificate:

```bash
apksigner verify --print-certs librechat-vYYYY.MM.P.apk
# or check the .sha256 checksum attached to each release:
sha256sum -c librechat-vYYYY.MM.P.apk.sha256
```

> Signing certificate SHA-256: `66:8A:71:96:6A:07:06:14:D1:44:95:5D:83:E7:23:6A:3C:ED:77:F8:64:08:57:C3:FA:84:B0:3C:CD:E4:0E:59`

If the signing key ever changed, Android would refuse the update — so this key is permanent. Never install a build signed with a different certificate over an existing install.

### Verifying build provenance (optional, strongest)

Every release APK carries a [SLSA build-provenance attestation](https://github.com/garfiec/Librechat-Mobile/attestations), signed by GitHub Actions via Sigstore. Unlike the `.sha256` — which whoever attaches a release could regenerate — the attestation proves the binary was produced by this repo's release workflow at a specific commit, and **cannot be forged outside GitHub's CI**. With the [GitHub CLI](https://cli.github.com):

```bash
gh attestation verify librechat-vYYYY.MM.P.apk \
  --repo garfiec/Librechat-Mobile \
  --signer-workflow garfiec/Librechat-Mobile/.github/workflows/release.yml
```

A pass confirms the exact bytes you downloaded came from that workflow. The run and attestation are also linked from each release's notes — but those links are convenience, not proof; only the command above verifies the bytes in your hand.

## Server Setup

The app works with any standard LibreChat server. During onboarding, you'll enter your server URL (e.g., `https://chat.example.com` or `http://192.168.1.100:3080`).

### Required Configuration

Add the following to your LibreChat server's `.env` file:

```env
# Safety net for native app clients.
# The app sends a browser User-Agent to pass the uaParser middleware,
# but if it ever fails to parse, this prevents ban point accumulation.
NON_BROWSER_VIOLATION_SCORE=0
```

Without this setting, the server's violation system may accumulate ban points against the mobile client if the User-Agent check fails, eventually locking the account out.

### Notes

- **Registration** — The app respects your server's registration settings. If registration is disabled server-side, only the login form is shown.
- **Self-signed TLS certificates** — Both platforms enforce certificate requirements stricter than browsers, and Android additionally does not trust user-installed CAs by default. See [FAQ.md](FAQ.md#can-i-use-a-self-signed-tls-certificate) for details and workarounds.

## Requirements

| Tool | Version |
|------|---------|
| JDK | 17+ |
| Android Studio or IntelliJ IDEA | Latest stable (recommended IDE for all code editing) |
| Xcode | 15+ (iOS only, Apple Silicon Mac required — IDE not needed, CLI only) |
| iOS Deployment Target | 16.0+ |
| Gradle | 9.4.1 (via wrapper) |
| Kotlin | 2.3.20 |

## Building from Source

### Android

```bash
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

For a release build:

```bash
./gradlew assembleRelease
```

### iOS

**Simulator:**

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -derivedDataPath iosApp/build build
```

**Physical device:**

```bash
./gradlew :shared:linkDebugFrameworkIosArm64
open iosApp/iosApp.xcodeproj
```

Then in Xcode: select your device, set your Team under Signing & Capabilities, and press ⌘R.

See [iosApp/README.md](iosApp/README.md) for full build and launch instructions.

### Tech Stack

- Kotlin Multiplatform (KMP) with shared business logic
- Jetpack Compose (Android) + Compose Multiplatform (iOS)
- Koin (dependency injection)
- Ktor Client (OkHttp on Android, Darwin on iOS)
- Kotlinx Serialization
- Room (cache), DataStore (preferences), EncryptedSharedPreferences / Keychain (tokens)
- Kotlin 2.3.20, compileSdk 36, minSdk 26

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style, and PR guidelines.

## License

This project is licensed under the [MIT License](LICENSE).
