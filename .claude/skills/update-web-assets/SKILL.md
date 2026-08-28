---
name: update-web-assets
description: >
  Update the third-party JavaScript vendored into the app for the artifact,
  diagram, and math WebViews (KaTeX, mermaid, marked, highlight.js, Tailwind,
  Babel, React). Checks what is outdated, reads each pin's rationale before
  proposing a bump, re-downloads from the official source, verifies the lock,
  and runs the gates. Ends by naming what must be checked on a device, because
  a broken WebView asset fails silently. Use when bumping a vendored web
  library, adding a new one, or investigating whether a rendering bug comes
  from a stale pin.
allowed-tools: Bash, Read, Edit, Write, Glob, Grep, WebFetch, AskUserQuestion
argument-hint: "[asset-id ...] (optional, defaults to checking all of them)"
---

# Update vendored web assets

The artifact, diagram and math renderers are WebViews, and every script they execute ships
inside the app. `scripts/web-assets.json` is the registry; `scripts/vendor-web-assets.py`
moves the bytes; `scripts/web-assets.lock.json` records a sha256 per file and CI verifies it.

**Read `scripts/web-assets.json` before doing anything.** Every entry carries a
`pin_reason`, and several of them are load-bearing rather than informational — two pins
must NOT be moved to the newest version, and the reason is in the file, not in this skill.

## What makes this different from a Gradle dependency bump

A wrong version here does not fail the build, fail a test, or throw anything Kotlin can
catch. The failure happens inside the WebView, and the usual shape of it is a blank box or
a feature that quietly stops working. Both libraries this system replaced were already
broken that way before anyone noticed:

- **marked** deleted its `highlight` option in v5. Passing one to `setOptions` is accepted
  and silently ignored, so syntax highlighting had been dead with no error anywhere.
- **highlight.js** was being loaded from a URL that served CommonJS. In a browser
  `<script>` that throws immediately and never defines `hljs`.

So: the gates below prove the wiring is *consistent*, and only a device proves it *renders*.
Do not report a bump as done on green gates alone.

## Phase 0 — Establish what is stale

```bash
scripts/vendor-web-assets.py --check      # tree matches the lock (should be clean first)
scripts/vendor-web-assets.py --outdated   # what npm has that we don't
scripts/vendor-web-assets.py --list       # what each asset is for, and who uses it
```

`--outdated` prints each pin's `pin_reason` next to any version it reports. Read it. It is
advisory output about a registry that has opinions.

If `--check` fails before you have changed anything, stop and report it — someone
hand-edited a vendored file, or a repin was left half-applied. That is a finding to raise,
not a precondition to quietly re-sync away.

## Phase 1 — Decide, per asset

For each asset the user named (or each one `--outdated` flags, if they named none):

1. Read its `pin_reason` in the registry.
2. If the reason forbids or constrains the bump, say so and **do not** bump it. Two known
   standing constraints, both explained in full in the registry:
   - **mermaid** must stay on the v10 line (v11+ is ESM-only and fails in Android WebView).
   - **react** / **react-dom** must move together and are bounded by UMD availability.
3. Check the upstream changelog for breaking changes in the range, especially anything
   touching the entry point path the registry's `files` block names. A package that
   reorganises its `dist/` is the most likely way a bump fails, and `--sync` will refuse
   with a clear error if a declared path no longer exists.
4. For a major version, use AskUserQuestion rather than deciding alone.

`tailwind` is pinned to a URL, not npm, because Tailwind v3 never published a browser
build. `--bump` refuses it deliberately; repinning it means editing both the version and
every file URL in the registry by hand, and moving to `@tailwindcss/browser` means moving
to v4, which is a behaviour change for artifacts (the registry explains which utilities
changed).

## Phase 2 — Apply

```bash
scripts/vendor-web-assets.py --bump <id> <version>
```

This repins the registry, wipes that asset's directory, re-downloads from the official
tarball, rewrites the lock, and regenerates `VendoredWebAssets.kt`. The wipe is deliberate:
a repin that drops a file must not leave the old one behind to be served.

Adding a **new** asset instead: add a registry entry (with a real `why`, `pin_reason` and
`used_by`), run `--sync`, then reference it from the renderer by a path relative to the
document base — never an absolute URL.

If the bump changes the entry point path or an API the renderer calls, update the HTML
builder in the same pass. The builders are:

| Asset | Built in |
|---|---|
| katex | `androidMain/components/LatexBlock.kt`, `iosMain/components/PlatformMediaComponents.ios.kt` |
| mermaid | `androidMain/components/MermaidDiagram.kt`, `commonMain/…/artifact/MermaidWebContent.kt`, `iosMain/components/PlatformMediaComponents.ios.kt` |
| marked, marked-highlight, highlight | `commonMain/…/artifact/MarkdownWebContent.kt` |
| tailwind, babel, react, react-dom | `commonMain/…/artifact/ArtifactWebContent.kt` |

Note KaTeX and mermaid each have **two** independent HTML builders (Android and iOS) that
do not share code. Changing one and not the other is the easiest mistake to make here.

## Phase 3 — Gates

```bash
scripts/vendor-web-assets.py --check
./gradlew :feature:chat:testDebugUnitTest --tests '*VendoredAssetReferenceTest*' \
                                          --tests '*ReactArtifactRenderTest*'
./gradlew :feature:chat:detekt :feature:chat:detektMetadataCommonMain
./gradlew :app:assembleDebug
```

`VendoredAssetReferenceTest` is the one that matters most: it asserts that no document
references a remote origin, that no CSP lets one execute, and that **every path a document
references exists in the generated manifest** — which is what catches a bump that renamed a
dist file, since the symptom otherwise is a blank WebView.

Per the repo's workflow preference, skip iOS builds unless asked; if asked, stop at the
Gradle framework link (`./gradlew :feature:chat:compileKotlinIosSimulatorArm64`).

## Phase 4 — Report, and name the device check

Report: which assets moved, from and to; the APK size delta if it is material; anything in
a `pin_reason` you deliberately did not act on.

Then state plainly that the gates cannot confirm rendering, and name what to look at for
the assets that actually moved:

| Asset | What to look at on a device |
|---|---|
| katex | a message containing `$$x^2$$` — check glyphs AND that fonts loaded (no fallback serif) |
| mermaid | a ```mermaid block in a message, and a mermaid artifact opened fullscreen |
| marked / marked-highlight / highlight | a markdown artifact with a fenced code block — highlighting present, theme matches light/dark |
| tailwind | an HTML artifact using utility classes |
| babel / react / react-dom | a React artifact that uses hooks; then one importing an unbundled package, which must show the naming error rather than a blank box |

Do not open a PR. Per the repo's convention, stop at local commits and let the user device-test first.
