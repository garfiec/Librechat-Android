---
name: audit-i18n
description: >
  Audit localization / i18n coverage across the compose-resources surface (9 modules
  x 9 locales). Finds strings that exist in English but not in some or all languages,
  keys left as untranslated English stubs inside a translated file, stale keys the base
  dropped, and English literals that never reached a strings.xml at all. Use when
  asking "which features shipped English-only?", after landing a feature that added
  user-facing strings, before a release that claims multi-language support, or when a
  translator asks what still needs doing. Audit-only — produces a report, never writes
  translations.
allowed-tools: Bash, Read, Glob, Grep, Write, Workflow, AskUserQuestion
argument-hint: "[exact-only] (optional; default runs the full fan-out including heuristic triage)"
---

# Audit i18n Coverage

Audit the localization surface and report what is missing, in a form the user can act on
and re-run later.

You are the **lead**. You do Phase 0 yourself with direct Bash, then hand the analysis to the
`audit-i18n` workflow, then independently re-assert what it claims. You do not attribute keys to
features, triage candidates, or write the report yourself — the workflow fans that out.

**The deterministic checker is the source of truth.** `scripts/i18n-coverage.py` is the only
thing that produces findings. The workflow explains findings that already exist; it never
discovers them.

The skill is **audit-only**. It ends at findings + a recommended fix order. It never writes a
translation, never edits a `strings.xml`, never adds a Gradle task or CI gate, never commits,
never opens a PR.

## The two layers, and why they are separate

| Layer | Produces | Property |
| --- | --- | --- |
| `scripts/i18n-coverage.py` | *What* the findings are | Exact, byte-deterministic, diffable across months |
| `audit-i18n` workflow | *Why / when / whether it matters* | Judgment, parallelised, not reproducible |

Keep the seam clean. The instant an agent re-derives findings by grepping, the audit stops being
comparable to the last one and the determinism you paid for is gone. Every number in the final
report traces to the JSON; the workflow's contribution is feature names, dates, verdicts and
priority.

## Hard rule — do not re-derive findings by hand

**Never grep for missing strings, diff `strings.xml` files with shell tools, or count keys
yourself.** Every number in your report must come out of the script.

The reason is not politeness, it is that a hand-rolled pass is *not comparable to the next one*.
The script is byte-deterministic — identical input produces identical stdout on every run and
every machine (no timestamps, no absolute paths, no set-iteration order). That is what makes a
report diffable against the report from six weeks ago, and what lets the user prove a fix
actually shrank the debt. An ad-hoc grep produces a number nobody can reproduce, and it
systematically misses the things the script handles carefully: both directions of parity drift
(a module with extras that cancel against its missing keys looks fine to a count comparison),
`values-night` being a theme qualifier and not a locale, and the Android `res/` surface being a
separate thing that must not be folded into the parity math.

You may read source files to *explain* a finding the script already reported — naming the
feature a key cluster belongs to, or triaging a heuristic candidate. You may not use reading to
*discover* findings.

## Phase 0 — Sanity-check the tool

Run this first, always:

```bash
python3 scripts/i18n-coverage.py --summary; echo "exit=$?"
```

Confirm three things before trusting anything downstream:

1. The header line reads `allowlist: config/l10n/i18n-allowlist.txt`. If it reads
   `allowlist: (none)` you are running unsuppressed — see the blind spot below, this failure is
   silent.
2. The module table lists **9 modules** and `loc` is **9** on every row. Fewer means discovery
   broke or a locale was dropped wholesale.
3. The exit code is **below 16**. `16` and above is a hard failure — the script could not find or
   parse the surface. It shares no bits with the finding mask (`1|2|4|8`) so that "the check
   broke" can never be misread as "the code is clean". If you see it, read the failure line, fix
   the invocation, and re-run. Do not report anything from a hard-failed run.

   An **advisory** (bit `8`) is not a failure and must not be treated as one. It means the tool
   measured something it wants a human to see — a locale directory missing wholesale, a new
   `<plurals>`, a module added since `EXPECTED_MODULES` was last updated. Those are findings.
   Carry them into the report; do not abort on them.

Phase 0 is yours and is not delegated: if the tool is broken, fanning out ten agents over its
output just multiplies the wrong answer.

## Phase 1 — Run the workflow

Resolve these yourself first, with Bash. **The date matters**: workflow scripts have no clock
(`Date.now()` throws inside one by design, so runs stay resumable), so the report date must be
computed here and passed in.

```bash
REPO="$(git rev-parse --show-toplevel)"
DATE="$(date -u +%F)"
SKILL_DIR="$REPO/.claude/skills/audit-i18n"

# Never overwrite an existing dated report. Two audits on the same UTC day collide on one
# filename, and the earlier report is the baseline the later one is meant to be diffed
# against — it is uncommitted at that moment, and artifacts/ is gitignored, so an overwrite
# is unrecoverable. Pick the first free suffix instead.
REPORT_PATH="$SKILL_DIR/REPORT-$DATE.md"
n=2
while [ -e "$REPORT_PATH" ]; do
  REPORT_PATH="$SKILL_DIR/REPORT-$DATE.$n.md"
  n=$((n + 1))
done
echo "$REPO" "$DATE" "$REPORT_PATH"
```

Pass `REPORT_PATH` through verbatim — the workflow derives its artifact filenames from it, so
the raw JSON behind an earlier report is preserved too. Then:

```
Workflow({ name: "audit-i18n", args: {
  repoRoot:     "<REPO>",
  skillDir:     "<REPO>/.claude/skills/audit-i18n",
  artifactsDir: "<REPO>/.claude/skills/audit-i18n/artifacts",
  reportPath:   "<REPORT_PATH from the snippet above — do not re-derive it>",
  reportDate:   "<DATE>",
  scope:        "full",     // or "exact-only" to skip heuristic triage
  attributeStale: true,
} })
```

Pass `scope: "exact-only"` when the user wants a fast parity-and-stubs answer, or when the
heuristic candidates were triaged recently and have not moved. It drops the triage agents and
leaves `[H]` reported as raw counts.

What it does: measures (1 agent, writes the JSON), then fans out one attribution agent per module
with drift plus a stub adjudicator plus up to three heuristic triagers — all concurrently — then
synthesizes one report, then reconciles that report against the raw JSON with two independent
agents that did not write it. Typically 10–13 agents.

It returns `{ reportPath, jsonPath, totals, featureClusters, modulesAttributed,
modulesNotAttributed, unreconciledModules, stubVerdictCounts, heuristicSampled, advisories,
reconcileVerdicts, confirmedDiscrepanciesFixed, reconcileFailedLenses,
unconfirmedDiscrepancies, clean, summary }`.

**If it returns `{ halted: true }`, stop.** That means the checker hard-failed (exit ≥ 16) — it
did not run at all. Report the reason to the user and fix the tool before auditing anything. A
report generated over a broken measurement reads as a clean bill of health, which is the worst
possible output. Advisories alone never halt the run; they are carried into the report.

## Phase 2 — Re-assert independently

Do not relay the workflow's self-report unchecked. It already reconciles itself, but it graded its
own homework. Run these yourself:

Use the `jsonPath` the workflow returned — it is keyed to this run's report, not a fixed name.

```bash
# 1. The report's headline total must equal the JSON's — the single most load-bearing number.
python3 -c "import json,sys;d=json.load(open(sys.argv[1]));print('missing',d['parity']['missing_total'],'stale',d['parity']['stale_total'],'struct',d['parity']['structural_total'],'stubs',d['stubs']['counts']['errors'],'heuristic',d['hardcoded']['total'])" <jsonPath>
grep -nE "^\| \*\*total\*\*|debt|missing" <reportPath> | head

# 2. The audit is reproducible: re-running the checker reproduces the same JSON.
python3 scripts/i18n-coverage.py --format json | shasum -a 256
shasum -a 256 <jsonPath>

# 3. Spot-check one feature attribution against git yourself. --reverse|head -1 gives the
#    commit that INTRODUCED the key; `-1` alone gives the last commit that touched it, which
#    is a different (and usually wrong) answer. The name="…" anchor stops a prefix-nested
#    sibling key from matching.
git log --format='%h %ad %s' --date=short --reverse -S'name="<a key named in the report>"' \
  -- <module>/src/commonMain/composeResources/values/strings.xml | head -1
```

Then check the returned fields. **`clean: true` is the only state you may summarize without
caveats.** When it is false, one of these is non-empty, and each means something specific:

| Field | If non-empty |
| --- | --- |
| `modulesNotAttributed` | an attribution cap bit — those modules' findings are in the report but unexplained |
| `unreconciledModules` | a module's feature clusters do not account for all its keys |
| `reconcileFailedLenses` | a reconcile lens returned `FAIL` — the report is not trustworthy as written |
| `unconfirmedDiscrepancies` | a lens suspected a fabricated or dropped finding but did not mechanically reproduce it. **Not auto-fixed.** Verify each by hand against the JSON before you summarize |
| `advisories` | the checker's own findings about the surface — these belong in your summary too |

Say so to the user in your summary. Those are the parts of the surface the audit did **not**
fully cover, and they must not be presented as if they were.

If `confirmedDiscrepanciesFixed` is non-zero, mention it: the report needed correcting against
its own source data, which is worth knowing about the run.

## Invocations

All paths are repo-relative; run from the repo root.

```bash
# Full report, human-readable. The default.
python3 scripts/i18n-coverage.py

# Totals only — good for a before/after comparison or a quick status line.
python3 scripts/i18n-coverage.py --summary

# One detector at a time. Useful because the exit code then isolates that detector.
python3 scripts/i18n-coverage.py --detector parity
python3 scripts/i18n-coverage.py --detector stubs
python3 scripts/i18n-coverage.py --detector hardcoded
python3 scripts/i18n-coverage.py --detector all        # same as omitting it

# Machine-readable. Use this when you need to group, sort, or cross-tabulate findings.
python3 scripts/i18n-coverage.py --json                # shorthand for --format json
python3 scripts/i18n-coverage.py --format json
python3 scripts/i18n-coverage.py --format text         # the default

# Show the raw, unsuppressed finding set — what the allowlist is currently hiding.
python3 scripts/i18n-coverage.py --no-allowlist

# Point at a different allowlist (must resolve INSIDE the repo).
python3 scripts/i18n-coverage.py --allowlist config/l10n/i18n-allowlist.txt

# Two opt-in low-precision stub tiers, both INFO, both off by default.
python3 scripts/i18n-coverage.py --include-latin       # Latin-locale values identical to base
python3 scripts/i18n-coverage.py --near-identical      # case/punctuation-only differences

# Override repo-root detection (normally found by walking up for settings.gradle.kts).
python3 scripts/i18n-coverage.py --repo-root .
```

A useful pattern for grouping missing keys by feature — the highest-value analysis step — is to
pull the distinct key lists out of the JSON rather than the text report:

```bash
python3 scripts/i18n-coverage.py --json > /tmp/i18n.json
python3 -c "
import json
d = json.load(open('/tmp/i18n.json'))
for m in d['parity']['per_module']:
    if m['missing_total']:
        print('===', m['module'], 'base', m['base_keys'], 'rows', m['missing_total'])
        for k in m['distinct_missing_keys']: print('  ', k)
"
```

### Exit codes

A bitmask, so a caller can tell exact findings from heuristic ones:

| Code | Meaning | Trust |
| --- | --- | --- |
| `0` | clean | — |
| `1` | parity findings | exact |
| `2` | stub findings, ERROR tier | exact |
| `4` | hardcoded candidates | heuristic |
| `8` | advisories (stale allowlist directives, module/locale-set drift, parser disagreement with `StringResourceParityTest`'s regex) | exact |
| `16` | **hard failure** — layout unrecognized, 0 modules, a module with 0 base keys, an unreadable/non-UTF-8/unparseable resource file, bad CLI argument, a missing explicitly-passed `--allowlist`, unexpected exception | the run did not happen |

Codes combine: `7` = parity + stubs + hardcoded, which is the current state of `develop`.

**Test hard failure with `exit & ~15` (equivalently `exit >= 16`), never with a narrower mask.**
The hard-failure code shares no bits with the finding mask, and that is the whole point — the
conventional `70` (`0b1000110`) sets both the stub bit and the hardcoded bit, so `exit & 3` would
report a crashed run as "2 — exact stub findings". Anything at or above 16 means the check did
not run; anything below is a finding set.

A caller wanting *proven* defects only would check `exit & 3` **after** ruling out hard failure.
Do not treat that as an invitation to add a gate — that is explicitly out of scope for this
skill.

## Reading the three detectors

The single most important thing you do is keep these three straight in the report. Two are exact
set operations. One is a regex heuristic over a language with no type-level marking of
user-facing strings. Blurring them destroys the report's credibility.

### [P] parity — EXACT. Report as fact.

Set difference between each module's base `values/strings.xml` and each of its `values-<loc>/`
files, computed in **both** directions:

- **missing** — in base, not in the locale. The user sees English in a translated app.
- **stale** — in the locale, not in base. Dead weight; usually a key the base renamed or
  removed, which the locale files were never updated to follow.
- **structural** — an entirely absent locale file (scored as 100% missing, not skipped),
  duplicate keys within one file, unknown locale qualifiers.

Also reported: `uniform`, meaning every locale in the module shares one identical missing set and
one identical stale set. When `uniform` is `yes`, the per-module distinct key list is lossless
and you can quote it directly. When it is `no`, per-locale sets diverge and you must go to the
JSON `parity.findings` rows before making per-locale claims.

Note that `missing_total` is a **row count summed over locales**, not a key count. 504 missing
rows in a 9-locale module is 56 distinct keys. State both in the report; the user sizes work in
keys and sizes debt in rows.

Parity is never allowlist-suppressible. A missing or stale key is not a judgment call.

### [S] stubs — EXACT. Report as fact.

A key that *is* present in a non-Latin-script locale but whose value is byte-identical to the
English base and pure ASCII was never actually translated. The file passes parity and still ships
English.

Two classes are exempted mechanically, with no allowlist entry needed: **shared literals** (no
locale anywhere translated them — `Bearer`, `OAuth`, `JSON`, `MCP`, `SSE`, `Markdown`,
`LibreChat`, `mermaid`, `shadcn/ui`) and **letterless values** (arrows, em dashes, pure format
strings).

What survives is tiered by cross-locale corroboration:

- **ERROR** — at least 4 other locales translated this key. The holdout is a genuine miss.
- **REVIEW** — only 1–3 others did, so English may be the local convention. Report these
  separately and say so.
- **INFO** (`--include-latin`, `--near-identical`) — off by default and near-100% /
  1-in-3 precision respectively. Only surface these if the user asks for exhaustive output, and
  label them INFO.

Also reported: **contiguous runs** of ≥3 adjacent untranslated keys in one file. These are the
cheapest fixes in the whole report — one localized block of a file, usually a whole form that a
translator skipped in one go. Call them out by `file:first_line-last_line`.

### [H] hardcoded — HEURISTIC CANDIDATES. Never report as proven.

Sink-anchored regex matching for English literals in `.kt` that never reached a `strings.xml` —
`Text("…")`, `contentDescription = "…"`, `?: "…"` error fallbacks, `when` branches returning
display strings, and so on, bucketed into rule families.

**Language rules for this section, non-negotiable:**

- Call them **candidates**. Never "missing strings", never "confirmed gaps", never fold their
  count into the translation-debt total.
- Print the script's own precision estimate and its enumerated false-positive class verbatim
  rather than paraphrasing it. The script knows which of its rows are wrong; you don't.
- Say explicitly that each one needs a human to decide whether it is user-facing at all.

Triage priority, best-value first:

1. `content_desc` and `text_call` — unambiguously on screen, and `content_desc` is also an
   accessibility defect. Smallest families, highest hit rate.
2. `ui_param` — labels/descriptions passed to UI builders. Large, but usually dominated by a few
   registry-shaped files where one file is one fix.
3. `when_branch` and `continuation` — display-string mappings and multi-line concatenations.
4. `state_error` and `elvis_error` — error fallbacks. Mixed: real user-facing error copy sits
   next to developer diagnostics that merely leak.
5. `enum_label` and `sink_call` — mostly wire tokens and non-UI helpers. Verify before touching.

Sort candidates by module and by file, not by rule, when recommending work: a single file with 100
rows is one PR, and 100 rows spread over 40 files is not.

## The allowlist

`config/l10n/i18n-allowlist.txt`. Suppresses individual `[S]` and `[H]` findings. It does **not**
touch `[P]`.

Format is TAB-separated, exact string equality — no regex, no globs, no prefix matching. Read the
header comment in the file for the five directive forms (`literal`, `key`, `pair`, `site`,
`file`). It is **shrink-only**: a directive that suppresses nothing is surfaced as a stale
advisory (exit bit `8`) and must be deleted, so debt can only go down.

**Every entry needs a written justification comment above it.** "The check was noisy" is not one.

Legitimate reasons to add an entry:

- The value is a protocol or wire token displayed verbatim (`Basic` as an HTTP auth scheme).
- The value is a proper noun or product name (`Streamable HTTP` as the MCP transport's name).
- The value is an input-format spec the user must literally type (`lowercase-kebab-case`).
- The value is an example inside a placeholder, where translating it would make it *wrong*.
- A `[H]` site is genuinely not user-facing — a wire constant, an internal exception default.

Illegitimate — this is hiding a real gap:

- Other locales translated the same key. If `ar`, `ja` and `zh` rendered it as prose, `ko`
  leaving it English is a miss, not a convention. The script's REVIEW/ERROR tiering exists
  precisely to make this visible; do not suppress across it.
- You could not decide, so you suppressed. Leave it as REVIEW and say it needs a native reader.
- A whole `file` directive used to quiet a noisy module. Prefer `site`.
- Anything to make the exit code smaller.

When the audit surfaces a suppression that looks wrong, report it as a finding against the
allowlist. Do not edit the allowlist inside this skill — proposing an entry is in scope, writing
it is a separate authorized change.

## Report artifact

**The workflow writes this, not you** — its synthesize phase produces
`.claude/skills/audit-i18n/REPORT-<YYYY-MM-DD>.md` and its reconcile phase checks it against the
raw JSON. This section documents the contract that report must satisfy, so you can tell whether
what came back is right; the shell snippets below are what the workflow's attribution agents run.

Never overwrite a prior dated report — the whole point of determinism is that two dated reports
diff cleanly. The raw JSON lands in `artifacts/` (gitignored, regenerable); the dated report is
the durable artifact.

Required sections:

1. **Headline number** — total translation debt as a single figure (missing rows + stale rows +
   ERROR stub rows), so the user can size the work in one glance. Exclude `[H]` candidates from
   it and say that you did.
2. **Per-module × per-locale parity table** — real numbers from the script, base key count
   included so percentages are checkable.
3. **Missing keys grouped by feature.** This is the highest-value section. Key names are
   prefix-clustered by design (`project_*`, `context_usage_*`, `media_*`), so group them and
   **name the feature**. Then date a representative key per cluster:

   ```bash
   git log -S'"context_usage_label"' --format='%h|%ad|%s' --date=short --reverse \
     -- feature/chat/src/commonMain/composeResources/values/strings.xml | head -1
   ```

   and establish when localization itself landed:

   ```bash
   git log --oneline --reverse --diff-filter=A -- '*/values-de/strings.xml'
   ```

   Cite the commit and PR for each cluster. A cluster whose keys postdate the i18n introduction
   is a feature that **shipped English-only** — that is the user's actual question, and counts
   alone do not answer it.
4. **Stub findings** — ERROR and REVIEW separated, per-locale counts, contiguous runs called out
   as the cheap wins.
5. **Hardcoded candidates** — labeled heuristic, with the triage ordering above.
6. **Recommended fix order** — biggest coverage win per unit of effort first, with rough entry
   counts per step so each step is schedulable. Deletions of stale keys and single-file registry
   extractions go early; they are near-zero-risk and shrink the number visibly.

## Known blind spots

State these in the report. A tool whose limits are undocumented gets over-trusted.

- **No placeholder or format-specifier checking.** A locale that writes `%2$s` where base has
  `%1$s`, or drops a `%s` entirely, passes every detector. This is a deliberate scope decision,
  not an oversight, and it is the most likely source of a runtime crash in translated copy.
- **Translation quality is entirely invisible.** Non-ASCII text is accepted as translated. A
  machine-translated or wrong-register string is indistinguishable from a good one. The stub
  detector only catches *English left in place*.
- **The default allowlist can be absent.** An explicitly-passed `--allowlist` that does not
  exist is a hard failure, but if the DEFAULT `config/l10n/i18n-allowlist.txt` is simply
  missing, the run continues unsuppressed with an `ALLOWLIST_ABSENT` advisory. That is a real
  state to notice: totals from such a run are not comparable with a run that had it. Always
  verify the header line reads the path, not `(none)`.
- **`--summary` hides the INFO tiers.** `--summary --include-latin` prints the same bytes as
  `--summary` alone. Use the full text or JSON output when you want those counts.
- **`[H]` scans `.kt` only, and not inside raw strings.** English inside `"""…"""` HTML/JS
  templates is not scanned (there is no sink pattern inside an inline web document), and
  `android:label` in `AndroidManifest.xml` is out of scope.
- **`[H]` recall limits, per the script's own notes:** concatenation fragments beyond a 4-line
  window are reported once at the head line; a `val message = "…"` bound far from its sink is
  missed; a spaceless all-lowercase word is dropped by the prose gate (admitting them measured
  2 true positives against 48 false, so that recall is given up on purpose).
- **The Android `res/` surface is reported but not scored.** `app/src/main/res/values/strings.xml`
  holds the platform strings and has no locale directories. Folding it into parity math would
  fabricate phantom "entire locale missing" failures, so it is listed separately. Its gap is
  real; it is just not part of the compose-resources numbers.
- **No iOS `.strings` / `.stringsdict` / `.xcstrings` exist**, so there is nothing to compare
  against on that side. If any are ever added, this tool will not see them.
- **Nothing checks that a declared key is actually *used*.** An orphaned base key that no
  composable references counts as real work for all 9 locales.

## Relationship to StringResourceParityTest

There is an overlapping JUnit test — `StringResourceParityTest` plus
`config/l10n/strings-parity-baseline.txt` (~1269 frozen entries, shrink-only) — on the **unmerged
local branch `chore/review-loop-protocol`** (commit `7f4f66e8`). Read it with
`git show chore/review-loop-protocol:<path>`.

It is **not on `develop`**, so nothing currently enforces parity in CI. Do not assume its baseline
reflects the present state; the checker deliberately does not read or regenerate it.

Where the two differ:

- The test walks only `shared` + `feature/*`, so **`core/ui` drift is invisible to it.** This
  checker discovers modules by sorted glob and includes `core/ui`.
- The test `continue`s past a missing locale file, so a **wholly absent locale is silently OK**.
  This checker scores an absent locale file as 100% missing and reports it as a structural defect.
- The test only checks key presence. It has no stub detection and no hardcoded-literal detection.
- The checker emits an advisory (exit bit `8`) if its parser disagrees with the test's regex, so
  the two can be reconciled rather than silently diverging.

Treat the checker as the superset. If the test later lands on `develop`, that is a CI gate
decision for the user, not something this skill performs.

## Anti-patterns to avoid

- **Don't grep to find findings.** Re-deriving by hand produces numbers nobody can reproduce and
  misses both-direction drift, the `values-night` trap, and the `res/` separation.
- **Don't merge `[H]` into the debt total.** Heuristic candidates and exact defects are different
  kinds of claim. Keeping one number honest is worth more than a bigger number.
- **Don't report `missing_total` as a key count.** It is rows summed over 9 locales. Divide, and
  show both.
- **Don't skip the feature attribution.** "feature/chat is missing 56 keys" is not actionable.
  "Media gallery (#145), message queue (#167), artifact home-screen shortcuts (#241) and context
  usage (#204) all shipped English-only" is.
- **Don't suppress a REVIEW row to get a cleaner exit code.** REVIEW exists to be escalated to a
  native reader, not resolved by fiat.
- **Don't write translations, edit a `strings.xml`, touch Gradle, or add a CI gate.** All four are
  out of scope. The skill stops at the report.
- **Don't run Gradle or an iOS build.** The deliverable is Python output and Markdown.
- **Don't report anything from a hard-failed run (exit ≥ 16).** It is not a clean bill of
  health; the check did not run. Conversely, don't abort on an advisory (bit `8`) — that is a
  finding, and refusing to report it is its own kind of silence.
- **Don't do the analysis yourself instead of running the workflow.** Attribution across five
  modules is the slow part and it parallelises perfectly. Doing it inline is both slower and
  worse, because nothing then reconciles the report against the JSON.
- **Don't let the workflow's numbers into the summary unchecked.** It reconciles itself, which is
  necessary but not sufficient — it graded its own homework. Phase 2 exists for that reason.
- **Don't hide a partial run.** If `modulesNotAttributed` or `unreconciledModules` came back
  non-empty, the audit covered less than it appears to. Say which parts, plainly.
- **Don't pass a stale `reportDate`.** The workflow cannot read a clock; whatever you pass becomes
  the filename and the in-report date. Compute it fresh with `date -u +%F` each run.
