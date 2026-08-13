---
name: release-highlights
description: >
  Add a hand-written Highlights section to a GitHub release whose notes were
  auto-generated, summarizing the release's PRs in user-facing language above
  the generated changelog. Reads every PR in the range, drafts the section in
  the repo's house style, asks about ambiguous wording, and writes only after
  approval. Use when a release has shipped and its notes are still a bare
  What's Changed list, or when backfilling releases that never got Highlights.
allowed-tools: Bash, Read, Write, Edit, AskUserQuestion
argument-hint: "[tag ...] (optional, defaults to the newest release) [--backfill] [--since <tag>]"
---

# Release Highlights

Adds a `# Highlights` section between the badge row and `## What's Changed` on a
published release. Nothing else about the release changes.

## The invariant

**Everything from `## What's Changed` down is generated once, at publish time, and cannot
be rebuilt.** It carries the compare link, the New Contributors block, the build provenance
attestation ID, and the CI run URL. Those come from the publish-time workflow run. If they
are overwritten they are gone.

So: never regenerate a release body. Never call `gh release edit --notes` with prose you
composed. Never let `gh release create --generate-notes` near an existing release. The only
supported write is `scripts/insert_highlights.py`, which fetches the live body, splits it at
the marker, and reassembles it with the tail byte-identical — then re-fetches and proves the
tail did not change, printing a restore command if it did.

If the script refuses, that refusal is the feature. Do not work around it by editing the
body by hand.

## Steps

### 1. Resolve the target

- No argument → the newest release: `gh release list --repo garfiec/Librechat-Mobile --limit 1`
- Explicit tags → exactly those, in the order given.
- `--backfill` → every release with no Highlights section. **Enumerate them and confirm
  before touching any.** There are releases going back to `v0.1.1` that predate both calver
  and the Switchboard rename; they are almost never what the user meant. `--since <tag>`
  sets a floor. Backfill never runs implicitly.

Refuse a tag that already has a Highlights section. Replacing one is a separate, explicit
request, and only then does `--replace` get passed.

### 2. Read the release and every PR in it

```bash
gh release view "$TAG" --repo garfiec/Librechat-Mobile --json body -q .body
```

Extract the PR numbers from the What's Changed list, then read every one of them:

```bash
for p in $PRS; do
  echo "=== #$p"
  gh pr view "$p" --json title,body -q '.title + "\n" + (.body | .[0:800])'
done
```

Read all of them, including the ones that look mechanical — a `chore(` prefix sometimes
hides a user-visible change, and a `feat(` prefix sometimes hides pure plumbing. The title
is a claim about the change, not a description of its effect; the body's Summary/Problem
section is what tells you whether a user would notice.

For each PR decide one thing: **what changes for someone using the app?** If the answer is
"nothing they could observe", it is not a Highlights bullet.

### 3. Ask about anything ambiguous — once, before drafting

Collect every uncertain item across the whole release and ask them in a single round. Do
not draft first and ask afterwards, and do not interrupt per-PR.

What counts as ambiguous is not "I don't understand the change" — read the PR again for
that. It is when the change is understood but its framing is a judgment call the user owns:

- Was this broken in a shipped build, or never working at all? ("banners display again" vs.
  "banners display" — the first claims a regression that may never have existed)
- Is a fix worth naming, or is it noise that belongs in the Misc lines?
- Does a staged feature land in this release or the next one?
- Is an internal-sounding change actually user-visible?

If nothing is ambiguous, ask nothing.

### 4. Draft in the house style

```markdown
# Highlights

* Features
  * <short user-facing phrase>
* Bug Fixes
  * <short user-facing phrase>
* Performance Improvements
* Misc Tech Debt Refactors
* Misc Stability Improvements
```

Rules, in force regardless of what prior releases happen to look like:

- **Categories are fixed and ordered**: Features, Bug Fixes, Performance Improvements, Misc
  Tech Debt Refactors, Misc Stability Improvements. Omit any with nothing to say — a release
  with no features has no Features line. A new top-level category is allowed when a release
  genuinely does not fit, but say so explicitly when presenting the draft.
- **A category with nothing nameable stays a bare bullet.** `* Performance Improvements`
  with no children is correct and common; it signals the work happened without itemizing it.
- **Sub-bullets are user-facing effects**, in the user's vocabulary, sentence case, no
  trailing period, no PR links, no PR numbers, no commit-message prefixes. "Two-factor
  authentication now works end to end", not "fix(auth): make 2FA login work end to end".
- **Collapse staged PRs into one bullet.** A feature delivered across several PRs is one
  thing to a user. Five prefetch PRs (#335, #341, #342, #343, #344) are one line about
  background cache warming; three access-gateway PRs (#294, #298, #312) are one line about
  custom request headers.
- **Never name infrastructure work individually.** Dependabot bumps, CI changes, test
  additions, Claude skills, dead-code removal, comment cleanups, docs — these fold into the
  two Misc lines and are never their own sub-bullet.
- **Lead with the most visible change.** If the release renames the app or changes the
  launcher icon, that is the first bullet.
- Keep it to roughly 3–6 sub-bullets per category. If Bug Fixes runs to ten, the tail of the
  list is Misc Stability Improvements.

### 5. Propose, then write

Present the draft as a fenced markdown block, plus the judgment calls worth flagging.
**Do not write until the user approves.**

On approval:

```bash
python3 .claude/skills/release-highlights/scripts/insert_highlights.py \
  --tag "$TAG" --highlights-file "$HL" --apply
```

Drop `--apply` first to dry-run it. The script backs up the original body, refuses on a
pre-existing Highlights section, refuses on a missing or duplicated marker, refuses on an
empty body, and verifies the tail after writing. Report what it prints — including the
backup path — rather than paraphrasing it as success.

If it exits non-zero after the write, run the restore command it printed immediately and
say what happened.

## Notes

- Repo is `garfiec/Librechat-Mobile`; the local directory is `LibreChat-Android` for legacy
  reasons.
- The app is **Switchboard**; LibreChat is the backend it talks to. Release notes describe
  Switchboard. Do not rename backend references.
- **GitHub stores release bodies with CRLF line endings.** Any pipeline that normalizes them
  rewrites every line below the marker — the content survives, but the tail is no longer the
  bytes that were published. The script reads the body verbatim, matches the existing line
  ending when building the inserted block, and never converts. Do not pre-process a body
  through `tr`, `sed`, or a Python `.replace("\r\n", "\n")` on the way in.
- GitHub appends a trailing newline to a body it stores. The script strips trailing
  whitespace before writing so repeat edits cannot accrete blank lines, and tolerates that
  single newline when verifying. Any other difference in the tail is treated as corruption.
- Reading a body with `-q .body` is fine — that is what step 2 does. What must never happen
  is a **write** round-tripped that way (`gh release view -q .body > f && edit f && gh
  release edit`): that pipeline drops the CRLFs and appends a newline per pass.
- `--backfill` and `--since` are instructions in this file, not flags on the script. Nothing
  enforces them; they hold only as long as they are followed. The script's own guards are
  `--apply`, `--replace`, and the refusals listed above.
