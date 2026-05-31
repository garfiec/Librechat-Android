# Releasing

How releases are versioned, signed, and published for LibreChat Mobile (Android).

## Versioning

The app version lives in **one place**: `version.properties` at the repo root.

```properties
versionName=0.1.0          # human-facing semver — the only thing you bump
backendTargetVersion=0.8.5 # LibreChat backend this build targets (best-tested)
```

- `AndroidApplicationConventionPlugin` reads `versionName` and **derives** `versionCode` as
  `MAJOR*10000 + MINOR*100 + PATCH` (digits XXYYZZ): `0.1.0` → `100`, `1.2.3` → `10203`.
  Monotonic as long as semver increases; limits are MINOR ≤ 99, PATCH ≤ 99.
- The About screen reads the *installed* version via `AppInfo` (package metadata), so it can never drift.
- This is the **app's** version and is intentionally independent of `backendTargetVersion`.
- `backendTargetVersion` is the **single source of truth** for the LibreChat backend the app
  targets. The app reads it via `BackendVersion.SUPPORTED_BACKEND_VERSION` (code-generated from
  this property by core/common's `generateBackendVersion` task), and `release.yml` reads the same
  key to add a **Target backend:** line to each release's notes. Edit the property — never the
  `SUPPORTED_BACKEND_VERSION` literal in `core/common/BackendVersion.kt` (it no longer exists as a literal).

Both platforms derive from the same `versionName`, so they stay in lockstep:

| Platform | versionName | versionCode | Where |
|---|---|---|---|
| Android | `versionName` verbatim | derived XXYYZZ | `AndroidApplicationConventionPlugin` reads `version.properties` at build |
| iOS | `CFBundleShortVersionString` | `CFBundleVersion` (same XXYYZZ) | *Stamp Version* Xcode build phase reads `version.properties` at build |

> iOS isn't published on GitHub Releases — the stamp exists only to keep the two platforms'
> reported versions identical. The "Stamp Version from version.properties" run-script phase
> runs after Info.plist is in the bundle and before codesign, so the committed `Info.plist`
> literals (and the unused `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` build settings) are
> just a fallback snapshot — the build always reflects `version.properties`.

Bump it with the script (also run by the release workflow):

```bash
scripts/bump-version.sh patch   # 0.1.0 -> 0.1.1  (versionCode 100 -> 101)
scripts/bump-version.sh minor   # 0.1.0 -> 0.2.0  (versionCode 100 -> 200)
scripts/bump-version.sh major   # 0.1.0 -> 1.0.0  (versionCode 100 -> 10000)
```

### Release candidates

To ship a candidate before a stable release, use the pre-release bumps:

```bash
scripts/bump-version.sh preminor  # 0.1.0    -> 0.2.0-rc1   (next minor, candidate 1)
scripts/bump-version.sh rc        # 0.2.0-rc1 -> 0.2.0-rc2  (next candidate)
scripts/bump-version.sh finalize  # 0.2.0-rc2 -> 0.2.0      (promote to stable)
```

`prepatch`/`preminor`/`premajor` start a candidate for the bumped version; `rc` advances
the candidate number; `finalize` drops the suffix to promote the current candidate. A
hyphenated version is published as a GitHub **pre-release**, which Obtainium skips unless
the user enables *Include prereleases*.

> The `-rcN` suffix is stripped when deriving the versionCode, so `0.2.0-rc1`, `0.2.0-rc2`,
> and the final `0.2.0` all share one versionCode. Candidates install over each other fine,
> but if users update *from* a candidate *to* the final build, bump the patch instead of
> finalizing so the version visibly advances.

## One-time signing key setup

Releases must be signed with **one permanent key**. If the key ever changes, every existing
user's update fails with a signature conflict and recovery requires uninstall + reinstall
(full data loss). There is no key reset for direct/sideloaded distribution.

1. **Generate the keystore** (do this once, keep it forever):

   ```bash
   keytool -genkeypair -v \
     -keystore librechat-release.jks \
     -alias librechat \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -storetype PKCS12 \
     -dname "CN=LibreChat Mobile, O=LibreChat, C=US"
   ```

   Back it up in **2+ secure locations** (password manager / offline). Do **not** commit it
   (`.gitignore` already excludes `*.jks` and `keystore.properties`).

2. **Add GitHub repository secrets** (Settings → Secrets and variables → Actions). Ideally put
   them in an Environment named `release` with required reviewers, so the release job is gated.

   | Secret | Value |
   |---|---|
   | `SIGNING_KEYSTORE_BASE64` | `openssl base64 -A < librechat-release.jks` |
   | `SIGNING_STORE_PASSWORD` | keystore password |
   | `SIGNING_KEY_PASSWORD` | key password |
   | `SIGNING_KEY_ALIAS` | `librechat` |

3. **Publish the certificate fingerprint** in the README so users can verify:

   ```bash
   keytool -list -v -keystore librechat-release.jks -alias librechat   # SHA-256 line
   ```

### Local signed builds (optional)

Create `keystore.properties` at the repo root (git-ignored):

```properties
storeFile=librechat-release.jks
storePassword=...
keyAlias=librechat
keyPassword=...
```

Then `./gradlew :app:assembleRelease` produces a signed APK. Without env vars or this file,
release builds fall back to the debug key so local builds and CI checks still work.

## Cutting a release

1. Actions → **Release** → *Run workflow* → choose the bump (`patch`/`minor`/`major`, or a
   `prepatch`/`preminor`/`premajor`/`rc`/`finalize` candidate).
2. The job bumps `version.properties`, builds a signed universal APK, signs a SLSA
   build-provenance attestation for it, and **only then** commits + tags `vX.Y.Z` and creates
   a **draft** GitHub Release with auto-generated notes and a `.sha256` checksum. Candidate
   versions are flagged as pre-releases automatically. If the build fails, nothing is committed
   or tagged — just re-run after fixing it.
3. Review the draft, edit the notes for users, then **publish**.
4. Obtainium / IzzyOnDroid pick up the published release automatically.

> **Drafts are invisible to Obtainium.** A release stays hidden from every user until you
> click **Publish** — Obtainium's GitHub source skips drafts. The workflow ends with a
> warning annotation reminding you to publish; don't skip it.

> **Branch protection:** the workflow pushes the version-bump commit and tag to the default
> branch, which is ruleset-protected. The `github-actions` bot can't be a ruleset bypass
> actor on a personal (non-org) repo, so the workflow authenticates the push with a
> fine-grained PAT instead. Create one (**Contents: read & write**, this repo only, short
> expiry) and add it as a secret named `RELEASE_PAT` in the `release` environment. It pushes
> as the repo owner (already a bypass actor); without it the run fails fast at the
> *Verify signing secrets* step.

## Verifying a release

Three checks are available to anyone who downloads an APK, in descending order of strength:

1. **Build provenance (strongest)** — `gh attestation verify <apk> --repo garfiec/Librechat-Mobile
   --signer-workflow garfiec/Librechat-Mobile/.github/workflows/release.yml`. This is the only
   check that resists a *tampered upload*: it's signed by GitHub's CI via Sigstore and can't be
   forged outside the runner. Each release's notes link the run + attestation, but a link is not
   proof — only this command verifies the downloaded bytes. See the README install section.
2. **Signing certificate** — `apksigner verify --print-certs <apk>`, compared against the
   published cert SHA-256. Catches a build signed with a different key.
3. **Checksum** — `sha256sum -c <apk>.sha256`. Catches accidental corruption or a download MITM
   only; it does *not* defend against a malicious release (the attacker would control both files).

## Distribution channels

- **Obtainium** — tracks GitHub Releases directly (see the README install section). Stable
  releases are full releases; `-rcN` candidate tags are marked pre-release.
- **IzzyOnDroid** (future) — ingests the developer-signed APK and pins the signing key via
  `AllowedAPKSigningKeys`. Use the *same* key. Reproducible builds earn a verification badge.
