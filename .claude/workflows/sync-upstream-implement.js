export const meta = {
  name: 'sync-upstream-implement',
  description: 'Implement approved upstream-sync changes, run deterministic build + consistency gates, then >=2 mandatory independent review passes. Ends at local commits — never pushes.',
  phases: [
    { title: 'Implement', detail: 'one implementer, commits per group, shared working tree' },
    { title: 'Version bookkeeping', detail: 'checkout submodule then regen commit map (ordering as code)' },
    { title: 'Build gates', detail: 'assembleDebug, detekt, iOS link, check' },
    { title: 'Consistency asserts', detail: 'sha-prefix, HEAD==commit, map-has-pin, landedDate<=synced' },
    { title: 'Independent review', detail: '>=2 stateless passes, CONFIRMED vs PLAUSIBLE, iteration-capped' },
    { title: 'Test plan', detail: 'user-testable behaviors, split Android / iOS / compat' },
  ],
}

// ---------------------------------------------------------------------------
// WF2 — IMPLEMENT + VERIFY + REVIEW.  Invoked by the sync-upstream skill AFTER the
// human approval gate. Runs in the SHARED working tree (no worktree isolation) so
// implement -> verify -> fix operate on the same branch; mutation is sequential,
// only the read-only review agents fan out.
//
// HARD RULE: this script contains NO `git push` / `gh pr create`, and its agents
// are told never to run them. It ends at local commits. Device testing + push
// authorization live in the skill's main loop, never here.
//
// args = {
//   approvedItems: [...]   // human-approved, scoped change list (findings + any direction notes)
//   mode:          'sync' | 'audit'
//   targetMode:    'release' | 'rc' | 'partial'
//   targetCommit:  full SHA
//   baseVersion:   e.g. "0.8.7"
//   anchorTag:     e.g. "v0.8.7"   (UPSTREAM_VERSION tag= for partial; the target tag for release/rc)
//   targetTag:     e.g. "v0.8.8" or "v0.8.8-rc1" (release/rc only; null for partial)
//   sha8:          first 8 chars of targetCommit (partial only)
//   bumpVersion:   boolean  // FALSE in audit mode — the pin does not move
//   branchName:    e.g. "chore/sync-upstream-v0.8.8" | "chore/sync-upstream-dev-6c97a7f4"
//                  |     "chore/sync-upstream-audit-6c97a7f4"
//   skillDir, artifactsDir, reportPath
// }
// ---------------------------------------------------------------------------

const A = args || {}
const MAX_BUILD_FIX_ROUNDS = 3
const MAX_REVIEW_ROUNDS = 4
const REVIEW_ROLES = ['reviewer', 'auditor']

const BUILD_SCHEMA = {
  type: 'object',
  properties: {
    assembleDebug: { type: 'boolean' }, detekt: { type: 'boolean' },
    iosLink: { type: 'boolean' }, check: { type: 'boolean' },
    errors: { type: 'string', description: 'concatenated failing output, empty if all pass' },
  },
  required: ['assembleDebug', 'detekt', 'iosLink', 'check'],
}

async function runBuildGates(round) {
  return agent(
    `Run the four build gates for the LibreChat Mobile sync branch, IN ORDER, and report each pass/fail.
Do NOT use --ignore-failures. Repo root is CWD.
1. ./gradlew assembleDebug                                    (Android compiles)
2. ./gradlew detekt                                           (static analysis, ALL source sets)
3. ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64      (catches iosMain compile errors assembleDebug skips)
4. ./gradlew check                                            (unit tests for touched modules)
Capture the failing output for any gate that fails into "errors". Do not fix anything — just report.`,
    { label: `build-gates:${round}`, phase: 'Build gates', schema: BUILD_SCHEMA }
  )
}

function gatesGreen(b) {
  return b && b.assembleDebug && b.detekt && b.iosLink && b.check
}

phase('Implement')

await agent(
  `You are the implementer for a LibreChat Mobile upstream ${A.mode}. Repo root is CWD. Read the root
CLAUDE.md, ${A.skillDir}/reference/android-architecture.md, and each touched module's CLAUDE.md before editing.

Create the branch first:  git checkout -b ${A.branchName}   (off current HEAD).

CONVENTIONS (this project uses KOIN, not Hilt):
- safeApiCall for all repository network calls; UDF: UI -> ViewModel -> Repository -> API/Room.
- Koin DI: viewModelOf(::X)/singleOf(::X)/factoryOf(::X) in feature/*/di and core/*/di; koinViewModel() in
  Composables; register new modules in the shared Koin module list. Never hiltViewModel()/@HiltViewModel.
- @Serializable data classes in core/model; Ktor client patterns in core/network.
- KMP: for every touched module, update BOTH src/commonMain AND src/iosMain (and androidMain when relevant).
  expect in commonMain REQUIRES actual in every target. The approved items carry an iosMainCoverage flag —
  honor it.
- Apply the version gate exactly as each item specifies (isCompatibleOrNewer vs supportsFeature+landedDate).

APPROVED ITEMS (implement each; do not add unapproved scope):
${JSON.stringify(A.approvedItems)}

Commit per logical group with conventional-commit subjects (e.g. "feat(chat): render SUMMARY blocks").
Commit locally only.

HARD RULE: do NOT run git push, git push -u, or gh pr create. Ever. The branch stays local.

Report the full list of files created/modified grouped by module.`,
  { label: 'implement', phase: 'Implement' }
)

if (A.bumpVersion) {
  // Ordering-as-code: submodule checkout MUST precede commit-map regen (the map is
  // keyed off the new pin). Two sequential agents make the order structural.
  phase('Version bookkeeping')
  await agent(
    `Version bookkeeping for the LibreChat Mobile ${A.targetMode} sync (base ${A.baseVersion},
target commit ${A.targetCommit}). Repo root is CWD. Do these IN ORDER:
1. Set backendTargetVersion in version.properties to the mode-correct form:
   release -> "${A.baseVersion}"   rc -> "${A.targetTag ? A.targetTag.replace(/^v/, '') : A.baseVersion}"
   partial -> "${A.baseVersion}+dev.${A.sha8}"
2. Advance the submodule:  cd upstream && git checkout ${A.targetCommit}   (detached HEAD).
3. Rewrite UPSTREAM_VERSION (keep its comment block):
     tag=${A.targetMode === 'partial' ? A.anchorTag : (A.targetTag || A.anchorTag)}
     commit=${A.targetCommit}
     date=$(date +%Y-%m-%d)
4. Update README.md compatibility badge + note upper bound (badge link target too):
   release/rc -> the tag; partial -> "${A.anchorTag} +dev" phrasing. Leave the lower bound.
5. Append to DISCOVERY.md: newly-discovered endpoints, removed endpoints, revised response shapes.
6. Update VERSION_GATES.md: a row per gated code path. For supportsFeature gates the row MUST cite the
   landing commit + landedDate (from the approved item) and the rc/final version at which the date can be
   dropped for a plain version gate.
Commit these as a "chore(sync): advance backend target to ..." commit. Do NOT regenerate the commit map yet
(next step). Do NOT push.`,
    { label: 'bookkeeping', phase: 'Version bookkeeping' }
  )
  await agent(
    `Regenerate the baked commit->version table AFTER the submodule checkout (it is keyed off the new pin):
  ./gradlew :core:common:generateBackendCommitMap
Then commit the regenerated core/common/src/commonMain/.../generated/BackendCommitMap.kt as
"chore(sync): regenerate BackendCommitMap at new pin". Verify it now contains the first 12 chars of
${A.targetCommit}. Do NOT push.`,
    { label: 'regen-commitmap', phase: 'Version bookkeeping' }
  )
} else {
  log('Audit mode: pin does not move — skipping version bookkeeping (version.properties / UPSTREAM_VERSION / submodule / commit map unchanged)')
}

phase('Build gates')
let build = await runBuildGates(0)

phase('Consistency asserts')
const consistency = await agent(
  `Run version-consistency checks for the LibreChat Mobile ${A.mode} and report each as a pass/fail boolean.
Repo root is CWD. ${A.bumpVersion
    ? `This was a version-advancing ${A.targetMode} sync — assert:
  - sha8Prefix:   ${A.targetMode === 'partial' ? `version.properties backendTargetVersion ends with "+dev.${A.sha8}" and ${A.sha8} is a prefix of UPSTREAM_VERSION commit=` : 'backendTargetVersion matches the tag form (no +dev)'}
  - headEqualsCommit:  (cd upstream && git rev-parse HEAD) equals UPSTREAM_VERSION commit= (== ${A.targetCommit})
  - mapHasPin:    BackendCommitMap.kt contains the first 12 chars of ${A.targetCommit} (same check release.yml runs)
  - readmeMatches: README badge/note upper bound matches ${A.targetMode === 'partial' ? `"${A.anchorTag} +dev"` : (A.targetTag || A.anchorTag)}
  - gatesDated:   every supportsFeature row in VERSION_GATES.md cites a landedDate <= the synced commit's UTC date`
    : `This was an AUDIT (pin must NOT have moved) — assert the pin is UNCHANGED:
  - pinUnchanged: UPSTREAM_VERSION commit= still equals ${A.targetCommit} AND (cd upstream && git rev-parse HEAD) equals it
  - versionUnchanged: version.properties backendTargetVersion is unchanged from before the audit
  - mapUnchanged: BackendCommitMap.kt was not regenerated (git diff shows no change to it)
  - gatesDated:   any NEW supportsFeature row added by an audit fix cites a landedDate <= the pin's UTC date`}
Return one boolean per named check plus a "failures" string describing any that failed.`,
  { label: 'consistency', phase: 'Consistency asserts', schema: {
    type: 'object',
    properties: {
      ok: { type: 'boolean' },
      failures: { type: 'string' },
    },
    required: ['ok'],
  } }
)

let buildRound = 0
while ((!gatesGreen(build) || !consistency.ok) && buildRound < MAX_BUILD_FIX_ROUNDS) {
  buildRound++
  await agent(
    `Fix these failures on the sync branch, then stop (do not push). Repo root is CWD.
BUILD: ${JSON.stringify(build)}
CONSISTENCY: ${JSON.stringify(consistency)}
For an iOS-link failure with Android passing, audit the iosMain source sets of the affected modules
(missing actual for a commonMain expect is the usual cause). Commit the fixes.`,
    { label: `build-fix:${buildRound}`, phase: 'Build gates' }
  )
  build = await runBuildGates(buildRound)
}

phase('Independent review')
// >=2 mandatory passes. Every agent() is stateless, so independence is structural
// (a reviewer literally cannot have seen the prior pass). Only CONFIRMED issues force
// another round; PLAUSIBLE are surfaced but don't block. Capped to avoid the flip-flop
// loop documented in project memory (the 9-round STT A->B->A RCA).
const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    clean: { type: 'boolean' },
    issues: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          verdict: { type: 'string', enum: ['CONFIRMED', 'PLAUSIBLE'] },
          category: { type: 'string' },
          description: { type: 'string' },
          file: { type: 'string' },
          suggestedFix: { type: 'string' },
        },
        required: ['verdict', 'description'],
      },
    },
  },
  required: ['clean', 'issues'],
}

const reviewPasses = []
let reviewRound = 0
let confirmedOpen = true
while (reviewRound < MAX_REVIEW_ROUNDS && (reviewRound < REVIEW_ROLES.length || confirmedOpen)) {
  const role = REVIEW_ROLES[reviewRound % REVIEW_ROLES.length]
  const r = await agent(
    `You are an INDEPENDENT ${role} for a LibreChat Mobile upstream ${A.mode}. You have NOT seen any prior
review pass — that independence is the point. Repo root is CWD; branch is ${A.branchName}.
Read the approved discovery report at ${A.reportPath} and compare it against the actual changes on the branch
(git diff/log against the branch point).

Hunt for:
- Scope creep beyond approved items.
- Backward-compat regressions on older-server paths (a bare final-version gate that should be rc1/supportsFeature).
- iOS-vs-Android SSE shape divergence; missing iosMain actuals for touched commonMain expects.
- Missing/incorrect version gates around new endpoints (wrong landedDate, wrong first-line).
- Koin DI wiring gaps; missing safeApiCall; DISCOVERY.md not updated for new endpoints.
${A.bumpVersion ? '' : '- AUDIT: any change that moved the pin / version (it must not have).'}

Classify each issue: CONFIRMED (you verified it is real and wrong) vs PLAUSIBLE (suspected, not proven).
Do NOT fix anything. Return clean=true only if there are zero CONFIRMED issues.`,
    { label: `${role}:${reviewRound}`, phase: 'Independent review', schema: REVIEW_SCHEMA }
  )
  reviewPasses.push({ role, round: reviewRound, clean: r.clean, issues: r.issues || [] })
  const confirmed = (r.issues || []).filter((i) => i.verdict === 'CONFIRMED')
  confirmedOpen = confirmed.length > 0
  if (confirmedOpen) {
    log(`${role} pass ${reviewRound}: ${confirmed.length} CONFIRMED issue(s) → dispatching fix`)
    await agent(
      `Fix these CONFIRMED review issues on branch ${A.branchName}, then stop (do not push). Repo root is CWD.
Commit the fixes with a clear subject. CONFIRMED ISSUES:\n${JSON.stringify(confirmed)}`,
      { label: `review-fix:${reviewRound}`, phase: 'Independent review' }
    )
    build = await runBuildGates(`r${reviewRound}`)
  }
  reviewRound++
}

const reviewClean = reviewPasses.length >= REVIEW_ROLES.length && !confirmedOpen

phase('Test plan')
const testPlan = await agent(
  `Author the on-device test plan for this LibreChat Mobile ${A.mode} on branch ${A.branchName}. Repo root is CWD.
Read the approved report at ${A.reportPath} and the branch diff. Produce concrete user-testable behaviors,
split into three lists: android, ios, and compat (older-server / version-gate behavior — e.g. "feature X is
HIDDEN on a v0.8.6 server, visible on the target"). Be specific enough to walk without re-reading the diff.`,
  { label: 'test-plan', phase: 'Test plan', schema: {
    type: 'object',
    properties: {
      android: { type: 'array', items: { type: 'string' } },
      ios: { type: 'array', items: { type: 'string' } },
      compat: { type: 'array', items: { type: 'string' } },
    },
    required: ['android', 'ios', 'compat'],
  } }
)

return {
  branchName: A.branchName,
  mode: A.mode,
  bumpVersion: !!A.bumpVersion,
  buildResults: build,
  buildGreen: gatesGreen(build),
  consistency,
  reviewPasses,
  reviewClean,
  unresolvedConfirmed: confirmedOpen,
  testPlan,
}
