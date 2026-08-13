#!/usr/bin/env python3
"""Insert a Highlights section into a GitHub release body without touching anything else.

Everything below `## What's Changed` is generated at publish time and cannot be rebuilt if
overwritten, so this script never renders a body: it fetches the live one, splits it at the
marker, and writes back `head + highlights + <original tail, byte for byte>`, then re-fetches
and proves the tail is unchanged. See ../SKILL.md.

Usage:
    insert_highlights.py --tag v2026.08.1 --highlights-file hl.md            # dry run
    insert_highlights.py --tag v2026.08.1 --highlights-file hl.md --apply    # write
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

MARKER = "## What's Changed"
DEFAULT_REPO = "garfiec/Librechat-Mobile"


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def gh(args: list[str]) -> str:
    proc = subprocess.run(["gh", *args], capture_output=True, text=True)
    if proc.returncode != 0:
        die(f"gh {' '.join(args)} failed:\n{proc.stderr.strip()}")
    return proc.stdout


def fetch_body(tag: str, repo: str) -> str:
    """Fetch the release body exactly. Parsed from JSON, not jq -r, which appends a newline."""
    raw = gh(["release", "view", tag, "--repo", repo, "--json", "body"])
    try:
        body = json.loads(raw)["body"]
    except (json.JSONDecodeError, KeyError) as exc:
        die(f"could not parse the release body for {tag}: {exc}")
    if not body or not body.strip():
        die(f"{tag} has an empty body — refusing to write over it")
    # Returned verbatim. GitHub stores release bodies with CRLF line endings; normalizing
    # them here would silently rewrite every line below the marker.
    return body


def split_body(body: str, tag: str) -> tuple[str, str]:
    """Return (head, tail) where tail starts at the marker and is never modified."""
    count = body.count(MARKER)
    if count == 0:
        die(f"{tag} has no '{MARKER}' section — this skill only augments generated notes")
    if count > 1:
        die(f"{tag} contains {count} '{MARKER}' markers — too ambiguous to edit safely")
    index = body.index(MARKER)
    return body[:index], body[index:]


def highlights_line(head: str) -> int | None:
    """Index of the Highlights heading. Scans only above the marker, so a PR title
    mentioning highlights can't false-positive."""
    for i, line in enumerate(head.splitlines()):
        if line.lstrip().startswith("#") and line.strip().lstrip("#").strip().lower() == "highlights":
            return i
    return None


def has_highlights(head: str) -> bool:
    return highlights_line(head) is not None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--highlights-file", required=True, type=Path)
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--backup-dir", type=Path, default=Path(tempfile.gettempdir()))
    ap.add_argument("--apply", action="store_true", help="write; otherwise dry-run")
    ap.add_argument("--replace", action="store_true",
                    help="allow replacing an existing Highlights section (must be explicit)")
    args = ap.parse_args()

    highlights = args.highlights_file.read_text().strip()
    if not highlights.startswith("#"):
        die("the highlights file must start with a '# Highlights' heading")

    body = fetch_body(args.tag, args.repo)
    head, tail = split_body(body, args.tag)

    cut = highlights_line(head)
    if cut is not None:
        if not args.replace:
            die(f"{args.tag} already has a Highlights section. "
                f"Pass --replace only if the user explicitly asked to overwrite it.")
        # Cut at the Highlights heading itself, never at whatever heading comes first —
        # anything above it (badges, an intro note) belongs to the author, not to us.
        head = "".join(head.splitlines(keepends=True)[:cut])

    # Match the body's existing line endings so the inserted block doesn't mix styles.
    nl = "\r\n" if "\r\n" in body else "\n"
    block = nl.join(highlights.splitlines())

    # Trailing whitespace is stripped so repeated edits cannot accrete blank lines.
    new_body = f"{head.rstrip()}{nl}{nl}{block}{nl}{nl}{tail}".rstrip() + nl

    if not new_body.rstrip().endswith(tail.rstrip()):
        die("internal check failed: the reconstructed body does not end with the original tail")

    if not args.apply:
        print(f"DRY RUN — {args.tag} (nothing written, no backup taken)")
        print(f"  original body : {len(body)} chars")
        print(f"  new body      : {len(new_body)} chars")
        print(f"  tail          : {len(tail.rstrip())} chars, carried over verbatim "
              f"({tail.count(chr(13))} CR, {tail.count(chr(10))} LF)")
        print(f"  line endings  : {'CRLF' if nl == chr(13) + chr(10) else 'LF'} (preserved)")
        print("\n--- head after edit ---")
        print(new_body[:new_body.index(MARKER)])
        return

    backup = args.backup_dir / f"release-body-{args.tag}.bak.md"
    backup.write_text(body)

    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
        fh.write(new_body)
        notes_path = fh.name
    gh(["release", "edit", args.tag, "--repo", args.repo, "--notes-file", notes_path])

    def fatal(what: str) -> None:
        print(f"\nFATAL: {what} on {args.tag}.", file=sys.stderr)
        print(f"Restore it now with:\n"
              f"  gh release edit {args.tag} --repo {args.repo} --notes-file {backup}",
              file=sys.stderr)
        sys.exit(2)

    # GitHub normalizes trailing whitespace on its own; any other tail difference is corruption.
    live = fetch_body(args.tag, args.repo)
    live_head, live_tail = split_body(live, args.tag)
    if live_tail.rstrip() != tail.rstrip():
        fatal("the section below the marker CHANGED")
    if not live.startswith(head.rstrip()):
        fatal("the content above the Highlights section CHANGED")
    # Without this the tool cannot tell a successful write from one that silently did nothing.
    # Compared line-ending-insensitively so a normalization upstream can't fake a corruption
    # report — a spurious FATAL here would send the user to restore over a good write.
    if highlights.replace("\r\n", "\n") not in live_head.replace("\r\n", "\n"):
        fatal("the Highlights section is missing or incomplete after the write")

    print(f"OK  {args.tag}")
    print(f"    tail unchanged, trailing whitespace excepted "
          f"({len(tail.rstrip())} chars below the marker)")
    print(f"    Highlights section confirmed present")
    print(f"    backup: {backup}")
    print(f"    https://github.com/{args.repo}/releases/tag/{args.tag}")


if __name__ == "__main__":
    main()
