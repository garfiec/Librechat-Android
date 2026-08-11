#!/usr/bin/env python3
"""Drift check for upstream constants this client mirrors by hand.

Some upstream values are never served over the API, so the client hardcodes a copy.
These copies fail SILENTLY -- when upstream edits one, nothing here breaks, decodes
wrong, or logs; the app just goes on making a slightly worse decision. What is mirrored,
and what breaks when each goes stale, is in scripts/mirrors.json. Adding a mirror to the
codebase means adding it there in the same PR.

This diffs each watched upstream region between two revisions of the `upstream/`
submodule and names the Kotlin file that has to be reconciled. It answers "did this move",
not "are the two sides equal" -- deliberately. Upstream's tables reference enum symbols
rather than literals, mix regexes with strings, and in at least one case carry meaning in
a COMMENTED-OUT line, so comparing values needs a TypeScript interpreter and would still
miss things.

Run it during a /sync-upstream pass, which is the only time these can drift: the
constants change when upstream moves, and upstream only moves at a sync.

    scripts/check-mirrors.py                       # synced commit -> submodule HEAD
    scripts/check-mirrors.py --to v0.8.8           # synced commit -> a target tag
    scripts/check-mirrors.py --from v0.8.6 --to v0.8.7
    scripts/check-mirrors.py --diff                # show what actually changed
    scripts/check-mirrors.py --list                # print the registry, check nothing

Exit status is 0 only when every watched region is byte-identical across the two
revisions. Any change, and any region that could not be located, exits 1 -- a missing
anchor means upstream renamed or removed the symbol, which is the loudest signal there
is, not a reason to skip.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
REGISTRY = REPO_ROOT / "scripts" / "mirrors.json"
UPSTREAM_VERSION = REPO_ROOT / "UPSTREAM_VERSION"
DEFAULT_UPSTREAM = REPO_ROOT / "upstream"

OK, CHANGED, MISSING = "OK", "CHANGED", "MISSING"


class Fatal(Exception):
    """Something that must stop the run rather than degrade into a false pass."""


@dataclass
class Result:
    entry_id: str
    status: str
    detail: str
    kotlin_file: str
    kotlin_symbol: str
    before: str = ""
    after: str = ""


# --------------------------------------------------------------------------- upstream

def git(upstream: Path, *args: str) -> str:
    proc = subprocess.run(
        ["git", "-C", str(upstream), *args],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise Fatal(f"git {' '.join(args)} failed:\n{proc.stderr.strip()}")
    return proc.stdout


def require_upstream(upstream: Path) -> None:
    """Refuse to run against an absent submodule.

    `upstream/` is empty in every git worktree and in CI (actions/checkout does not
    fetch submodules). Skipping there would make this report success on zero coverage,
    which is worse than not running it at all.
    """
    if not upstream.exists() or not any(upstream.iterdir()):
        raise Fatal(
            f"upstream submodule is empty at {upstream}\n"
            "  It is not populated in git worktrees or in CI. Run this from the main\n"
            "  checkout, or: git submodule update --init upstream"
        )
    if not (upstream / ".git").exists():
        raise Fatal(f"{upstream} exists but is not a git checkout")


def synced_commit() -> str:
    if not UPSTREAM_VERSION.exists():
        raise Fatal(f"{UPSTREAM_VERSION} not found")
    for line in UPSTREAM_VERSION.read_text().splitlines():
        line = line.strip()
        if line.startswith("commit="):
            return line.split("=", 1)[1].strip()
    raise Fatal(f"no commit= line in {UPSTREAM_VERSION}")


def resolve(upstream: Path, rev: str) -> str:
    try:
        return git(upstream, "rev-parse", "--verify", f"{rev}^{{commit}}").strip()
    except Fatal:
        raise Fatal(
            f"revision {rev!r} not found in the upstream submodule.\n"
            "  Try: git -C upstream fetch --tags origin"
        )


def file_at(upstream: Path, rev: str, path: str) -> str | None:
    proc = subprocess.run(
        ["git", "-C", str(upstream), "show", f"{rev}:{path}"],
        capture_output=True, text=True,
    )
    return proc.stdout if proc.returncode == 0 else None


# -------------------------------------------------------------------------- extraction

# Anything that can introduce a bracket we must NOT count: comments, the three string
# forms, and regex literals. Regexes are only recognised in value position, so a division
# `a / b` is not mistaken for one -- which matters because upstream's MIME tables are
# arrays of regex literals containing character classes like [\w.-].
_SKIPPABLE = re.compile(
    r"""
      //[^\n]*                     # line comment
    | /\*.*?\*/                    # block comment
    | "(?:\\.|[^"\\])*"            # double-quoted
    | '(?:\\.|[^'\\])*'            # single-quoted
    | `(?:\\.|[^`\\])*`            # template literal
    """,
    re.VERBOSE | re.DOTALL,
)

_OPEN, _CLOSE = "([{", ")]}"


def _mask(text: str) -> str:
    """Blank out regions whose brackets must not be counted, preserving offsets.

    Replacing with spaces rather than deleting keeps every index in the masked copy
    aligned with the original, so a slice found here can be taken from the real text.
    """
    out = list(text)

    for m in _SKIPPABLE.finditer(text):
        for i in range(m.start(), m.end()):
            if out[i] != "\n":
                out[i] = " "

    # Regex literals, scanned left to right on the already-masked text so we never start
    # one inside a comment or string.
    masked = "".join(out)
    i, n = 0, len(masked)
    while i < n:
        if masked[i] != "/":
            i += 1
            continue
        # Slice first, THEN rstrip: rstripping the whole file before slicing shortens it by
        # any trailing whitespace and reads a character that many positions early.
        prev = masked[:i].rstrip()[-1:]
        if prev and (prev.isalnum() or prev in ")]_$"):
            i += 1                       # division, not a regex
            continue
        j, closed = i + 1, False
        while j < n:
            c = masked[j]
            if c == "\\":
                j += 2
                continue
            if c == "\n":
                break
            if c == "/":
                closed = True
                break
            j += 1
        if not closed:
            i += 1
            continue
        for k in range(i, j + 1):
            if out[k] != "\n":
                out[k] = " "
        i = j + 1

    return "".join(out)


def _first_assignment(masked: str, start: int) -> int:
    """Index just past the declaration's `=`, or `start` if it has none.

    Stops at the first `{` so an `enum X {` / `interface X {` body -- which has no
    assignment -- is counted from the declaration itself rather than from some `=`
    appearing later in the file.
    """
    i, n = start, len(masked)
    while i < n:
        c = masked[i]
        if c in "{;":
            return start
        if c == "=":
            nxt = masked[i + 1] if i + 1 < n else ""
            prv = masked[i - 1] if i > start else ""
            if nxt not in "=>" and prv not in "=!<>+-*/&|^":
                return i + 1
        i += 1
    return start


def extract_block(text: str, symbol: str) -> str | None:
    """The full declaration of `symbol`, from its keyword to its closing delimiter.

    Handles `export const X = [...]`, `= new Set([...])`, `= {...}` and `export enum X {}`.
    Returns None when the symbol is not declared here -- upstream renamed or moved it,
    which the caller reports as MISSING rather than passing over.
    """
    anchor = re.compile(
        rf"^[ \t]*(?:export\s+)?(?:const|let|var|enum|type|interface)\s+{re.escape(symbol)}\b",
        re.MULTILINE,
    )
    m = anchor.search(text)
    if not m:
        return None

    masked = _mask(text)
    start = m.start()

    # Begin counting at the initializer, not the declaration keyword: a type annotation
    # can carry its own brackets (`const FEEDBACK_TAGS: TFeedbackTag[] = [...]`), and
    # counting those would close the block at the end of the signature line -- yielding a
    # one-line "block" that then compares equal across every revision.
    scan = _first_assignment(masked, m.start())
    depth, i, opened = 0, scan, False

    while i < len(masked):
        c = masked[i]
        if c in _OPEN:
            depth += 1
            opened = True
        elif c in _CLOSE:
            depth -= 1
            if opened and depth == 0:
                end = text.find("\n", i)
                return text[start: end if end != -1 else len(text)]
            if depth < 0:
                return None
        elif c == ";" and not opened:
            return text[start:i + 1]        # e.g. `type X = string;`
        i += 1

    return None


# ----------------------------------------------------------------------------- checking

def check(entry: dict, upstream: Path, rev_from: str, rev_to: str) -> Result:
    up, kt = entry["upstream"], entry["kotlin"]
    path, mode = up["file"], up.get("mode", "block")
    symbol = up.get("symbol")
    ident = f"{path}:{symbol}" if symbol else path

    def res(status: str, detail: str, before: str = "", after: str = "") -> Result:
        return Result(entry["id"], status, detail, kt["file"], kt.get("symbol", ""), before, after)

    src_from = file_at(upstream, rev_from, path)
    src_to = file_at(upstream, rev_to, path)

    if src_from is None and src_to is None:
        return res(MISSING, f"{path} absent at both revisions")
    if src_from is None:
        # Carry the new content through so --diff has something to show: a mirror that
        # appears for the first time is exactly the one whose body needs reading.
        new = src_to if mode == "file" else (extract_block(src_to, symbol) or src_to)
        return res(CHANGED, f"{path} is NEW at the target revision", "", new)
    if src_to is None:
        return res(MISSING, f"{path} was DELETED upstream")

    if mode == "file":
        if src_from == src_to:
            return res(OK, ident)
        return res(CHANGED, ident, src_from, src_to)

    block_from = extract_block(src_from, symbol)
    block_to = extract_block(src_to, symbol)

    if block_from is None and block_to is None:
        return res(MISSING, f"{symbol} not found in {path} at either revision")
    if block_from is None:
        return res(CHANGED, f"{symbol} is NEW in {path}", "", block_to or "")
    if block_to is None:
        return res(MISSING, f"{symbol} was REMOVED or RENAMED in {path}")

    if block_from == block_to:
        return res(OK, ident)
    return res(CHANGED, ident, block_from, block_to)


# -------------------------------------------------------------------------------- output

def print_list(entries: list[dict]) -> None:
    print(f"{len(entries)} mirrored constants\n")
    for e in entries:
        up, kt = e["upstream"], e["kotlin"]
        sym = f":{up['symbol']}" if up.get("symbol") else "  (whole file)"
        print(f"  {e['id']}")
        print(f"    upstream  {up['file']}{sym}")
        print(f"    kotlin    {kt['file']}")
        if kt.get("symbol"):
            print(f"              {kt['symbol']}")
        print(f"    why       {e['why']}")
        print(f"    if stale  {e['failure_mode']}")
        print()


def print_diff(r: Result) -> None:
    import difflib
    lines = difflib.unified_diff(
        r.before.splitlines(), r.after.splitlines(),
        fromfile="before", tofile="after", lineterm="", n=2,
    )
    for line in lines:
        print(f"      {line}")


def main() -> int:
    p = argparse.ArgumentParser(
        description="Diff hand-mirrored upstream constants between two upstream revisions.",
    )
    p.add_argument("--from", dest="rev_from", metavar="REV",
                   help="baseline revision (default: commit= from UPSTREAM_VERSION)")
    p.add_argument("--to", dest="rev_to", default="HEAD", metavar="REV",
                   help="target revision (default: the submodule's current HEAD)")
    p.add_argument("--upstream", type=Path, default=DEFAULT_UPSTREAM,
                   help="path to the upstream checkout (default: ./upstream)")
    p.add_argument("--diff", action="store_true", help="show what changed in each region")
    p.add_argument("--list", action="store_true", help="print the registry and exit")
    args = p.parse_args()

    try:
        registry = json.loads(REGISTRY.read_text())
    except FileNotFoundError:
        print(f"error: registry not found at {REGISTRY}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as e:
        print(f"error: {REGISTRY} is not valid JSON: {e}", file=sys.stderr)
        return 2

    entries = registry["entries"]

    if args.list:
        print_list(entries)
        return 0

    try:
        require_upstream(args.upstream)
        rev_from = resolve(args.upstream, args.rev_from or synced_commit())
        rev_to = resolve(args.upstream, args.rev_to)
    except Fatal as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    if rev_from == rev_to:
        print(f"Both revisions are {rev_from[:12]} — nothing to compare.")
        print("Point --to at the sync target, or fetch the submodule first.")
        return 0

    print(f"upstream {rev_from[:12]} -> {rev_to[:12]}   ({len(entries)} mirrors)\n")

    results = [check(e, args.upstream, rev_from, rev_to) for e in entries]
    changed = [r for r in results if r.status == CHANGED]
    missing = [r for r in results if r.status == MISSING]

    for r in results:
        mark = {OK: "  ok  ", CHANGED: " DRIFT", MISSING: "  ??  "}[r.status]
        print(f"{mark}  {r.entry_id}")
        if r.status == OK:
            continue
        print(f"          {r.detail}")
        print(f"          reconcile: {r.kotlin_file}")
        if r.kotlin_symbol:
            print(f"                     {r.kotlin_symbol}")
        # `or` not `and`: a NEW file or symbol has an empty `before`, and that is precisely
        # the case whose content the reader needs to see.
        if args.diff and (r.before or r.after):
            print_diff(r)
        print()

    print()
    if not changed and not missing:
        print(f"All {len(results)} mirrors unchanged across this range.")
        return 0

    if changed:
        print(f"{len(changed)} mirror(s) drifted — reconcile the Kotlin side before syncing.")
    if missing:
        print(f"{len(missing)} mirror(s) could not be located — upstream renamed, moved or")
        print("  removed the symbol. The registry entry needs updating either way.")
    if not args.diff:
        print("\nRe-run with --diff to see the changes.")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
