export const meta = {
  name: 'audit-i18n',
  description: 'Fan out over the deterministic i18n-coverage findings: attribute missing keys to the features that shipped them, adjudicate untranslated stubs, triage heuristic literal candidates, then reconcile the report against the raw JSON',
  phases: [
    { title: 'Measure', detail: 'run scripts/i18n-coverage.py, persist JSON, derive fan-out shape' },
    { title: 'Attribute', detail: 'one agent per module with drift: git archaeology -> feature clusters' },
    { title: 'Adjudicate', detail: 'stub verdicts + heuristic candidate triage, in parallel with attribution' },
    { title: 'Synthesize', detail: 'single report from the structured returns' },
    { title: 'Reconcile', detail: 'adversarial: report claims vs raw JSON, and coverage of what was dropped' },
  ],
}

// ---------------------------------------------------------------------------
// Invoked by the audit-i18n skill. The skill (main loop, direct Bash) resolves
// paths and the report date FIRST, because this script body has no shell, no fs
// and no clock — Date.now()/new Date() throw here by design, so `reportDate`
// MUST arrive via args.
//
// THE LOAD-BEARING INVARIANT, repeated into every prompt below:
//   scripts/i18n-coverage.py is the sole authority on WHAT the findings are.
//   Agents supply WHY / WHEN / WHETHER-IT-MATTERS. No agent may add a finding
//   the JSON does not contain, or drop one it does. That separation is what
//   keeps a re-run diffable — the moment an agent re-derives findings by
//   grepping, the output stops being reproducible and the whole point is lost.
//
// args = {
//   repoRoot:         absolute path to the checkout (worktree-safe)
//   skillDir:         absolute path to .claude/skills/audit-i18n
//   artifactsDir:     absolute path for the raw JSON + intermediate artifacts
//   reportPath:       absolute path the final report is written to
//   reportDate:       YYYY-MM-DD, computed by the skill (no clock in here)
//   scope:            'full' | 'exact-only'   (exact-only skips heuristic triage)
//   attributeStale:   boolean, archaeology on stale keys too (default true)
// }
// ---------------------------------------------------------------------------

// The harness has been observed delivering `args` JSON-stringified; parse defensively
// or every field silently reads as undefined and the run proceeds against nonsense.
let A = args
if (typeof A === 'string') {
  try { A = JSON.parse(A) } catch (e) { A = {} }
}
A = A || {}

const REPO = A.repoRoot
const SKILL_DIR = A.skillDir
const ARTIFACTS = A.artifactsDir || `${SKILL_DIR}/artifacts`
const REPORT_DATE = A.reportDate
// The skill is responsible for handing in a path that does not already exist (it has a
// shell and a clock; this script has neither). Two audits on the same UTC day would
// otherwise resolve to one filename and the second would destroy the first — and since
// the report is uncommitted at that moment and artifacts/ is gitignored, there is no copy
// to recover. The synthesize agent refuses to overwrite as a second line of defence.
const REPORT_PATH = A.reportPath || `${SKILL_DIR}/REPORT-${REPORT_DATE}.md`
// Keyed to the report it belongs to, so the raw JSON behind an earlier report is not
// clobbered either — diffing two reports is useless if both point at one JSON.
const RUN_STEM = REPORT_PATH.split('/').pop().replace(/^REPORT-/, '').replace(/\.md$/, '')
const JSON_PATH = `${ARTIFACTS}/i18n-coverage-${RUN_STEM}.json`
const TEXT_PATH = `${ARTIFACTS}/i18n-coverage-${RUN_STEM}.txt`
const EXACT_ONLY = A.scope === 'exact-only'
const ATTRIBUTE_STALE = A.attributeStale !== false

if (!REPO || !SKILL_DIR || !REPORT_DATE) {
  return {
    error: 'audit-i18n workflow requires args.repoRoot, args.skillDir and args.reportDate. ' +
      `Received: repoRoot=${REPO} skillDir=${SKILL_DIR} reportDate=${REPORT_DATE}. ` +
      'The skill computes these in the main loop — Date.now() is unavailable inside a workflow.',
  }
}

// Caps exist so a pathological corpus cannot fan out unboundedly. When one bites we
// log it — a silently truncated audit reads as "covered everything" when it did not.
const MAX_ATTRIBUTION_AGENTS = 6
const MAX_HEURISTIC_AGENTS = 3

const GROUND_RULES = `
REPO ROOT (a git worktree — never cd to the original checkout): ${REPO}
RAW FINDINGS JSON (already written, read it, do not regenerate it): ${JSON_PATH}

THE INVARIANT — read this twice:
  \`scripts/i18n-coverage.py\` is the SOLE authority on WHAT the findings are. It is exact and
  deterministic, and a caller re-runs it to diff audits over time. Your job is to explain
  findings that already exist: WHY a gap happened, WHEN it landed, WHETHER it matters.
  - You may NOT introduce a finding absent from the JSON.
  - You may NOT drop, merge away, or silently re-scope a finding present in the JSON.
  - You may NOT re-derive findings by grepping strings.xml yourself. If your reading of the
    repo disagrees with the JSON, that is a BUG REPORT about the script — say so explicitly
    and loudly in your return, with the exact command and the discrepancy. Do not quietly
    "correct" the numbers in your output; a silent correction destroys reproducibility.
  Git history, source files and PR metadata ARE yours to read freely — that is the whole point
  of this phase. The restriction is on inventing or discarding FINDINGS, not on investigation.

NON-GOALS: do not write translations, do not edit any strings.xml, do not edit the checker or
the allowlist, do not commit, do not push, do not open a PR. Do not run Gradle or any iOS build.
`

// ---------------------------------------------------------------------------
// Phase 1 — Measure.  Barrier: the entire fan-out shape is derived from this.
// ---------------------------------------------------------------------------

phase('Measure')

const SHAPE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['exit_code', 'json_written', 'totals', 'modules_with_drift', 'stub_locales', 'hardcoded_modules', 'advisories', 'trustworthy'],
  properties: {
    exit_code: { type: 'integer' },
    json_written: { type: 'boolean' },
    trustworthy: {
      type: 'boolean',
      description:
        'false ONLY if the checker hard-failed (exit & ~15, i.e. >= 16) or exit was 0 over an ' +
        'implausibly empty corpus. Advisories do NOT make a run untrustworthy — they are ' +
        'findings about the surface, and the report must carry them.',
    },
    advisories: { type: 'array', items: { type: 'string' } },
    totals: {
      type: 'object',
      additionalProperties: false,
      required: ['parity_missing', 'parity_stale', 'parity_structural', 'stub_errors', 'stub_review', 'hardcoded_candidates', 'base_keys'],
      properties: {
        parity_missing: { type: 'integer' },
        parity_stale: { type: 'integer' },
        parity_structural: { type: 'integer' },
        stub_errors: { type: 'integer' },
        stub_review: { type: 'integer' },
        hardcoded_candidates: { type: 'integer' },
        base_keys: { type: 'integer' },
      },
    },
    modules_with_drift: {
      type: 'array',
      description: 'Every module whose missing_total or stale_total is non-zero, from parity.per_module',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['module', 'missing_total', 'stale_total', 'distinct_missing_keys', 'distinct_stale_keys', 'uniform'],
        properties: {
          module: { type: 'string' },
          missing_total: { type: 'integer' },
          stale_total: { type: 'integer' },
          distinct_missing_keys: { type: 'integer' },
          distinct_stale_keys: { type: 'integer' },
          uniform: { type: 'boolean' },
        },
      },
    },
    stub_locales: { type: 'array', items: { type: 'string' } },
    hardcoded_modules: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['module', 'count'],
        properties: { module: { type: 'string' }, count: { type: 'integer' } },
      },
    },
  },
}

const shape = await agent(`${GROUND_RULES}

PHASE: measure. You produce the ground truth every later agent consumes.

1. mkdir -p ${ARTIFACTS}
2. Run, from ${REPO}:
     python3 scripts/i18n-coverage.py --format json > ${JSON_PATH}
   Capture the exit code. Then ALSO run the human-readable form and keep it for the report:
     python3 scripts/i18n-coverage.py > ${TEXT_PATH}
   (Both write files; neither mutates the repo.)
3. INTERPRET THE EXIT CODE BEFORE ANYTHING ELSE. The low nibble is the finding mask
   (1=parity 2=stubs 4=hardcoded 8=advisories); anything at or above 16 is a hard failure.
   - exit & ~15 (>= 16) = HARD FAILURE. The check did not run. Set trustworthy=false,
     explain, and stop — do not report zeros as a clean result.
   - Any other non-zero = findings exist. Normal. This INCLUDES the advisory bit.
   - 0 = genuinely no findings. Verify that is plausible (base_keys should be >1000); if the
     corpus looks empty, set trustworthy=false.
4. Read the JSON's \`advisories\`, \`stale_allowlist_directives\`, and each detector's own
   \`advisories\` array. Report every one VERBATIM in the advisories field.
   Advisories do NOT set trustworthy=false. The checker already draws the fatal /
   non-fatal line itself, deterministically: what it cannot measure it hard-fails on,
   what it CAN measure but wants a human to see it reports as an advisory. Treating
   advisories as fatal makes a dropped locale, a new <plurals>, or a newly added module —
   all ordinary, all things the audit exists to surface — produce no report at all.
   Do not re-litigate that boundary with your own judgement; carry the advisories into
   the report as a prominent section instead.
5. Populate the schema from the JSON. Read the real fields, do not estimate:
   parity.missing_total / stale_total / structural_total / per_module[]
   stubs.counts.errors / .review / stubs.counts.errors_per_locale
   hardcoded.total / hardcoded.by_module
   base_keys = sum of parity.per_module[].base_keys
   modules_with_drift comes from per_module entries with missing_total>0 OR stale_total>0.
   stub_locales = the keys of stubs.counts.errors_per_locale.

Return the shape. Do not analyze anything yet — later agents do that.`, {
  label: 'measure',
  phase: 'Measure',
  schema: SHAPE_SCHEMA,
})

if (!shape) {
  return { error: 'Measure phase returned nothing — the checker could not be run. No report written.' }
}

// Halt ONLY on a genuine hard failure. The exit code is checked here as well as in the
// prompt, so an agent that misreads its own instruction cannot start a fan-out over a
// run that never happened — and, symmetrically, cannot halt a run that merely had
// advisories. `exit & ~15` is the checker's own documented hard-failure test.
const HARD_FAILED = typeof shape.exit_code === 'number' && (shape.exit_code & ~15) !== 0

if (HARD_FAILED || !shape.trustworthy) {
  log(`HALTED: the checker did not produce a usable measurement (exit ${shape.exit_code}).`)
  return {
    halted: true,
    reason: HARD_FAILED
      ? `Checker hard-failed (exit ${shape.exit_code}, outside the finding mask) — it did not run. ` +
        'Refusing to emit a report that would read as a clean bill of health.'
      : 'Measure agent judged the corpus implausible (e.g. exit 0 over an empty corpus).',
    exit_code: shape.exit_code,
    advisories: shape.advisories || [],
    jsonPath: JSON_PATH,
  }
}

const ADVISORIES = shape.advisories || []
if (ADVISORIES.length) {
  log(`${ADVISORIES.length} advisory/advisories — carried into the report, NOT fatal.`)
}

const T = shape.totals
log(`Measured: ${T.parity_missing} missing + ${T.parity_stale} stale over ${T.base_keys} base keys; ${T.stub_errors} stub errors; ${T.hardcoded_candidates} heuristic candidates`)

// ---------------------------------------------------------------------------
// Fan-out shape, derived deterministically from the JSON.
// ---------------------------------------------------------------------------

const driftModules = (shape.modules_with_drift || [])
  .slice()
  .sort((a, b) => (b.missing_total + b.stale_total) - (a.missing_total + a.stale_total) || a.module.localeCompare(b.module))

const attributionTargets = driftModules.slice(0, MAX_ATTRIBUTION_AGENTS)
const attributionDropped = driftModules.slice(MAX_ATTRIBUTION_AGENTS)
if (attributionDropped.length) {
  log(`CAP HIT: ${attributionDropped.length} module(s) will not get a dedicated attribution agent: ${attributionDropped.map((m) => m.module).join(', ')}. Their findings still appear in the report, unattributed.`)
}

// Greedy balance of heuristic candidates across a small fixed pool, largest first,
// so one huge module does not serialize the phase behind eight trivial ones.
const hModules = (shape.hardcoded_modules || []).slice().sort((a, b) => b.count - a.count || a.module.localeCompare(b.module))
const hBuckets = []
if (!EXACT_ONLY && hModules.length) {
  const n = Math.min(MAX_HEURISTIC_AGENTS, hModules.length)
  for (let i = 0; i < n; i++) hBuckets.push({ modules: [], count: 0 })
  for (const m of hModules) {
    let lightest = 0
    for (let i = 1; i < hBuckets.length; i++) {
      if (hBuckets[i].count < hBuckets[lightest].count) lightest = i
    }
    hBuckets[lightest].modules.push(m.module)
    hBuckets[lightest].count += m.count
  }
}

log(`Fan-out: ${attributionTargets.length} attribution agent(s), ${T.stub_errors > 0 || T.stub_review > 0 ? 1 : 0} stub adjudicator, ${hBuckets.length} heuristic triager(s)${EXACT_ONLY ? ' (exact-only scope — heuristic triage skipped)' : ''}`)

// ---------------------------------------------------------------------------
// Phases 2+3 — Attribute and Adjudicate.  Independent tracks, run concurrently.
// ---------------------------------------------------------------------------

phase('Attribute')

const ATTRIBUTION_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['module', 'clusters', 'unattributed_keys', 'key_count_reconciles'],
  properties: {
    module: { type: 'string' },
    key_count_reconciles: {
      type: 'boolean',
      description: 'true iff (sum of cluster key counts + unattributed) equals distinct_missing_keys + distinct_stale_keys from the JSON',
    },
    reconciliation_note: { type: 'string' },
    clusters: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['feature', 'keys', 'landed_commit', 'landed_date', 'kind'],
        properties: {
          feature: { type: 'string', description: 'Human feature name, e.g. "Chat Projects"' },
          pr: { type: 'string', description: 'PR number like #204, or "" if none found' },
          landed_commit: { type: 'string' },
          landed_date: { type: 'string', description: 'YYYY-MM-DD, UTC, author date of the commit that added the key to the base file' },
          kind: { type: 'string', enum: ['missing', 'stale', 'mixed'] },
          keys: { type: 'array', items: { type: 'string' } },
          evidence: { type: 'string', description: 'The exact git command whose output backs the attribution' },
        },
      },
    },
    unattributed_keys: {
      type: 'array',
      description: 'Keys you could not tie to a commit. Listing them honestly beats a tidy table.',
      items: { type: 'string' },
    },
  },
}

const attributionTrack = () => parallel(attributionTargets.map((m) => () => agent(`${GROUND_RULES}

PHASE: attribute — module \`${m.module}\`.

The JSON says this module has ${m.distinct_missing_keys} distinct missing key(s) across
${m.missing_total} rows, and ${m.distinct_stale_keys} distinct stale key(s) across ${m.stale_total} rows.
${m.uniform ? 'Drift is UNIFORM across locales, so the distinct key lists are lossless.' : 'Drift is NON-UNIFORM across locales — different locales are missing different keys. Read parity.per_module[].missing_by_locale for the per-locale breakdown and preserve that distinction; do not flatten it into a union.'}

Read the distinct key lists for \`${m.module}\` out of ${JSON_PATH}. For EACH key, date it:

  git log --format='%h|%ad|%s' --date=short --reverse -S'name="<key>"' \\
      -- ${m.module}/src/commonMain/composeResources/values/strings.xml | head -1

Three details are load-bearing, do not "simplify" them:
  --reverse | head -1  takes the OLDEST matching commit, i.e. the one that INTRODUCED the
      key. \`-1\` alone takes the newest, which is the most recent time anyone touched the
      key — usually a later edit, and for a re-touched key that names the wrong PR and a
      landing date that can be months off.
  -S'name="<key>"'  the pickaxe string includes the XML attribute syntax, so it matches the
      key exactly. A bare -S'<key>' also matches every prefix-nested sibling
      (\`skill_field_name\` matches commits that only added \`skill_field_name_hint\`).
  --date=short  NOT --date=short-local: a local-timezone date makes the report
      machine-dependent, and this report is meant to diff cleanly across runs.

Batch this — one shell loop over the key list, not one tool call per key.

Then CLUSTER the keys into the user-facing features that shipped them. Cluster by the commit
that added them first, then by key-name prefix within a commit (a single PR often ships two
unrelated groups, e.g. a feature plus its settings page). Name each cluster the way a human
would describe the feature in a changelog — "Chat Projects", "context-usage bar" — not by key
prefix. Pull the PR number from the commit subject when it has one.

${ATTRIBUTE_STALE ? `STALE keys get archaeology too, and a different question: find the commit that REMOVED
the key from the base file (\`git log --format='%h|%ad|%s' --date=short -S'name="<key>"' --\` on
the base file; here you DO want the newest commit, since removal is the last event — confirm
with \`git show\` that it is a deletion and not an edit). A stale key means the base dropped a string but the locale files kept
it — the interesting fact is which change orphaned it, because that is a cleanup others may
have missed too. Mark those clusters kind="stale".` : 'Skip stale-key archaeology this run.'}

RECONCILE BEFORE RETURNING: the sum of your clusters' key counts plus unattributed_keys MUST
equal the JSON's distinct missing + distinct stale count for this module. Set
key_count_reconciles accordingly and explain any shortfall in reconciliation_note. A key you
cannot date goes in unattributed_keys — never drop it to make the arithmetic work.`, {
  label: `attribute:${m.module}`,
  phase: 'Attribute',
  schema: ATTRIBUTION_SCHEMA,
})))

const STUB_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['verdicts', 'locale_summary', 'recommended_allowlist_additions'],
  properties: {
    locale_summary: { type: 'string' },
    verdicts: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['module', 'key', 'locale', 'verdict'],
        properties: {
          module: { type: 'string' },
          key: { type: 'string' },
          locale: { type: 'string' },
          verdict: { type: 'string', enum: ['GENUINE_MISS', 'ACCEPTABLE_AS_IS', 'UNCERTAIN'] },
          reason: { type: 'string' },
        },
      },
    },
    recommended_allowlist_additions: {
      type: 'array',
      description: 'Only for ACCEPTABLE_AS_IS rows. Each needs a written justification — the allowlist is not a mute button.',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['entry', 'justification'],
        properties: { entry: { type: 'string' }, justification: { type: 'string' } },
      },
    },
  },
}

const stubTrack = () => agent(`${GROUND_RULES}

PHASE: adjudicate stubs.

Read \`stubs.errors\` (${T.stub_errors} rows) and \`stubs.review\` (${T.stub_review} rows) from ${JSON_PATH}.
Each row is a key whose value in a non-Latin-script locale (${(shape.stub_locales || []).join(', ')}) is
byte-identical to English and pure ASCII — the detector's evidence that it was never translated.
Each row carries \`translated_by\`: the other locales that DID translate that key, which is the
corroboration the ERROR tier is built on.

For every row, decide:
  GENUINE_MISS      — should be translated; the term has a normal rendering in that language.
  ACCEPTABLE_AS_IS  — correctly left in English: a brand, a protocol token, a header name, a
                      unit, or a term that is genuinely used untranslated in that locale's
                      technical register.
  UNCERTAIN         — you cannot tell without a native speaker. Use this rather than guessing;
                      an honest UNCERTAIN is more useful than a confident wrong verdict.

Weigh \`translated_by\` heavily: if 8 locales translated a key and one did not, that one is
almost certainly a miss. If a term is English in every locale that has it, the detector already
auto-exempted it (see stubs.auto_exempt) and it will not be in your list.

Also read \`stubs.runs\` — contiguous untranslated regions by source line. A run of adjacent
untranslated keys is evidence a translator skipped a block wholesale rather than making N
independent judgments, which usually promotes every row in that run to GENUINE_MISS. Say so
where it applies.

Recommend allowlist entries ONLY for ACCEPTABLE_AS_IS, each with a real justification. Do not
recommend allowlisting anything merely to reduce the count.`, {
  label: 'adjudicate:stubs',
  phase: 'Adjudicate',
  schema: STUB_SCHEMA,
})

const HEURISTIC_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['bucket', 'sampled', 'true_positives', 'false_positives', 'precision_estimate', 'priority_examples'],
  properties: {
    bucket: { type: 'string' },
    sampled: { type: 'integer' },
    true_positives: { type: 'integer' },
    false_positives: { type: 'integer' },
    precision_estimate: { type: 'string' },
    fp_classes: {
      type: 'array',
      description: 'Recurring false-positive shapes worth a rule tightening or an allowlist entry',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['shape', 'example', 'count'],
        properties: { shape: { type: 'string' }, example: { type: 'string' }, count: { type: 'integer' } },
      },
    },
    priority_examples: {
      type: 'array',
      description: 'Highest-value TRUE positives: user-visible English that clearly should be a resource',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['file', 'line', 'literal', 'why_it_matters'],
        properties: {
          file: { type: 'string' },
          line: { type: 'integer' },
          literal: { type: 'string' },
          why_it_matters: { type: 'string' },
        },
      },
    },
  },
}

const heuristicTracks = hBuckets.map((b, i) => () => agent(`${GROUND_RULES}

PHASE: triage heuristic candidates — bucket ${i + 1} of ${hBuckets.length}: ${b.modules.join(', ')} (~${b.count} candidates).

These come from \`hardcoded.candidates\` in ${JSON_PATH}, filtered to your modules. Unlike parity
and stubs, this detector is HEURISTIC: sink-anchored regex over a language with no type-level
marking of user-facing text. It is expected to be wrong sometimes. Your job is to measure HOW
wrong, and to surface the ones that genuinely matter.

Each candidate has: file, line, rule (the sink family that matched), literal, source (the matched
source line), module.

1. SAMPLE at least 25 candidates across your bucket, spread over every \`rule\` family present —
   not 25 from the easiest family. If your bucket has fewer than 25, take all of them.
2. OPEN each at its file:line and read enough surrounding code to judge it. Do not classify from
   the \`source\` snippet alone; the snippet is one line and the answer is usually in the caller.
3. Classify TRUE POSITIVE (user-visible English that should be a string resource) vs FALSE
   POSITIVE (log/Timber message, exception text never surfaced, test fixture, wire/JSON key, DI
   or Koin name, DataStore key, testTag, MIME type, HTTP header, regex, SQL, @Preview sample data).
   The decisive question is: can a user of the shipped app read this text on screen?
4. Group recurring false positives into CLASSES with counts — a class is actionable (tighten a
   rule, add an allowlist entry), a one-off is not.
5. Return the highest-value true positives with file:line and why each matters. Prioritize text
   on a primary user path (empty states, error banners, buttons, content descriptions) over
   text a user reaches only in an edge case.

Report your real precision honestly. If this bucket's precision is poor, say so plainly and name
the rule that is misfiring — rubber-stamping a noisy detector is how it stops being read.`, {
  label: `triage:h${i + 1}`,
  phase: 'Adjudicate',
  schema: HEURISTIC_SCHEMA,
}))

const [attributions, stubVerdicts, heuristicResults] = await parallel([
  attributionTrack,
  () => ((T.stub_errors > 0 || T.stub_review > 0) ? stubTrack() : Promise.resolve(null)),
  () => (heuristicTracks.length ? parallel(heuristicTracks) : Promise.resolve([])),
])

const attrOk = (attributions || []).filter(Boolean)
const hOk = (heuristicResults || []).filter(Boolean)
const unreconciled = attrOk.filter((a) => !a.key_count_reconciles)
if (unreconciled.length) {
  log(`WARNING: ${unreconciled.length} module(s) failed key-count reconciliation: ${unreconciled.map((a) => a.module).join(', ')} — the report must flag this, not paper over it`)
}
log(`Attributed ${attrOk.reduce((n, a) => n + (a.clusters || []).length, 0)} feature cluster(s) across ${attrOk.length} module(s); triaged ${hOk.reduce((n, h) => n + (h.sampled || 0), 0)} heuristic candidate(s)`)

// ---------------------------------------------------------------------------
// Phase 4 — Synthesize.
// ---------------------------------------------------------------------------

phase('Synthesize')

const report = await agent(`${GROUND_RULES}

PHASE: synthesize. Write the audit report to ${REPORT_PATH} (date ${REPORT_DATE}).

BEFORE YOU WRITE ANYTHING: check whether ${REPORT_PATH} already exists
(\`test -e ${REPORT_PATH} && echo EXISTS\`). If it does, STOP — do not write, do not
overwrite, do not append. Return an explanation that the path collided. Dated reports are
the durable artifact this whole tool exists to diff across time; silently replacing one
destroys the baseline a reader was about to compare against, and it is not in git yet.
The caller is expected to hand in a fresh path, so a collision means something is wrong
upstream and is worth surfacing rather than papering over.

${ADVISORIES.length ? `ADVISORIES from the checker — these are FINDINGS, not failures. Give them their own
section in the report, verbatim, and say what each one means for the numbers:
${ADVISORIES.map((a) => `  - ${a}`).join('\n')}` : 'The checker raised no advisories.'}

You have the raw JSON at ${JSON_PATH}, the human-readable run at ${TEXT_PATH},
and these structured returns from the fan-out:

ATTRIBUTION (per module, feature clusters with landing commits):
${JSON.stringify(attrOk)}

STUB VERDICTS:
${JSON.stringify(stubVerdicts)}

HEURISTIC TRIAGE:
${JSON.stringify(hOk)}

${unreconciled.length ? `RECONCILIATION FAILURES — these modules' clusters do not account for every key.
State this in the report explicitly, per module, rather than presenting a complete-looking table:
${unreconciled.map((a) => `  ${a.module}: ${a.reconciliation_note || 'no note given'}`).join('\n')}` : 'All modules reconciled their key counts.'}

Structure the report:

1. **Headline** — total translation debt as ONE number a human can act on, coverage %, and the
   single most important sentence: which shipped features are English-only.
2. **Parity, exact** — per-module x per-locale table with real numbers from the JSON. Include a
   short note that count-only comparison understates drift wherever stale keys cancel missing
   ones, and show the modules where that actually happens, with the specific stale keys.
3. **The English-only features** — the centerpiece. A table ordered newest first: feature, PR,
   landing date, distinct keys, rows (keys x locales), modules. This is the direct answer to
   "which features shipped English-only?", so it leads on feature names, not key prefixes.
4. **Stale keys** — with the commit that orphaned each.
5. **Untranslated stubs** — per-locale counts, the GENUINE_MISS rows, and any recommended
   allowlist additions WITH their justifications.
6. **Unextracted literals — heuristic** — measured precision, false-positive classes, and a
   prioritized list of true positives. Label this section unmistakably as candidates requiring
   triage. A reader skimming must never mistake it for the exact sections.
7. **Recommended fix order** — biggest coverage win per unit of effort first, with entry counts
   so the user can size each step.
8. **Limits** — what this audit cannot see. Carry the checker's documented blind spots forward
   (no placeholder/format-specifier checking; translation quality invisible; \`[H]\` scans .kt
   only) plus anything the fan-out could not attribute.

Rules for the writing:
- Every number traces to the JSON. If a fan-out agent's count disagrees with the JSON, the JSON
  wins and you FLAG the disagreement in the report rather than silently picking one.
- Mark exact sections and heuristic sections distinctly and consistently.
- No invented findings. No dropped findings.
- Keep the reproduction line at the top: the exact command that regenerates the JSON, so the next
  run diffs cleanly against this one.

Return the absolute path you wrote plus a 3-sentence summary of what it says.`, {
  label: 'synthesize',
  phase: 'Synthesize',
})

// ---------------------------------------------------------------------------
// Phase 5 — Reconcile.  The guard that makes the fan-out safe: a workflow that
// interprets findings can hallucinate them, so the report is checked back
// against the raw JSON by agents that did not write it.
// ---------------------------------------------------------------------------

phase('Reconcile')

const RECONCILE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['lens', 'verdict', 'discrepancies'],
  properties: {
    lens: { type: 'string' },
    verdict: { type: 'string', enum: ['PASS', 'FAIL'] },
    discrepancies: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['severity', 'claim_in_report', 'what_json_says', 'confirmed'],
        properties: {
          severity: { type: 'string', enum: ['HIGH', 'MEDIUM', 'LOW'] },
          claim_in_report: { type: 'string' },
          what_json_says: { type: 'string' },
          confirmed: { type: 'boolean', description: 'true only if you actually diffed the two and saw it' },
          location: { type: 'string' },
        },
      },
    },
  },
}

const checks = (await parallel([
  () => agent(`${GROUND_RULES}

PHASE: reconcile — LENS: numeric fidelity. You did not write the report; do not defend it.

Compare ${REPORT_PATH} against ${JSON_PATH} mechanically. Prefer computing over reading: use
python3/jq to pull the JSON's numbers, then check each against the report's tables.

1. Every per-module missing/stale figure in the report matches parity.per_module.
2. The headline total matches parity.missing_total (and the row/key distinction is not conflated
   — "keys" and "rows = keys x locales" are different numbers and the report must not mix them).
3. Stub counts match stubs.counts, per locale.
4. The heuristic total matches hardcoded.total, and the report does not present sampled-subset
   precision as if the whole set were triaged.
5. The feature-attribution table's key counts sum to the distinct missing keys the JSON reports.
   A shortfall is only acceptable if the report explicitly flags it as unattributed.
6. INVENTED FINDINGS: take a sample of at least 12 specific keys named in the report and confirm
   each exists in the JSON with the same module and locale(s). Any key in the report but not the
   JSON is a HIGH severity fabrication.
7. DROPPED FINDINGS: confirm no module with drift in the JSON is missing from the report.

Report only discrepancies you actually reproduced, with the command you ran.`, { label: 'reconcile:numeric', phase: 'Reconcile', schema: RECONCILE_SCHEMA }),

  () => agent(`${GROUND_RULES}

PHASE: reconcile — LENS: honesty and usability. You did not write the report; be skeptical.

1. EXACT vs HEURISTIC separation: can a reader skimming the report mistake a heuristic candidate
   for a proven gap? Check headings, the summary, and any combined totals. Any place the
   ${T.hardcoded_candidates} heuristic candidates are added into the exact debt figure
   (${T.parity_missing} missing + ${T.stub_errors} stub rows) is a HIGH severity finding.
2. Attribution soundness: spot-check at least 5 feature clusters. Re-run the pickaxe yourself,
   and use the INTRODUCING commit, not the newest one:
     git log --format='%h|%ad|%s' --date=short --reverse -S'name="<key>"' \\
         -- <module>/src/commonMain/composeResources/values/strings.xml | head -1
   \`--reverse | head -1\` and the \`name="…"\` anchor are both required — with \`-1\` and a bare
   key you would re-derive the same wrong answer the attribution agent might have made and
   "confirm" it. A confidently wrong attribution is worse than an honest "unattributed".
3. Silent caps: if the workflow dropped modules from attribution or sampled only a subset of
   heuristic candidates, does the report SAY so? An audit that quietly covers 80% while reading
   as complete is the failure mode here.
4. Are the documented blind spots carried forward — especially that placeholder/format-specifier
   mismatches are not checked at all?
5. Is the recommended fix order actually justified by the numbers, or is it asserted?
6. Does the report state how to reproduce it exactly?

Report only what you verified.`, { label: 'reconcile:honesty', phase: 'Reconcile', schema: RECONCILE_SCHEMA }),
])).filter(Boolean)

const allDiscrepancies = checks.flatMap((c) => (c.discrepancies || []).map((d) => ({ ...d, lens: c.lens })))
const confirmedDiscrepancies = allDiscrepancies.filter((d) => d.confirmed)

// A discrepancy the lens reasoned about but did not mechanically re-diff still gets
// `confirmed:false` under the schema's own wording. Such rows are NOT auto-fixed (they are
// unverified, and acting on them could corrupt a correct report) but they MUST stay
// surfaced, and any FAIL verdict must block the run from reading as clean — drop them and a
// lens can return FAIL over a HIGH-severity "this key is not in the JSON" while the workflow
// still reports a tidy `confirmedDiscrepanciesFixed: 0` and the fabrication ships.
const unconfirmedDiscrepancies = allDiscrepancies.filter((d) => !d.confirmed)
const failedLenses = checks.filter((c) => c.verdict === 'FAIL').map((c) => c.lens)

if (unconfirmedDiscrepancies.length) {
  log(`${unconfirmedDiscrepancies.length} UNCONFIRMED discrepancy(ies) reported but not mechanically reproduced — surfaced for the lead, not auto-fixed.`)
}
if (failedLenses.length) {
  log(`RECONCILE FAILED on lens(es): ${failedLenses.join(', ')} — the report is NOT clean.`)
}

if (confirmedDiscrepancies.length) {
  log(`Reconcile found ${confirmedDiscrepancies.length} confirmed discrepancy(ies) — correcting the report`)
  await agent(`${GROUND_RULES}

The report at ${REPORT_PATH} was checked against the raw JSON by two independent agents. Fix every
confirmed discrepancy below, editing the report in place.

${confirmedDiscrepancies.map((d, i) => `${i + 1}. [${d.severity}] (${d.lens}) ${d.location || ''}
   report claims: ${d.claim_in_report}
   JSON says:     ${d.what_json_says}`).join('\n\n')}

The JSON is authoritative for every number. Where a discrepancy is a missing caveat rather than a
wrong figure, add the caveat rather than deleting the content. Do not introduce new findings while
fixing. When done, state what you changed, line by line.`, { label: 'correct-report', phase: 'Reconcile' })
}

return {
  reportPath: REPORT_PATH,
  jsonPath: JSON_PATH,
  reportDate: REPORT_DATE,
  totals: T,
  featureClusters: attrOk.reduce((n, a) => n + (a.clusters || []).length, 0),
  modulesAttributed: attrOk.map((a) => a.module),
  modulesNotAttributed: attributionDropped.map((m) => m.module),
  unreconciledModules: unreconciled.map((a) => a.module),
  stubVerdictCounts: stubVerdicts
    ? {
        genuine: (stubVerdicts.verdicts || []).filter((v) => v.verdict === 'GENUINE_MISS').length,
        acceptable: (stubVerdicts.verdicts || []).filter((v) => v.verdict === 'ACCEPTABLE_AS_IS').length,
        uncertain: (stubVerdicts.verdicts || []).filter((v) => v.verdict === 'UNCERTAIN').length,
      }
    : null,
  heuristicSampled: hOk.reduce((n, h) => n + (h.sampled || 0), 0),
  advisories: ADVISORIES,
  reconcileVerdicts: checks.map((c) => ({ lens: c.lens, verdict: c.verdict, discrepancies: (c.discrepancies || []).length })),
  confirmedDiscrepanciesFixed: confirmedDiscrepancies.length,
  // Every one of these must be empty/false for the audit to be reportable as clean. The
  // lead re-asserts them in Phase 2 rather than trusting this summary.
  reconcileFailedLenses: failedLenses,
  unconfirmedDiscrepancies: unconfirmedDiscrepancies.map((d) => ({
    lens: d.lens,
    severity: d.severity,
    location: d.location,
    claim_in_report: d.claim_in_report,
    what_json_says: d.what_json_says,
    note: 'NOT auto-fixed — the lens did not mechanically reproduce it. Verify by hand.',
  })),
  clean:
    failedLenses.length === 0 &&
    unconfirmedDiscrepancies.length === 0 &&
    attributionDropped.length === 0 &&
    unreconciled.length === 0,
  summary: report,
}
