export const meta = {
  name: 'sync-upstream-discovery',
  description: 'Parallel multi-angle discovery of upstream LibreChat changes + mobile-gap mapping, converged into one findings report (sync or audit lens)',
  phases: [
    { title: 'Multi-angle sweep', detail: 'parallel readers, each a different lens over base..target' },
    { title: 'Converge', detail: 'dedup + cross-reference mobile codebase + tag landing commit/date' },
    { title: 'Completeness critic', detail: 'what modality/claim did we miss?' },
  ],
}

// ---------------------------------------------------------------------------
// WF1 — DISCOVERY.  Invoked by the sync-upstream skill AFTER it has (in the main
// loop, with direct Bash) run prereqs, resolved the target, and detected the mode.
// The script body has NO shell/fs access — every git/grep/read action happens
// INSIDE an agent(). Determinism comes from control flow + schema-forced output.
//
// args = {
//   mode:         'sync' | 'audit'
//   baseCommit:   diff base (sync: current pin; audit: previous pin from UPSTREAM_VERSION history)
//   targetCommit: diff ceiling (sync: new target SHA; audit: current pin)
//   targetMode:   'release' | 'rc' | 'partial'
//   baseVersion:  version the target commit's package.json reports (e.g. "0.8.7")
//   anchorTag:    last v-tag at/before targetCommit (e.g. "v0.8.7")
//   skillDir:     absolute path to .claude/skills/sync-upstream
//   artifactsDir: absolute path to .claude/sync-upstream/artifacts
// }
// ---------------------------------------------------------------------------

const A = args || {}
const RANGE = `${A.baseCommit}..${A.targetCommit}`
const IS_AUDIT = A.mode === 'audit'

// Every agent below starts cold. "Switchboard upstream sync" on its own reads as though upstream
// were also called Switchboard — the old product name happened to carry the backend's identity for
// free, and the rename took that away. State the relationship once, explicitly, per prompt.
const CONTEXT = `CONTEXT: Switchboard is a third-party native mobile client for LibreChat.
"Upstream" always means the official LibreChat server repo (danny-avila/LibreChat), vendored
read-only at ./upstream. Switchboard is the client, never the upstream.`

// The lens flips the whole run — the same range, read with opposite intent.
const LENS = IS_AUDIT
  ? `AUDIT LENS: the mobile pin is ALREADY at ${A.targetCommit}. You are NOT finding new
     work — you are checking whether the upstream changes in ${RANGE} (the last synced delta)
     were FULLY and CORRECTLY absorbed into the mobile client. Report ONLY misses: upstream
     changes with no mobile equivalent, partial ports, wrong field/shape mappings, missing
     version gates, or iosMain parity that was skipped. A change that mobile handled correctly
     is NOT a finding.`
  : `SYNC LENS: report every upstream change in ${RANGE} that the mobile client may need to
     absorb — new/changed/removed endpoints, type & SSE shape drift, new config flags, new UI
     flows, security fixes. This is the superset diff; each finding is tagged with the target
     line it first lands in so the human can filter scope at the interview.`

// Six blind angles. Each reads only its slice, categorizes only its kind of change.
// Blindness is the point (multi-modal sweep): no angle sees the others' output.
const ANGLES = [
  { key: 'routes-api', label: 'Routes / API',
    focus: 'HTTP endpoints added, removed, deprecated, or changed (path, method, request/response contract).',
    paths: 'api/server/routes/ (incl. routes/agents, routes/files, routes/admin), api/server/controllers/, api/server/middleware/' },
  { key: 'types-sse', label: 'Types / schemas / SSE shapes',
    focus: 'Data-type / response-shape drift AND streaming payload changes. SSE shape drift is the top source of silent runtime breakage.',
    paths: 'api/models/, packages/data-schemas/src/, packages/data-provider/src/types/, and SSE/run-event emitters. Cross-check mobile core/model StreamEvent.kt, SseContentEvent.kt, ToolCallRecord.kt, ToolCallResult.kt, ToolAuthStatus.kt.' },
  { key: 'config-flags', label: 'Config / feature flags',
    focus: 'New or changed keys in the /api/config surface that gate UI availability.',
    paths: 'packages/data-provider/src/config.ts, librechat.example.yaml, .env.example. Mobile counterpart: core/model StartupConfig.kt.' },
  { key: 'ui-flows', label: 'UI / user flows',
    focus: 'New components, changed user flows, new screens — mapped to mobile feature modules.',
    paths: 'client/src/components/, client/src/hooks/, client/src/store/, client/src/Providers/, client/src/data-provider/.' },
  { key: 'security-auth', label: 'Security / auth',
    focus: 'CVEs, auth/token-handling changes, permission changes. CANNOT be deferred.',
    paths: 'api/server/middleware/, packages/data-provider/src/permissions.ts, auth controllers/services, token handling.' },
  { key: 'release-narrative', label: 'Release notes / PR narrative',
    focus: 'The candid "what broke" source that diffs hide: release-note bodies, merged-PR titles/bodies, rc-prep PR body, and raw commit subjects.',
    paths: 'gh api releases + gh pr list (merged, base dev) + `git log --oneline` over the range. Flag anything a maintainer described as breaking/removed/migration.' },
]

const RAW_FINDING = {
  type: 'object',
  properties: {
    id: { type: 'string' },
    title: { type: 'string' },
    category: { type: 'string', enum: ['api', 'removed-endpoint', 'type-schema', 'config-flag', 'ui', 'security', 'bugfix', 'infra'] },
    upstreamPaths: { type: 'array', items: { type: 'string' } },
    landingCommit: { type: 'string', description: '12-char upstream commit SHA that introduced this change (best effort from the range)' },
    breaking: { type: 'boolean' },
    evidence: { type: 'string', description: 'commit subject / release-note excerpt / diff summary supporting the finding' },
  },
  required: ['id', 'title', 'category', 'breaking', 'evidence'],
}

phase('Multi-angle sweep')

const angleResults = await parallel(ANGLES.map((angle) => () => agent(
  `${CONTEXT}

You are the "${angle.label}" discovery angle for a Switchboard upstream ${A.mode}.
Repo root is the current working directory. Upstream submodule is at ./upstream (read-only).

${LENS}

YOUR ANGLE — ${angle.label}: ${angle.focus}
Upstream area to read/diff: ${angle.paths}
Reference the full path inventory at ${A.skillDir}/reference/upstream-paths.md but stay in YOUR lane —
other angles cover routes/types/config/ui/security/narrative separately. Do not duplicate them.

Method:
- Diff the range with: cd upstream && git diff ${RANGE} -- <your paths>   (use --stat first, then per-file).
- For the release-narrative angle, use: gh api repos/danny-avila/LibreChat/releases/tags/{tag} --jq .body
  for any tag in range, gh pr list --repo danny-avila/LibreChat --state merged --base dev --limit 200
  --json number,title,mergedAt,url, and cd upstream && git log --oneline ${RANGE}.
- For each real change, capture the introducing commit's 12-char SHA when identifiable
  (git log ${RANGE} -- <path> for the relevant file).
- Persist your raw notes to ${A.artifactsDir}/discovery-angle-${angle.key}.md as you go.

Return ONLY findings in your lane. In audit mode, remember: report misses, not correctly-absorbed changes.`,
  { label: `angle:${angle.key}`, phase: 'Multi-angle sweep', schema: {
    type: 'object',
    properties: { findings: { type: 'array', items: RAW_FINDING } },
    required: ['findings'],
  } }
)))

const rawFindings = (angleResults || []).filter(Boolean).flatMap((r) => r.findings || [])
log(`Swept ${ANGLES.length} angles → ${rawFindings.length} raw findings`)

phase('Converge')

// Machine-COMPLETE converged schema. Landing commit + UTC date per feature is the
// tax that keeps downstream version gates correct — WF2's stateless implementer
// must never re-derive these from prose.
const CONVERGED_FINDING = {
  type: 'object',
  properties: {
    id: { type: 'string' },
    title: { type: 'string' },
    category: { type: 'string', enum: ['api', 'removed-endpoint', 'type-schema', 'config-flag', 'ui', 'security', 'bugfix', 'infra'] },
    severity: { type: 'string', enum: ['breaking', 'additive', 'cosmetic'] },
    upstreamPaths: { type: 'array', items: { type: 'string' } },
    mobilePaths: { type: 'array', items: { type: 'string' }, description: 'mobile files that need to change (or that already handle it)' },
    mobileGapStatus: { type: 'string', enum: ['missing', 'needs-update', 'exists', 'backend-blocked'] },
    iosMainCoverage: { type: 'string', enum: ['common-only', 'needs-iosmain', 'na'], description: 'needs-iosmain = touched module has iosMain sources that must be updated too' },
    landingCommit: { type: 'string', description: '12-char upstream SHA that introduced the change' },
    landedDate: { type: 'string', description: 'UTC committer date YYYY-MM-DD of landingCommit, from: cd upstream && TZ=UTC git log -1 --date=format-local:%Y-%m-%d --format=%cd <sha>' },
    firstTargetLine: { type: 'string', description: 'version the feature first ships under, e.g. "0.8.8-rc1" (even before that tag exists)' },
    gateForm: { type: 'string', description: 'recommended gate: isCompatibleOrNewer(v,"X") or supportsFeature(detected,"X",landedDate=...) or none' },
    proposedApproach: { type: 'string' },
  },
  required: ['id', 'title', 'category', 'severity', 'mobileGapStatus', 'landingCommit', 'landedDate'],
}

const converged = await agent(
  `${CONTEXT}

Converge the raw discovery findings for a Switchboard upstream ${A.mode}
(${A.baseVersion}, targetMode=${A.targetMode}, anchor=${A.anchorTag}). Repo root is CWD.

${LENS}

RAW FINDINGS (from ${ANGLES.length} blind angles — expect duplicates across angles):
${JSON.stringify(rawFindings)}

Do ALL of the following, then return the converged report:
1. Dedup: merge findings that describe the same upstream change (routes & types angles often overlap).
2. Cross-reference each finding against the mobile codebase to set mobileGapStatus + mobilePaths:
   - api:            grep core/network/src/commonMain (*Api.kt) AND core/data/src/commonMain (inlined paths).
   - type-schema:    check core/model/src/commonMain data classes; for SSE, the StreamEvent/SseContentEvent/
                     ToolCall* files. Note added/removed/type-changed fields precisely.
   - config-flag:    check core/model StartupConfig.kt for the key.
   - ui:             check the matching feature/* module (commonMain + platform sources).
3. Read ${A.skillDir}/reference/{api-mapping.md,model-mapping.md,ui-mapping.md} to resolve upstream→mobile,
   and DISCOVERY.md at repo root for already-catalogued endpoints/quirks.
4. Read ${A.skillDir}/reference/upstream-backend-gaps.md. Any finding matching an OPEN entry → mobileGapStatus
   "backend-blocked" (do not build). RE-CHECK each entry: if the backend route has since shipped, say so.
5. iosMainCoverage: for each touched core/* or feature/* module, check whether src/iosMain/ sources exist →
   "needs-iosmain" if they do, else "common-only".
6. Landing commit + date (REQUIRED, machine-complete): for each finding set landingCommit (12-char) and
   landedDate via: cd upstream && TZ=UTC git log -1 --date=format-local:%Y-%m-%d --format=%cd <sha>
   (UTC is mandatory — it must match how BackendCommitMap bakes dates; %cs mis-orders).
7. Version gating: read core/common/src/commonMain/.../BackendVersion.kt and VERSION_GATES.md. For each
   feature needing a gate set firstTargetLine + gateForm:
   - release/rc target → isCompatibleOrNewer(version, "{first line carrying it, usually the rc1}")
   - partial target    → supportsFeature(detected, "{next-rc-line}", landedDate="{the UTC date above}")
8. severity: breaking (changed/removed shapes, removed endpoints, auth/security) / additive / cosmetic.
9. Write the full converged report to ${A.artifactsDir}/discovery-report.md (human-reviewable: grouped by
   severity, each finding with upstream+mobile paths, landing commit/date, gap status, gate form).

Return the structured report.`,
  { label: 'converge', phase: 'Converge', schema: {
    type: 'object',
    properties: {
      findings: { type: 'array', items: CONVERGED_FINDING },
      counts: {
        type: 'object',
        properties: {
          breaking: { type: 'integer' }, additive: { type: 'integer' }, cosmetic: { type: 'integer' },
          missing: { type: 'integer' }, backendBlocked: { type: 'integer' },
        },
      },
      reportPath: { type: 'string' },
    },
    required: ['findings', 'reportPath'],
  } }
)

phase('Completeness critic')

const critic = await agent(
  `${CONTEXT}

You are the completeness critic for a Switchboard upstream ${A.mode}. Repo root is CWD.
The converged discovery report is at ${converged.reportPath}. Read it.

Your ONLY job is to find what the sweep MISSED. Check, concretely:
- Any path group in ${A.skillDir}/reference/upstream-paths.md that no angle actually diffed over ${RANGE}?
  (Verify with: cd upstream && git diff --stat ${RANGE} -- <path> ; a non-empty diff with no matching
  finding is a miss.)
- Any finding missing landingCommit or landedDate (would break version gating downstream)?
- Any claim asserted without diff/commit/release-note evidence?
- Any NEW client-only "vapor" feature (upstream UI with no shipped backend) not yet in
  ${A.skillDir}/reference/upstream-backend-gaps.md?
- ${IS_AUDIT ? 'Audit-specific: any upstream change in range that was silently assumed absorbed without checking the mobile side?' : 'Sync-specific: any removed/renamed endpoint whose mobile call sites were not enumerated?'}

Return addenda findings (same shape as the report's findings) plus a short note per gap on what to verify.
If the report is complete, return an empty findings array and say so. Do NOT fix — just surface.`,
  { label: 'critic', phase: 'Completeness critic', schema: {
    type: 'object',
    properties: {
      addenda: { type: 'array', items: CONVERGED_FINDING },
      notes: { type: 'string' },
      complete: { type: 'boolean' },
    },
    required: ['addenda', 'complete'],
  } }
)

const allFindings = converged.findings.concat(critic.addenda || [])
if ((critic.addenda || []).length) {
  log(`Critic surfaced ${critic.addenda.length} additional finding(s) — merging into report`)
  await agent(
    `Append these critic addenda to the discovery report at ${converged.reportPath}, keeping the same
grouping/format, and mark them "(added by completeness critic)". ADDENDA:\n${JSON.stringify(critic.addenda)}`,
    { label: 'merge-addenda', phase: 'Completeness critic' }
  )
}

return {
  mode: A.mode,
  targetMode: A.targetMode,
  range: RANGE,
  findings: allFindings,
  counts: converged.counts || null,
  criticComplete: critic.complete,
  reportPath: converged.reportPath,
}
