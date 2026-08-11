---
name: sync-upstream
description: >
  Sync the Switchboard client with a newer official LibreChat server version —
  a stable release, a release candidate, or a PARTIAL sync up to an untagged upstream
  commit (e.g. current dev HEAD). If the client is ALREADY at the target commit, runs
  an AUDIT of the last synced delta to catch anything that was missed. Coordinates two
  dynamic workflows (discovery, implementation) around human decision gates.
allowed-tools: Bash, Read, Glob, Grep, Write, Edit, WebFetch, WebSearch, Workflow, AskUserQuestion
argument-hint: "[target] (stable tag | rc tag | branch dev/main | commit SHA; add 'audit' or '--full' to force/ widen an audit; defaults to latest stable)"
---

# Sync Upstream

Synchronize the Switchboard app with a newer version of the official LibreChat server.

**You are the coordinator.** You run the lightweight deterministic work yourself with `Bash`
(prereqs, target/mode resolution, final consistency asserts) and own the three human decision
points. All heavy parallel work is delegated to **two dynamic workflows** — you never read or
write app code yourself.

```
[you]  0. Prereqs + resolve target + detect mode          (Bash — fail loud on ambiguity)
  │
[WF1]  1. sync-upstream-discovery   — parallel multi-angle sweep → converged findings
  │
[you]  2. HITL INTERVIEW            — the one real gate: target + scope + direction   (AskUserQuestion)
  │
[WF2]  3. sync-upstream-implement   — implement → build+consistency gates → ≥2 independent review passes
  │
[you]  4. TEST + PUSH TAIL          — present test plan, facilitate device testing, hold the push gate
```

Two workflows do the work; you hold the seams. **A workflow cannot pause to ask the user** — that
is exactly why the target choice, the proposal approval, and the no-push/device-test boundary live
in your main loop *between* invocations, never inside a script.

## Two modes

Resolve these before anything else (Phase 0), because they change the whole run:

| Mode | Trigger | Goal | Version bookkeeping |
|------|---------|------|---------------------|
| **sync** | resolved target commit ≠ current pin | absorb new upstream changes | advances (version.properties, UPSTREAM_VERSION, submodule, commit map) |
| **audit** | resolved target commit **==** current pin (or `audit` in `$ARGUMENTS`) | re-check that the LAST synced delta was fully/correctly absorbed — find misses | **none — the pin does not move** |

Audit's default range is the **last synced delta** (`previous pin → current pin`), recovered from
`UPSTREAM_VERSION`'s own git history. `--full` widens it to full-pin reconciliation.

## Target Modes (sync)

| Mode | Target looks like | `backendTargetVersion` | UPSTREAM_VERSION | Gating strategy |
|------|-------------------|------------------------|------------------|-----------------|
| **release** | `v0.8.8` (default: latest stable tag) | `0.8.8` | `tag=v0.8.8`, `commit=<tag commit>` | `isCompatibleOrNewer(version, "0.8.8-rc1")` |
| **rc** | `v0.8.8-rc1` | `0.8.8-rc1` | `tag=v0.8.8-rc1`, `commit=<tag commit>` | `isCompatibleOrNewer(version, "0.8.8-rc1")` |
| **partial** | `dev`, `main`, or a SHA | `<base>+dev.<sha8>` | `tag=<last v-tag at/before commit>`, `commit=<target SHA>` | `supportsFeature(detected, "<next-rc-line>", landedDate)` |

Shared invariants: the **diff base is always `commit=` from `UPSTREAM_VERSION`** (never `tag=`);
`BackendVersion.parse()` handles all three forms; a later sync from a partial baseline is normal.

---

## Phase 0 — Prereqs, target & mode (you, with Bash)

Working directory is already the mobile repo root — do **not** `cd` elsewhere; rely on relative paths.
Fail LOUD on any ambiguity below — never guess a baseline or target for the user.

1. **Submodule.** `git submodule status`. If `upstream/` missing:
   `git submodule add https://github.com/danny-avila/LibreChat.git upstream && git submodule update --init`.
2. **Version file.** `cat UPSTREAM_VERSION`. If missing, create from `version.properties`
   `backendTargetVersion` + submodule HEAD (`tag=v<version>`, `commit=<HEAD>`, `date=$(date +%Y-%m-%d)`).
3. **Clean tree.** `git status --porcelain`. If dirty → **ask the user** to commit/stash; wait.
4. **Baseline consistency** (compute, then apply — a partial/rc baseline is NOT an inconsistency):
   - `X.Y.Z+dev.<sha8>` → `<sha8>` must be a prefix of `commit=`; verify the sha, not tag equality.
   - `X.Y.Z-rcN` / plain `X.Y.Z` → must equal `tag=` without `v`.
   - Always: submodule HEAD must equal `commit=`.
   - On failure → **ask the user** which state is the true baseline; wait. Never auto-pick.
5. **Fetch + resolve target.** `cd upstream && git fetch origin --tags && git fetch origin dev main`.
   Resolve `$ARGUMENTS` (see Target Modes) to a full `{target_commit}`:
   - none → newest stable tag (exclude `-rc/-beta/-alpha`), but still enumerate newer rc tags and how
     far `dev`/`main` are ahead (`git rev-list --count {base_commit}..origin/dev`) for the interview.
   - tag (stable or rc) → that tag; never silently drop an rc the user named.
   - branch/SHA → **partial**; `{base_version}` from `git show {sha}:package.json`,
     `{anchor_tag}` = `git describe --tags --abbrev=0 --match "v[0-9]*" {sha}`
     (`--match` is required — upstream also tags Helm charts like `chart-2.0.7`).
6. **Detect mode.** If `{target_commit}` == `commit=` (submodule HEAD) **or** `$ARGUMENTS` contains
   `audit` → **audit mode**; the audit base is the previous `commit=` from
   `git log -p --format=%H -- UPSTREAM_VERSION` (or full-pin if `--full`). Else **sync mode**.
   If sync mode and no newer tags AND branch heads == base → tell the user "already up to date" and
   offer an audit instead of stopping.
7. **Artifacts dir.** `mkdir -p .claude/sync-upstream/artifacts`.
8. **Mirrored constants.** `scripts/check-mirrors.py --from {base_commit} --to {target_commit} --diff`
   Some upstream values are never served over the API, so the client hardcodes a copy — which
   providers take documents natively, which MIME types the parser extracts, which feedback reasons
   the write route accepts. These drift **silently**: nothing fails to decode, nothing errors, the
   app just makes worse decisions as it ages, so the ordinary diff sweep reads them as inert
   constant edits and defers them. **Exit 1** means at least one moved; the output names the Kotlin
   file to reconcile. **Exit 2 is not drift** — it is a fatal (empty submodule, unresolvable
   revision, bad registry); fix the setup and re-run rather than reporting it as a finding.
   Carry every DRIFT and `??` row into the Phase 2 interview as its own item — a `??` means upstream
   renamed or deleted the symbol, so the registry entry is wrong either way.
   Registry: `scripts/mirrors.json`. Adding a mirror to the codebase means adding an entry there in
   the same PR.

If a prior run's artifacts exist (`ls .claude/sync-upstream/artifacts/`), offer to resume from them.

---

## Phase 1 — Discovery (WF1)

Invoke the discovery workflow. It runs the parallel multi-angle sweep, converges + cross-references
the mobile codebase, and runs a completeness critic. Watch its phase tree live via `/workflows`.

```
Workflow({ name: "sync-upstream-discovery", args: {
  mode, baseCommit, targetCommit, targetMode, baseVersion, anchorTag,
  skillDir: "<abs path to .claude/skills/sync-upstream>",
  artifactsDir: "<abs path to .claude/sync-upstream/artifacts>",
} })
```

It returns `{ mode, range, findings[], counts, criticComplete, reportPath }` and writes
`artifacts/discovery-report.md`. Each finding is machine-complete: category, severity, mobile-gap
status, iosMain coverage, **landing commit + UTC `landedDate`**, first-target-line, gate form. Do not
paraphrase-and-lose those — thread the structured objects straight into WF2.

---

## Phase 2 — HITL interview (you)

This is the single human gate. Present the converged report, then use `AskUserQuestion` to settle:

- **Target / scope** (sync): confirm the resolved target or switch (stable / rc / partial — the findings
  are already tagged by target-line, so switching just filters). If the user switches, you have the
  data; no re-run needed unless they name a target outside the swept superset (then re-invoke WF1).
- **Which items** to implement now vs defer (Breaking + Security cannot be deferred — say so).
- **Direction** on UI items that don't map cleanly to Compose (surface the 2-3 options per item).
- **Audit**: which surfaced misses to fix now.
- **Drifted mirrors** (Phase 0 step 8): each one is a hand-maintained copy that upstream has moved
  underneath. They are cheap to reconcile and invisible if skipped — present them explicitly rather
  than folding them into the general findings list.
- Items marked `backend-blocked` are shown as knowingly-skipped, never built.

Persist the approved, scoped list to `artifacts/approved-{target}.md`. If the user cancels, archive the
report and stop cleanly. **Do not invoke WF2 until the user approves.**

---

## Phase 3 — Implementation (WF2)

Invoke the implementation workflow with the approved list. It implements per group, runs build +
consistency gates, then ≥2 mandatory independent review passes (stateless ⇒ independent by
construction; CONFIRMED vs PLAUSIBLE; iteration-capped). **It ends at local commits — it never pushes.**

```
Workflow({ name: "sync-upstream-implement", args: {
  approvedItems, mode, targetMode, targetCommit, baseVersion, anchorTag,
  targetTag,               // release/rc only; null for partial
  sha8,                    // partial only
  bumpVersion,             // FALSE in audit mode
  branchName,              // chore/sync-upstream-{tag} | -dev-{sha8} | -audit-{sha8}
  skillDir, artifactsDir,
  reportPath,              // artifacts/discovery-report.md
} })
```

Returns `{ branchName, buildResults, buildGreen, consistency, reviewPasses, reviewClean,
unresolvedConfirmed, testPlan }`.

**Then re-assert independently (you, with Bash)** — do not trust the workflow's self-report blindly:
- sync: `sha8` is a prefix of `commit=`; `cd upstream && git rev-parse HEAD` == `commit=`;
  `BackendCommitMap.kt` contains the 12-char prefix of `commit=` (same check `release.yml` runs);
  README upper bound matches; each `supportsFeature` row's `landedDate` ≤ synced commit's UTC date.
- audit: the pin did **not** move — `commit=`, `backendTargetVersion`, submodule HEAD, and
  `BackendCommitMap.kt` are all unchanged from before the audit.

If `buildGreen` is false or `unresolvedConfirmed` is true, report it and route back into WF2 (scoped
to the failures) rather than proceeding.

---

## Phase 4 — Test & push tail (you) — HARD STOP

> **Phase 3 ends at local commits. DO NOT push. DO NOT open a PR.** Build + detekt + tests confirm
> scaffolding, not feature correctness. A sync must be device-tested against a real backend on BOTH
> Android and iOS (incl. older-server compat) before a PR is offered. The branch stays local-only
> until the user walks the test plan and **explicitly** says "push it" / "open the PR".

Present:

```
## Sync Ready for Device Testing: {baseline} → {target}   [{mode}]
Branch `{branchName}` is local-only. Not pushed. No PR opened.

### Files changed        {grouped by module}
### Test plan            {WF2 testPlan — Android / iOS / compat}
### How to build & run   Android: assembleDebug + install. iOS: linkDebugFrameworkIosSimulatorArm64, run in Xcode.
                         Connect to a target-version server + an older supported server; walk the plan.
                         Partial: verify new features HIDDEN on the older server, visible on a server the
                         regenerated BackendCommitMap COVERS (between landing and the pin). A server NEWER
                         than the pin resolves to no-version → features fail closed by design (not a bug),
                         and your own dev server drifts past the pin within days.
### Version updates      {sync: the bumps; audit: "none — pin unchanged"}
```

Then:
- User reports bugs → re-invoke **WF2** scoped to just those fixes (same branch), loop until they're happy.
- User explicitly authorizes the push → only then run `git push` / `gh pr create`. Not before.

Dispatch on-device test facilitation as needed (split by platform, or platform × feature area for large
syncs). Device testing is the human's; you facilitate and route blockers back into WF2.

---

## Reference files (read by the workflows' agents, not by you)

`${CLAUDE_SKILL_DIR}/reference/`: `upstream-paths.md` (diff surface), `api-mapping.md`,
`model-mapping.md`, `ui-mapping.md`, `upstream-backend-gaps.md` (client-only vapor features — do not
build), `android-architecture.md` (implementer patterns). Root: `DISCOVERY.md`, `VERSION_GATES.md`,
`BackendVersion.kt`, `scripts/mirrors.json` (hand-mirrored upstream constants — see Phase 0 step 8).

## Error recovery

| Situation | Recovery |
|-----------|----------|
| Submodule / UPSTREAM_VERSION missing | Recreate (Phase 0 steps 1–2) |
| Dirty tree / baseline inconsistency / SHA not found | **Ask the user** — never auto-resolve |
| Already up to date | Offer an **audit** (don't just stop) |
| No newer tags but dev/main ahead | Offer a **partial** sync |
| WF1/WF2 returns partial (agent died) | Re-invoke with `resumeFromRunId` (cached agents replay); artifacts under `artifacts/` show the phase reached |
| BackendCommitMap missing the pin (release.yml net) | WF2's regen step runs AFTER submodule checkout; re-run if needed |
| Build/consistency/review fail | Reported in WF2's return; re-invoke WF2 scoped to the failures |
| Session compacted mid-run | Artifacts dir is the resume authority; `resumeFromRunId` only within a single workflow invocation |
