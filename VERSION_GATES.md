# Version Gates

LibreChat Mobile supports a range of backend server versions. This file catalogs every
place in the codebase where behavior branches based on the detected server version so
the compatibility surface is auditable. When the minimum supported server version is
raised, entries here can be simplified or removed.

The canonical API for version comparisons lives in
`core/common/src/commonMain/kotlin/com/garfiec/librechat/core/common/BackendVersion.kt`:

- `BackendVersion.parse(version)` — parse a loose semver string (`"v0.8.5"`, `"0.8"`, …).
- `BackendVersion.isCompatible(supported, actual)` — exact `major.minor.patch` match.
- `BackendVersion.isCompatibleOrNewer(actual, minimum)` — `actual ≥ minimum` by `(major, minor, patch)`.
- `BackendVersion.extractVersionFromFooter(footer)` — fallback parse from `customFooter`.

The detected server version is exposed via `ConfigRepository.detectedBackendVersion`
(populated once `checkBackendVersion()` runs on app startup / server-switch).

`BackendVersion.SUPPORTED_BACKEND_VERSION` (the backend this build targets) is **generated**
from `backendTargetVersion` in the root `version.properties` by core/common's
`generateBackendVersion` Gradle task — bump that property, not the constant.

## Catalog

| Feature | Gated since | Behavior on older | Behavior on newer | File:line | Safe to remove when min supported server ≥ |
|---|---|---|---|---|---|
| `isCollaborative` agent toggle | v0.8.5 (2026-04-23) | Toggle visible; mobile sends `isCollaborative` + `projectIds` to server | Toggle hidden; inline hint "Access permissions are managed server-side in this version" rendered instead; fields not sent | `feature/agents/.../components/AgentSharingSection.kt` + `feature/agents/.../viewmodel/AgentEditorViewModel.kt` (`observeServerVersion`, `save`) | v0.8.5 |
| `xhigh` reasoning-effort dropdown value | v0.8.5 (2026-04-25) | `xhigh` filtered out of `reasoning_effort` and `effort` dropdowns (older Anthropic/Bedrock/OpenAI schemas reject the unknown enum) | `xhigh` shown alongside `low/medium/high/max` | `core/ui/.../components/EndpointParameterRegistry.kt` (`getDefinitions(xhighEffortSupported)`) + `feature/chat/.../viewmodel/ChatViewModel.kt` (xhigh observer in `init`) | v0.8.5 |

## Guidelines for adding a new gate

1. Call `BackendVersion.isCompatible(...)` or `BackendVersion.isCompatibleOrNewer(...)` — never parse versions ad hoc.
2. Default to **older-server behavior** when the version is unknown (`detectedBackendVersion == null`). The server may not advertise its version; failing open avoids hiding features from self-hosted installs with stripped customFooters.
3. Add a row to the table above. Include file + line anchors and the concrete minimum version at which the gate becomes dead code.
4. If the gated field is a request DTO field, omit it (send `null`) rather than sending a value the server will silently drop — unless you can verify round-trip parity. Silent drops lead to UI state that disagrees with server state.
5. Patch-version gates are supported. Upstream LibreChat regularly ships breaking API and SSE-shape changes inside a patch bump (the same-minor assumption broke us moving 0.8.4 → 0.8.5), so use the exact patch the feature shipped in.
