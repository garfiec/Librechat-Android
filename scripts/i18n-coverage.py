#!/usr/bin/env python3
"""Deterministic i18n coverage checker for Switchboard.

Three independent detectors over the compose-resources localization surface:

  P  parity     EXACT. Per (module, locale): keys present in the English base but
                absent from the locale ("missing"), and keys present in the locale
                but absent from the base ("stale"). Plus structural defects: an
                entirely absent locale strings.xml, duplicate keys inside one file,
                unknown locale qualifiers, and <plurals>/<string-array> advisories.

  S  stubs      EXACT. Keys whose locale value was never actually translated: the
                locale value is byte-identical to the English base, the locale uses a
                non-Latin script, and the value is pure ASCII. Shared literals
                (brand names / acronyms nobody translated anywhere) and letterless
                values (symbols, pure format strings) are auto-exempted mechanically.
                Remaining judgment calls are suppressed via the allowlist.

  H  hardcoded  HEURISTIC — always reported as CANDIDATES, never as defects. English
                literals in Kotlin/Compose that never reached any strings.xml and are
                therefore structurally invisible to parity checking. Sink-anchored
                regexes plus a prose gate; ~98% precision, requires human triage.

Determinism is the point: this script exists so a skill can re-run it and diff the
output. Every collection is sorted, discovery is sorted-glob based, and the output
contains no timestamps, hostnames, absolute paths, or hash-order-dependent ordering.
Identical input produces byte-identical stdout on every run and every machine.
The single exception is opt-in and stderr-only: I18N_COVERAGE_TRACEBACK=1 prints a
Python traceback (with machine-absolute paths) after an unexpected hard failure.

Exit codes
----------
  0   no findings in the selected detectors
  bitmask, confined to the low nibble (FINDING_MASK = 15):
    1   parity findings          (exact)
    2   stub findings, ERROR tier (exact)
    4   hardcoded candidates     (heuristic — triage, not a defect count)
    8   advisories: allowlist staleness, module/locale-set drift, parser disagreement
  16  hard failure: repo layout not recognized, no modules discovered, a module with
      0 base keys, an unreadable/unparseable resource file, a bad CLI argument, a
      missing explicitly-passed --allowlist, or any unexpected exception. Never
      silently reports "0 findings".

The hard-failure code shares NO bits with the finding mask (16 & 15 == 0), so
`exit & FINDING_MASK` is 0 on every failure path and a caller that branches on the
bitmask can never read a crash as real findings. This is asserted at import time.
Any future finding bit must stay inside FINDING_MASK; anything above it means "the
check did not run". stdout/stderr are reconfigured to UTF-8 at startup so an ambient
non-UTF-8 codec cannot truncate the report mid-write.

Scope
-----
Read-only. This script never writes to any strings.xml, never runs Gradle, and never
compares format specifiers / placeholder counts (deliberately out of scope).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import traceback
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

# --------------------------------------------------------------------------------------
# Exit-code bits
# --------------------------------------------------------------------------------------

EXIT_PARITY = 1
EXIT_STUBS = 2
EXIT_HARDCODED = 4
EXIT_ADVISORY = 8

# Every finding bit lives inside this mask; the hard-failure code lives outside it.
# `exit & FINDING_MASK` is therefore the finding set, on every path, including crashes.
FINDING_MASK = EXIT_PARITY | EXIT_STUBS | EXIT_HARDCODED | EXIT_ADVISORY

# Must not be 70 (EX_SOFTWARE): 0b1000110 sets the stub bit (70 & 2) and the hardcoded bit
# (70 & 4), so a bitmask-testing caller reads a crash as exact stub findings. 16 is the
# first bit above the finding mask.
EXIT_HARD_FAILURE = 16

if EXIT_HARD_FAILURE & FINDING_MASK:  # pragma: no cover — structural invariant
    raise SystemExit(
        "i18n-coverage is misconfigured: EXIT_HARD_FAILURE shares bits with FINDING_MASK, "
        "so callers would read a hard failure as findings."
    )

# --------------------------------------------------------------------------------------
# Tripwire expectations.
#
# These are deliberate, not defensive. Pure discovery hides a module whose base file was
# accidentally deleted; pure hardcoding hides a newly added module. Asserting the
# discovered set against the known set catches both, and the resulting advisory is the
# signal to update this constant on purpose.
# --------------------------------------------------------------------------------------

EXPECTED_MODULES = (
    "core/ui",
    "feature/agents",
    "feature/auth",
    "feature/chat",
    "feature/conversations",
    "feature/files",
    "feature/settings",
    "feature/skills",
    "shared",
)

EXPECTED_LOCALES = ("ar", "de", "es", "fr", "ja", "ko", "pt", "ru", "zh")

# Parity findings that are defects of SHAPE rather than of a single key. They survive
# --summary (lowest volume, highest signal) and are counted in structural_total. Named
# once so a new kind cannot be added to the finding stream while silently missing from
# the totals, the renderer, or the --summary filter.
STRUCTURAL_KINDS = ("ABSENT_LOCALE_DIR", "ABSENT_LOCALE_FILE", "DUPLICATE_KEY")

# Corpus floor. Discovery tripwires catch a module disappearing; this catches the corpus
# EVAPORATING while the file tree still looks right (a bad merge, a truncating write, a
# resource-format migration that keeps the filenames). Without it, 90 well-formed but
# empty strings.xml files report "0 findings / ADVISORIES: none" — a clean bill of health
# over nothing. Shrink-only, same discipline as the allowlist: a deliberate key deletion
# means lowering this constant on purpose.
EXPECTED_MIN_BASE_KEYS = 1213

# Script class per locale. Hardcoded on purpose: the stub detector's precision rests
# entirely on this partition, and deriving it from Unicode ranges of the tag is fragile.
# A locale in neither set is an advisory, forcing a human decision.
NON_LATIN_LOCALES = frozenset({"ar", "ja", "ko", "ru", "zh"})
LATIN_LOCALES = frozenset({"de", "es", "fr", "pt"})

# --------------------------------------------------------------------------------------
# Locale-directory predicate
# --------------------------------------------------------------------------------------

# Accepts values-<lang>, values-<lang>-r<REGION> and values-b+<bcp47>.
LOCALE_DIR_RE = re.compile(r"^values-(?:b\+[A-Za-z0-9+]+|[a-z]{2,3}(?:-r[A-Z]{2})?)$")

# Android resource qualifiers that are shape-compatible with a language code but are not
# locales. `car` is load-bearing: three lowercase letters the regex alone would accept.
NON_LOCALE_QUALIFIERS = frozenset(
    {
        "night", "notnight", "land", "port", "ldrtl", "ldltr", "round", "notround",
        "car", "desk", "watch", "television", "appliance", "vrheadset", "long",
        "notlong", "ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi",
        "tvdpi", "anydpi",
    }
)


def locale_code_of(dirname: str) -> str | None:
    """Return the locale code for a `values-*` directory name, or None if not a locale."""
    if not LOCALE_DIR_RE.match(dirname):
        return None
    suffix = dirname[len("values-") :]
    first_token = suffix.split("-")[0]
    if first_token in NON_LOCALE_QUALIFIERS:
        return None
    return suffix


# --------------------------------------------------------------------------------------
# Repo discovery
# --------------------------------------------------------------------------------------


class HardFailure(Exception):
    """Layout/parse problem that must never be reported as a clean bill of health."""


def find_repo_root(start: Path) -> Path:
    for candidate in [start, *start.parents]:
        if (candidate / "settings.gradle.kts").is_file():
            return candidate
    raise HardFailure(
        f"could not locate repo root: no settings.gradle.kts found in {start} or any parent. "
        "Run this from inside the LibreChat-Android checkout, or pass --repo-root."
    )


def rel(path: Path, root: Path) -> str:
    """Repo-relative POSIX path. Absolute paths must never reach the output."""
    return path.relative_to(root).as_posix()


def rel_or_str(path: Path, root: Path) -> str:
    """rel(), degrading to the basename for a path outside the repo.

    Only for stderr diagnostics about a path that failed to resolve. Never used for report
    content: an absolute path there would be machine-specific and break determinism.
    """
    try:
        return rel(path, root)
    except ValueError:
        return f"<outside repo>/{path.name}"


def read_text_or_fail(path: Path, relpath: str) -> str:
    """Read UTF-8 text, converting any read/decode error into a HARD FAILURE.

    A resource file this script cannot read is not "zero findings" — it is an unmeasured
    file. Unreadable (permissions, broken symlink) and non-UTF-8 (an `encoding=` decl
    ElementTree honours but read_text does not) both land here.
    """
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError as exc:
        raise HardFailure(
            f"{relpath} is not valid UTF-8 ({exc.reason} at byte {exc.start}). "
            "Resource files must be UTF-8; a non-UTF-8 `encoding=` declaration is the "
            "usual cause. Refusing to report this file as clean."
        ) from exc
    except OSError as exc:
        raise HardFailure(
            f"cannot read {relpath}: {exc.strerror or exc}. Refusing to report an "
            "unreadable file as clean."
        ) from exc


# --------------------------------------------------------------------------------------
# XML parsing
# --------------------------------------------------------------------------------------


def _element_value(el: ET.Element) -> str:
    """Full textual value of a <string>, including any child markup, in document order.

    Today no value contains child markup, so the child branch is dead — but it keeps the
    comparison honest if <b> or <xliff:g> is ever introduced.
    """
    parts = [el.text or ""]
    for child in el:
        parts.append(ET.tostring(child, encoding="unicode"))
    return "".join(parts)


class ResourceFile:
    """One parsed strings.xml.

    ElementTree is used (not a regex) so XML comments and CDATA can never be miscounted
    as keys, and attribute order / quote style cannot cause a miss.
    """

    __slots__ = (
        "path", "relpath", "text", "ordered_keys", "values", "duplicates",
        "plurals", "arrays", "non_translatable",
    )

    def __init__(self, path: Path, root: Path):
        self.path = path
        self.relpath = rel(path, root)
        # Read once, through the fail-loud helper, and parse the decoded text: that way a
        # read error, a decode error and a parse error all become HardFailure, and the
        # line-number and second-opinion passes reuse this text instead of re-reading.
        self.text = read_text_or_fail(path, self.relpath)
        try:
            resources = ET.fromstring(self.text)
        except ET.ParseError as exc:
            raise HardFailure(
                f"malformed XML in {self.relpath}: {exc}. "
                "Unescaped '&' or '<' in a value is the usual cause; fix the file and re-run."
            ) from exc

        self.ordered_keys: list[str] = []
        self.values: dict[str, str] = {}
        self.plurals: list[str] = []
        self.arrays: list[str] = []
        # Android's standard marker for "this string is intentionally never translated".
        # Tracked separately so it can be excluded from parity instead of generating a
        # phantom missing-key finding in every locale.
        self.non_translatable: list[str] = []

        for el in list(resources):
            name = el.get("name")
            if el.tag == "string":
                if name is None:
                    raise HardFailure(f"<string> without a name attribute in {self.relpath}")
                self.ordered_keys.append(name)
                self.values[name] = _element_value(el)
                if (el.get("translatable") or "").strip().lower() == "false":
                    self.non_translatable.append(name)
            elif el.tag == "plurals":
                if name is not None:
                    self.plurals.append(name)
            elif el.tag == "string-array":
                if name is not None:
                    self.arrays.append(name)

        # Counter over the ordered list, before collapsing to a dict: set/dict-based
        # extraction destroys duplicate information, and a duplicated key would itself
        # mask a missing key in any count-based reconciliation.
        counts = Counter(self.ordered_keys)
        self.duplicates: list[str] = sorted(k for k, n in counts.items() if n > 1)

    @property
    def keys(self) -> frozenset[str]:
        return frozenset(self.ordered_keys)

    @property
    def translatable_keys(self) -> frozenset[str]:
        """Keys eligible for parity/stub checking: everything not marked translatable="false"."""
        return frozenset(self.ordered_keys) - frozenset(self.non_translatable)


# The exact regex used by the prior-art StringResourceParityTest. Run only as a SECOND
# opinion, to report which files that guard would miscount. Never the primary parser: it
# misses reversed attribute order and single-quoted attrs, and falsely captures
# commented-out and CDATA-embedded <string> tags.
LEGACY_KEY_RE = re.compile(r'<(string|plurals)\s+name="([^"]+)"')


def legacy_keys(rf: ResourceFile) -> frozenset[str]:
    return frozenset(m.group(2) for m in LEGACY_KEY_RE.finditer(rf.text))


# --------------------------------------------------------------------------------------
# Module model
# --------------------------------------------------------------------------------------


class Module:
    __slots__ = ("name", "res_dir", "base", "locales", "absent_locale_files", "unknown_dirs")

    def __init__(self, name: str, res_dir: Path, root: Path):
        self.name = name
        self.res_dir = res_dir
        self.base = ResourceFile(res_dir / "values" / "strings.xml", root)
        self.locales: dict[str, ResourceFile] = {}
        self.absent_locale_files: list[str] = []
        self.unknown_dirs: list[str] = []

        for child in sorted(res_dir.iterdir()):
            if not child.is_dir() or child.name == "values":
                continue
            if not child.name.startswith("values-"):
                continue  # drawable/, font/, ... — not a string qualifier at all
            code = locale_code_of(child.name)
            if code is None:
                self.unknown_dirs.append(child.name)
                continue
            strings = child / "strings.xml"
            if not strings.is_file():
                # Never `continue` silently here: that is exactly the hole that lets a
                # whole locale be deleted while the check stays green.
                self.absent_locale_files.append(f"{child.name}/strings.xml")
                continue
            self.locales[code] = ResourceFile(strings, root)


def discover_modules(root: Path) -> list[Module]:
    """Discover string-resource modules by globbing, at both 1- and 2-segment depths."""
    bases: list[Path] = []
    for pattern in (
        "*/src/commonMain/composeResources/values/strings.xml",
        "*/*/src/commonMain/composeResources/values/strings.xml",
    ):
        bases.extend(sorted(root.glob(pattern)))

    modules: list[Module] = []
    seen: set[str] = set()
    for base in sorted(bases):
        relpath = rel(base, root)
        if "/upstream/" in f"/{relpath}":
            continue
        module_name = relpath.split("/src/")[0]
        if module_name in seen:
            continue
        seen.add(module_name)
        modules.append(Module(module_name, base.parent.parent, root))

    if not modules:
        raise HardFailure(
            "discovered 0 string-resource modules. Expected "
            "<module>/src/commonMain/composeResources/values/strings.xml to exist. "
            "The localization layout has moved — this run proves nothing."
        )
    for module in modules:
        # A module with 0 usable locale directories is deliberately NOT a hard failure:
        # "this feature shipped English-only" is the question the audit exists to answer,
        # and hard-failing here would suppress the findings for every other module too.
        # run_parity scores it as 100% missing in every expected locale, exactly like an
        # absent locale file.
        #
        # Corpus floor. A base strings.xml that parses but declares no keys means the
        # content evaporated while the tree still looks right. Reporting that as
        # "0 findings" would be the worst possible outcome for a check whose whole
        # purpose is to be re-run and diffed.
        if not module.base.ordered_keys:
            raise HardFailure(
                f"module {module.name} has a base strings.xml ({module.base.relpath}) that "
                "declares 0 <string> keys. The corpus is empty or truncated — this run "
                "proves nothing."
            )
    return modules


# --------------------------------------------------------------------------------------
# Allowlist
# --------------------------------------------------------------------------------------

ALLOWLIST_DEFAULT = "config/l10n/i18n-allowlist.txt"


class Allowlist:
    """Exact-equality suppression directives for the stub and hardcoded detectors.

    TAB-separated, one directive per line. Blank lines and `#` comments ignored.

      literal <base value>                 suppress every stub whose BASE value equals this
      key     <module>/<key>               suppress this stub key in every locale
      pair    <module>/values-<loc>  <key> suppress exactly this (stub key, locale)
      site    <path>:<line>                drop this hardcoded candidate site
      file    <path>                       drop every hardcoded candidate in this file

    No regex, no globs, no prefix matching, no normalization — a reviewer must be able to
    verify any line by grepping for it. Suppression is matched against the RAW candidate
    set (before the mechanical auto-exemptions), so an auto-exempted row cannot make a
    directive look stale.
    """

    __slots__ = ("path", "relpath", "literals", "keys", "pairs", "sites", "files", "used", "lines", "errors")

    def __init__(self, path: Path | None, root: Path):
        self.path = path
        if path is None:
            self.relpath = None
        else:
            try:
                self.relpath = rel(path, root)
            except ValueError:
                raise HardFailure(
                    f"--allowlist must resolve inside the repo (got {path}); paths in the report "
                    "are repo-relative by design."
                ) from None
        self.literals: dict[str, int] = {}
        self.keys: dict[str, int] = {}
        self.pairs: dict[tuple[str, str], int] = {}
        self.sites: dict[str, int] = {}
        self.files: dict[str, int] = {}
        self.lines: dict[int, str] = {}
        self.errors: list[str] = []
        self.used: set[int] = set()

        if path is None or not path.is_file():
            return

        for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not raw.strip() or raw.lstrip().startswith("#"):
                continue
            fields = raw.split("\t")
            kind = fields[0].strip()
            if kind == "literal" and len(fields) == 2:
                self.literals[fields[1]] = lineno
            elif kind == "key" and len(fields) == 2:
                self.keys[fields[1].strip()] = lineno
            elif kind == "pair" and len(fields) == 3:
                self.pairs[(fields[1].strip(), fields[2].strip())] = lineno
            elif kind == "site" and len(fields) == 2:
                self.sites[fields[1].strip()] = lineno
            elif kind == "file" and len(fields) == 2:
                self.files[fields[1].strip()] = lineno
            else:
                self.errors.append(
                    f"{self.relpath}:{lineno}: unrecognized directive (fields must be "
                    f"TAB-separated): {raw!r}"
                )
                continue
            # Only successfully parsed directives are staleness-tracked, so a malformed
            # line is reported once (as unrecognized) rather than twice (also as stale).
            self.lines[lineno] = raw

    # Both matchers mark EVERY directive that covers the row as used, then report the
    # first. Marking only the first makes any overlapping directive look stale — one broad
    # `file` directive would strand every `site` directive for the same file — and the
    # shrink-only rule ("a stale directive must be deleted") would then instruct users to
    # delete live suppressions along with their written justifications.

    def match_stub(self, module: str, locale: str, key: str, base_value: str) -> int | None:
        matched = [
            candidate
            for candidate in (
                self.literals.get(base_value),
                self.keys.get(f"{module}/{key}"),
                self.pairs.get((f"{module}/values-{locale}", key)),
            )
            if candidate is not None
        ]
        self.used.update(matched)
        return matched[0] if matched else None

    def match_site(self, relpath: str, line: int) -> int | None:
        matched = [
            candidate
            for candidate in (self.files.get(relpath), self.sites.get(f"{relpath}:{line}"))
            if candidate is not None
        ]
        self.used.update(matched)
        return matched[0] if matched else None

    def stale(self) -> list[str]:
        """Directives that matched nothing. Shrink-only: dead lines must be deleted."""
        return [
            f"{self.relpath}:{lineno}: {self.lines[lineno]}"
            for lineno in sorted(set(self.lines) - self.used)
        ]


# --------------------------------------------------------------------------------------
# Detector P — parity (EXACT)
# --------------------------------------------------------------------------------------


def run_parity(modules: list[Module], root: Path) -> dict:
    findings: list[dict] = []
    advisories: list[str] = []
    per_module: list[dict] = []

    discovered = tuple(sorted(m.name for m in modules))
    if discovered != tuple(sorted(EXPECTED_MODULES)):
        added = sorted(set(discovered) - set(EXPECTED_MODULES))
        removed = sorted(set(EXPECTED_MODULES) - set(discovered))
        advisories.append(
            "MODULE_SET_CHANGED: discovered string-resource modules differ from the expected set. "
            f"added={added} removed={removed}. Update EXPECTED_MODULES in scripts/i18n-coverage.py "
            "deliberately — a silently added module means its drift goes unmeasured."
        )
    else:
        # Only meaningful when the module set matches; otherwise the totals are not
        # comparable and the advisory would be noise on every fixture.
        live_base_keys = sum(len(m.base.keys) for m in modules)
        if live_base_keys < EXPECTED_MIN_BASE_KEYS:
            advisories.append(
                f"CORPUS_SHRANK: total base keys across the expected modules is {live_base_keys}, "
                f"below the recorded floor of {EXPECTED_MIN_BASE_KEYS}. Either the corpus lost "
                "content (truncation / bad merge) or keys were deliberately deleted — in the "
                "latter case lower EXPECTED_MIN_BASE_KEYS in scripts/i18n-coverage.py on purpose."
            )

    for module in modules:
        # translatable="false" declares a key is intentionally never localized, so it must
        # not be scored as missing in every locale. Excluded from `base_keys` (missing) and
        # tolerated in a locale file (not stale), with an advisory so the exclusion is
        # auditable rather than invisible.
        non_translatable = frozenset(module.base.non_translatable)
        if non_translatable:
            advisories.append(
                f"NON_TRANSLATABLE_KEYS: {module.base.relpath} declares "
                f"{len(non_translatable)} translatable=\"false\" key(s) "
                f"{sorted(non_translatable)}, excluded from parity and from stub detection."
            )
        base_keys = module.base.keys - non_translatable

        for dirname in sorted(module.unknown_dirs):
            advisories.append(
                f"UNKNOWN_LOCALE_DIR: {module.name}/{dirname} looks like a locale qualifier but is "
                "not a recognized language code. Not scored as a locale."
            )
        absent_locales: list[str] = []
        for missing_file in sorted(module.absent_locale_files):
            absent_locale = missing_file.split("/")[0][len("values-") :]
            absent_locales.append(absent_locale)
            findings.append(
                {
                    "kind": "ABSENT_LOCALE_FILE",
                    "module": module.name,
                    "locale": absent_locale,
                    "detail": f"{rel(module.res_dir, root)}/{missing_file} does not exist; "
                    f"all {len(base_keys)} base keys are reported missing below",
                }
            )

        locales = sorted(module.locales)
        # An expected locale with no values-<loc>/ directory at all is scored the same as
        # one whose strings.xml was deleted: 100% missing, as an exact structural finding.
        # An advisory alone would be an under-report with the same shape as the count-based
        # comparison this tool exists to replace: a deleted locale directory would produce
        # a warning and ZERO missing rows, leaving its drift invisible in every total. This
        # is also what makes a brand-new English-only module measurable.
        expected_missing = sorted(set(EXPECTED_LOCALES) - set(locales) - set(absent_locales))
        for absent_dir_locale in expected_missing:
            absent_locales.append(absent_dir_locale)
            findings.append(
                {
                    "kind": "ABSENT_LOCALE_DIR",
                    "module": module.name,
                    "locale": absent_dir_locale,
                    "detail": f"{rel(module.res_dir, root)}/values-{absent_dir_locale}/ does not "
                    f"exist; all {len(base_keys)} base keys are reported missing below",
                }
            )
        if expected_missing:
            advisories.append(
                f"LOCALE_SET_INCOMPLETE: {module.name} is missing locale(s) {expected_missing} "
                f"out of the expected {list(EXPECTED_LOCALES)}. Scored as 100% missing."
            )
        unexpected = sorted(set(locales) - set(EXPECTED_LOCALES))
        if unexpected:
            advisories.append(
                f"UNEXPECTED_LOCALE: {module.name} ships locale(s) {unexpected} not in the expected "
                f"set {list(EXPECTED_LOCALES)}. Classify them in NON_LATIN_LOCALES/LATIN_LOCALES."
            )

        for dup in module.base.duplicates:
            findings.append(
                {"kind": "DUPLICATE_KEY", "module": module.name, "locale": None,
                 "key": dup, "detail": f"{module.base.relpath} declares '{dup}' more than once"}
            )

        missing_sets: dict[str, frozenset[str]] = {}
        stale_sets: dict[str, frozenset[str]] = {}

        # An absent locale file is 100% missing, not zero findings, and it is scored
        # alongside the present locales so the per-module table and the TOTAL agree. The
        # prior-art guard test `continue`s here instead, which is what would let a whole
        # locale be deleted while the check stayed green.
        for locale in sorted(absent_locales):
            missing_sets[locale] = frozenset(base_keys)
            stale_sets[locale] = frozenset()
            for key in sorted(base_keys):
                findings.append({"kind": "missing", "module": module.name, "locale": locale, "key": key})

        for locale in locales:
            lf = module.locales[locale]
            for dup in lf.duplicates:
                findings.append(
                    {"kind": "DUPLICATE_KEY", "module": module.name, "locale": locale,
                     "key": dup, "detail": f"{lf.relpath} declares '{dup}' more than once"}
                )
            missing = frozenset(base_keys - lf.keys)
            # Compared against the FULL base key set, so a locale that happens to carry a
            # translatable="false" key is not reported as stale for doing so.
            stale = frozenset(lf.keys - module.base.keys)
            missing_sets[locale] = missing
            stale_sets[locale] = stale
            for key in sorted(missing):
                findings.append({"kind": "missing", "module": module.name, "locale": locale, "key": key})
            for key in sorted(stale):
                findings.append({"kind": "stale", "module": module.name, "locale": locale, "key": key})

            if lf.plurals or lf.arrays:
                advisories.append(
                    f"NON_STRING_RESOURCE: {lf.relpath} contains "
                    f"{len(lf.plurals)} <plurals> / {len(lf.arrays)} <string-array>. "
                    "Parity for those forms is NOT checked, and <string-array> is invisible to "
                    "StringResourceParityTest's regex."
                )
        if module.base.plurals or module.base.arrays:
            advisories.append(
                f"NON_STRING_RESOURCE: {module.base.relpath} contains "
                f"{len(module.base.plurals)} <plurals> / {len(module.base.arrays)} <string-array>. "
                "Parity for those forms is NOT checked."
            )

        # Second-opinion parse. Reports which files the prior-art guard test would
        # miscount, without this script re-litigating parity through a regex.
        for rf in [module.base] + [module.locales[loc] for loc in locales]:
            regex_keys = legacy_keys(rf)
            if regex_keys != rf.keys:
                only_regex = sorted(regex_keys - rf.keys)
                only_xml = sorted(rf.keys - regex_keys)
                advisories.append(
                    f"PARSER_DISAGREEMENT: {rf.relpath} — StringResourceParityTest's regex sees "
                    f"keys the XML parser does not ({only_regex}) and/or misses keys it should see "
                    f"({only_xml}). That guard would miscount this file."
                )

        scored = sorted(missing_sets)
        distinct_missing = len({missing_sets[loc] for loc in scored})
        distinct_stale = len({stale_sets[loc] for loc in scored})
        uniform = distinct_missing <= 1 and distinct_stale <= 1
        per_module.append(
            {
                "module": module.name,
                "base_keys": len(base_keys),
                "non_translatable_keys": sorted(non_translatable),
                "locales": locales,
                "absent_locales": sorted(absent_locales),
                "missing_total": sum(len(missing_sets[loc]) for loc in scored),
                "stale_total": sum(len(stale_sets[loc]) for loc in scored),
                "distinct_missing_keys": sorted(set().union(*missing_sets.values()) if scored else set()),
                "distinct_stale_keys": sorted(set().union(*stale_sets.values()) if scored else set()),
                "uniform_across_locales": uniform,
                # Populated ONLY when drift is non-uniform. When it is uniform the collapsed
                # distinct_* lists above are lossless (that is what `uniform` asserts), so
                # duplicating them per locale would just double the report for no signal.
                # When it is NOT uniform the collapsed lists are a union over locales and
                # would misstate both the scope and the affected locale, so the renderer
                # must use these instead.
                "missing_by_locale": (
                    {} if uniform else {loc: sorted(missing_sets[loc]) for loc in scored if missing_sets[loc]}
                ),
                "stale_by_locale": (
                    {} if uniform else {loc: sorted(stale_sets[loc]) for loc in scored if stale_sets[loc]}
                ),
            }
        )

    return {
        # Self-describing for machine consumers: parity is a set operation over declared
        # resources, so every finding is a proven defect, not a candidate.
        "confidence": "exact",
        "per_module": per_module,
        "findings": findings,
        "advisories": sorted(set(advisories)),
        "missing_total": sum(1 for f in findings if f["kind"] == "missing"),
        "stale_total": sum(1 for f in findings if f["kind"] == "stale"),
        "structural_total": sum(1 for f in findings if f["kind"] in STRUCTURAL_KINDS),
    }


def android_res_note(root: Path) -> dict:
    """Android platform res/ is a separate surface, deliberately outside parity math.

    Folding it in would fabricate nine phantom 'entire locale missing' failures; ignoring
    it entirely would hide that its strings are untranslated.
    """
    values = root / "app" / "src" / "main" / "res" / "values" / "strings.xml"
    if not values.is_file():
        return {"present": False}
    rf = ResourceFile(values, root)
    res_dir = values.parent.parent
    locale_dirs = sorted(
        d.name
        for d in sorted(res_dir.iterdir())
        if d.is_dir() and d.name.startswith("values-") and locale_code_of(d.name) and (d / "strings.xml").is_file()
    )
    return {
        "present": True,
        "path": rf.relpath,
        "keys": sorted(rf.keys),
        "locale_dirs_with_strings": locale_dirs,
    }


# --------------------------------------------------------------------------------------
# Detector S — untranslated stubs (EXACT)
# --------------------------------------------------------------------------------------

FORMAT_SPEC_RE = re.compile(r"%(\d+\$)?[a-zA-Z]|%%")
LETTER_RE = re.compile(r"[A-Za-z]")
STRING_LINE_RE = re.compile(r'<string\s+name="([^"]+)"')
NEAR_IDENTICAL_STRIP_RE = re.compile(r"[\s.:!?…]+")


def is_ascii(value: str) -> bool:
    return all(ord(ch) < 128 for ch in value)


def is_letterless(base_value: str) -> bool:
    """True when the value carries no translatable prose once format specifiers are removed.

    This inspects format specifiers ONLY to decide whether prose exists. It never compares
    specifier sets or counts between base and locale — that is explicitly out of scope.
    """
    return LETTER_RE.search(FORMAT_SPEC_RE.sub("", base_value)) is None


def key_line_numbers(rf: ResourceFile) -> dict[str, int]:
    out: dict[str, int] = {}
    for lineno, line in enumerate(rf.text.splitlines(), start=1):
        m = STRING_LINE_RE.search(line)
        if m and m.group(1) not in out:
            out[m.group(1)] = lineno
    return out


def run_stubs(modules: list[Module], allowlist: Allowlist, include_latin: bool, near_identical: bool) -> dict:
    errors: list[dict] = []
    review: list[dict] = []
    suppressed: list[dict] = []
    auto_exempt: list[dict] = []
    info_latin: list[dict] = []
    info_near: list[dict] = []
    runs: list[dict] = []
    advisories: list[str] = []

    for module in modules:
        base = module.base
        locales = sorted(module.locales)
        # translatable="false" says this value is deliberately identical everywhere, which
        # is precisely the stub predicate — so scoring it would be a guaranteed FP.
        non_translatable = frozenset(base.non_translatable)

        unclassified = sorted(set(locales) - NON_LATIN_LOCALES - LATIN_LOCALES)
        if unclassified:
            advisories.append(
                f"UNCLASSIFIED_SCRIPT: {module.name} locale(s) {unclassified} are in neither "
                "NON_LATIN_LOCALES nor LATIN_LOCALES. Stub detection SKIPPED for them — classify "
                "them in scripts/i18n-coverage.py before trusting this section."
            )

        for key in sorted(base.keys - non_translatable):
            base_value = base.values[key]
            present = [loc for loc in locales if key in module.locales[loc].values]
            if not present:
                continue
            identical = [loc for loc in present if module.locales[loc].values[key] == base_value]
            translated_by = [loc for loc in present if module.locales[loc].values[key] != base_value]

            # AUTO-EXEMPT A: no locale anywhere translated this term -> shared literal.
            shared_literal = len(identical) == len(present)
            # AUTO-EXEMPT B: nothing translatable in the value at all.
            letterless = is_letterless(base_value)

            for locale in identical:
                if locale not in NON_LATIN_LOCALES:
                    if include_latin and locale in LATIN_LOCALES:
                        info_latin.append(
                            {"module": module.name, "locale": locale, "key": key, "value": base_value}
                        )
                    continue
                value = module.locales[locale].values[key]
                if not is_ascii(value):
                    continue

                row = {
                    "module": module.name,
                    "locale": locale,
                    "key": key,
                    "value": value,
                    "translated_by": sorted(translated_by),
                }

                # Allowlist is matched against the RAW hit set, before the auto-exemptions,
                # so an auto-exempted row can never make a directive look stale.
                hit = allowlist.match_stub(module.name, locale, key, base_value)
                if hit is not None:
                    suppressed.append({**row, "allowlist_line": hit})
                    continue
                if shared_literal:
                    auto_exempt.append({**row, "rule": "shared-literal"})
                    continue
                if letterless:
                    auto_exempt.append({**row, "rule": "letterless-value"})
                    continue
                if len(translated_by) >= 4:
                    errors.append(row)
                else:
                    review.append(row)

        # CORROBORATOR: contiguous runs of >=3 untranslated keys by source line. Direct
        # evidence a translator skipped a region. Non-Latin only — extended to Latin it is
        # 100% false (correct French/German words that coincide with English).
        for locale in locales:
            if locale not in NON_LATIN_LOCALES:
                continue
            lf = module.locales[locale]
            lines = key_line_numbers(lf)
            run: list[str] = []
            for key in lf.ordered_keys:
                hit = (
                    key in base.values
                    and key not in non_translatable
                    and lf.values[key] == base.values[key]
                    and is_ascii(lf.values[key])
                )
                if hit:
                    run.append(key)
                    continue
                if len(run) >= 3:
                    runs.append(_run_record(module.name, locale, lf, run, lines))
                run = []
            if len(run) >= 3:
                runs.append(_run_record(module.name, locale, lf, run, lines))

        if near_identical:
            for key in sorted(base.keys - non_translatable):
                base_value = base.values[key]
                norm_base = NEAR_IDENTICAL_STRIP_RE.sub("", base_value).casefold()
                for locale in locales:
                    lf = module.locales[locale]
                    if key not in lf.values:
                        continue
                    value = lf.values[key]
                    if value == base_value:
                        continue
                    if NEAR_IDENTICAL_STRIP_RE.sub("", value).casefold() == norm_base:
                        info_near.append(
                            {"module": module.name, "locale": locale, "key": key,
                             "base": base_value, "locale_value": value}
                        )

    sort_key = lambda r: (r["module"], r["locale"], r["key"])  # noqa: E731
    return {
        # Byte-identity of a declared value is a proven fact, not a guess.
        "confidence": "exact",
        # Counts are stored explicitly, not derived from the lists, so --summary can drop
        # the per-item detail without silently changing the reported totals.
        "counts": {
            "errors": len(errors),
            "error_keys": len({(r["module"], r["key"]) for r in errors}),
            "errors_per_locale": {k: v for k, v in sorted(Counter(r["locale"] for r in errors).items())},
            "review": len(review),
            "review_keys": len({(r["module"], r["key"]) for r in review}),
            "suppressed": len(suppressed),
            "auto_exempt_shared_literal": sum(1 for r in auto_exempt if r["rule"] == "shared-literal"),
            "auto_exempt_letterless": sum(1 for r in auto_exempt if r["rule"] == "letterless-value"),
            "runs": len(runs),
            "info_latin": len(info_latin),
            "info_near_identical": len(info_near),
        },
        "errors": sorted(errors, key=sort_key),
        "review": sorted(review, key=sort_key),
        "suppressed": sorted(suppressed, key=sort_key),
        "auto_exempt": sorted(auto_exempt, key=lambda r: (r["rule"], r["module"], r["locale"], r["key"])),
        "runs": sorted(runs, key=lambda r: (r["module"], r["locale"], r["first_line"])),
        "info_latin": sorted(info_latin, key=sort_key),
        "info_near_identical": sorted(info_near, key=sort_key),
        "advisories": sorted(set(advisories)),
    }


def _run_record(module: str, locale: str, lf: ResourceFile, run: list[str], lines: dict[str, int]) -> dict:
    first = min(lines.get(k, 0) for k in run)
    last = max(lines.get(k, 0) for k in run)
    return {
        "module": module,
        "locale": locale,
        "file": lf.relpath,
        "first_line": first,
        "last_line": last,
        "length": len(run),
        "keys": list(run),
    }


# --------------------------------------------------------------------------------------
# Detector H — unextracted literals (HEURISTIC, candidates only)
# --------------------------------------------------------------------------------------

H_PATH_EXCLUDE = re.compile(
    r"(^|/)(build|upstream|\.git|\.gradle|iosApp|detekt-rules|build-logic|scripts|docs|config)(/|$)"
    r"|/src/(androidTest|androidUnitTest|androidInstrumentedTest|commonTest|iosTest|jvmTest|test)/"
    r"|(Test|Tests|Fake|Fakes|Preview|Previews)\.kt$"
    r"|/generated/"
    r"|/composeResources/"
    r"|(^|/)core/logging/"
    r"|(CrashReporting|ExceptionHandler|LibreChatApplication)[A-Za-z.]*\.kt$"
)

# The single most important suppression: the repo logs through core/logging's LogHelper
# exposed as `Logger`/`log` with a trailing lambda. 263 lines. There is no Timber.
H_SUPPRESS_LOGGING = re.compile(r"\b(Logger|log|logger)\s*\.\s*[a-z]+\s*(\([^)]*\))?\s*\{")

H_SUPPRESS_NON_UI = re.compile(
    # The lookbehind on Error( is load-bearing: unanchored, `Error\(\s*"` also matches the
    # tail of `setError("…")` / `showError("…")` and suppresses the WHOLE line before
    # H_SINKS runs, silently shadowing two sinks the `sink_call` rule explicitly names and
    # hiding the user-facing error banners the detector is meant to find. Match only a bare
    # constructor call (`throw Error("…")`), never a method whose name ends in Error.
    r"\bthrow\b|Exception\(|(?<![A-Za-z0-9_])Error\(\s*\"|require\(|requireNotNull"
    r"|check\(|checkNotNull|\berror\("
    r"|testTag\(|named\(\"|[Pp]referencesKey\("
    r"|@SerialName|@Suppress|@OptIn|@SuppressLint|@Deprecated|@Test"
    r"|Regex\(|\.toRegex\("
    r"|\bheader\(|headers\.|ContentType\.|\bappendPathSegments\("
    r"|buildJsonObject|putJsonObject|\bput\(\"|jsonPrimitive"
    r"|rememberInfiniteTransition|animate[A-Za-z]*AsState|updateTransition|\bCrossfade\("
    r"|^\s*(//|\*|/\*)"
)

_LIT = r'"((?:[^"\\\n]|\\.)*)"'

# An optional Kotlin type annotation between the slot name and `=`, so a composable's
# rendered DEFAULT (`verifyLabel: String = "Verify"`) is caught, not just a call-site
# argument.
_TYPE_ANN = r"(?:\s*:\s*[A-Za-z_][A-Za-z0-9_.]*[?]?(?:<[^=>\n]*>)?[?]?)?"

# camelCase-suffixed slot names (`backupCodeLabel`, `dialogTitle`, `stateDescription`) are
# as user-facing as the bare names, and a closed bare-name list silently misses them.
_SLOT_SUFFIX = r"[A-Za-z]*(?:Label|Title|Text|Description|Hint|Placeholder|Headline|Subtitle)"

# Trial order is fixed so rule attribution is reproducible run to run. content_desc is
# tried before ui_param so an accessibility slot keeps its own family even though the
# relaxed ui_param name pattern would also match it.
H_SINKS: tuple[tuple[str, re.Pattern[str], re.Pattern[str] | None], ...] = (
    ("text_call", re.compile(r"(?<![A-Za-z0-9_.])Text\(\s*(?:text\s*=\s*)?" + _LIT), None),
    (
        "content_desc",
        re.compile(
            r"(?<![A-Za-z0-9_])(?:contentDescription|stateDescription)"
            + _TYPE_ANN + r"\s*=\s*" + _LIT
        ),
        None,
    ),
    (
        "ui_param",
        re.compile(
            r"(?<![A-Za-z0-9_])(?:" + _SLOT_SUFFIX
            + r"|label|placeholder|title|text|description|subtitle|hint|headline)"
            + _TYPE_ANN + r"\s*=\s*" + _LIT
        ),
        None,
    ),
    (
        "state_error",
        re.compile(
            r"(?<![A-Za-z0-9_])(?:error|errorMessage|message|snackbarMessage|toastMessage)"
            + _TYPE_ANN + r"\s*=\s*" + _LIT
        ),
        None,
    ),
    # The guard is load-bearing: unguarded, `?: "lit"` matches 334 lines of URL/path/default
    # fallbacks; guarded it is 118 genuine `error = result.message ?: "Failed to …"` sites.
    # `$` stands in for the newline terminator: the literal is frequently the last thing on
    # its line, and matching is done line-by-line so no newline character is present.
    ("elvis_error", re.compile(r"\?:\s*" + _LIT + r"\s*(?:[,)]|$)"), re.compile(r"\b(error|errorMessage|message)\b")),
    # Project-specific user-facing sinks. A closed list is a recall hole, so every name
    # here is a grep-verified in-repo sink: `toast()` is core/ui's Android helper,
    # `fail()` reaches handle.setError via VoiceInputDelegate, `copyToClipboard()`'s second
    # argument is the clipboard label the OS paste UI shows, `onComplete(ok, message)` is
    # the export/share result channel, and `errors.add()` feeds ActionEditorDialog's
    # validation list.
    (
        "sink_call",
        re.compile(
            r"(?<![A-Za-z0-9_])(?:setError|failStart|showError|showMessage|showToast"
            r"|showSnackbar|makeText|toast|fail|copyToClipboard|onComplete|errors\.add)\s*\("
            r"[^\"\n]*" + _LIT
        ),
        None,
    ),
    ("when_branch", re.compile(r"->\s*" + _LIT + r"\s*$"), None),
)

# Continuation-window openers: a line that opens a user-facing sink but whose literal is on
# a later physical line. `->$` and `<slot> = if` are included so a `when`/`if` branch whose
# literal wrapped onto the next line is still reported, instead of at no line at all.
H_CONT_OPEN_TRAILING = re.compile(
    r"(contentDescription\s*=|(?<![A-Za-z0-9_.])Text\(|showSnackbar\(|makeText\(|setError\("
    r"|failStart\(|\btext\s*=|->)$"
)
H_CONT_OPEN_IF = re.compile(
    r"(?:contentDescription|stateDescription|title|label|description|text|message|error)"
    r"\s*=\s*if\b"
)
H_CONT_LITERAL = re.compile(r"^\s*" + _LIT + r"\s*,?\s*$")

# Enum constructor display labels: `VIEW_ONLY("View only")`. Handled outside H_SINKS
# because the decision needs BOTH captures — a SCREAMING_SNAKE constant whose literal is
# byte-identical to its own name is a wire token (`USE("USE")`, `MCP_SERVERS("MCP_SERVERS")`),
# not a label, and dropping those is what makes this rule clean.
H_ENUM_LABEL = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*\(\s*" + _LIT)

H_INTERP_BRACE = re.compile(r"\$\{[^}]*\}")
H_INTERP_BARE = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*")
H_MIME = re.compile(r"^[a-z0-9]+/[a-z0-9.+\-]+$")
# Any URI scheme, not just http(s): the deep-link scheme `librechat://` and `prompt://`
# are addresses, never prose. Exactly 2 rows, both verified non-prose, 0 collateral.
H_URI_SCHEME = re.compile(r"^[a-z][a-z0-9+.\-]*://")
H_CAMEL_HUMPS = re.compile(r"^(?:[A-Z][a-z0-9]*){2,}$")
H_DOTTED = re.compile(r"^[a-z0-9]+(?:[._\-][a-z0-9]+)+$")
H_LOWER_CAMEL = re.compile(r"^[a-z][a-zA-Z0-9]*$")
H_CONSTANT_CASE = re.compile(r"^[A-Z][A-Z_0-9]+$")


def h_prose_gate(literal: str) -> bool:
    """True when a captured literal plausibly reads as user-facing prose."""
    text = literal.strip()
    if len(text) < 2:
        return False
    if not LETTER_RE.search(text):
        return False
    residue = H_INTERP_BARE.sub("", H_INTERP_BRACE.sub("", text))
    if not re.search(r"[A-Za-z]{2}", residue):
        return False
    if H_URI_SCHEME.match(text):
        return False
    if text.startswith(("/", ".", "@", "#", "<", "{")):
        return False
    if "::" in text:
        return False
    if H_MIME.match(text):
        return False
    if " " not in text:
        # A spaceless multi-hump CamelCase / dotted / all-lowercase token is an identifier
        # (animation labels, wire enum values, file extensions, persisted preference
        # values); a single CAPITALIZED word is UI text ("Back", "OK").
        #
        # The all-lowercase clause is deliberately unconditional. Relaxing it to "length
        # >= 5 or contains a digit" — so real lowercase UI words like "now" survive — was
        # measured on this corpus: it admits 2 true positives (TimestampFormatter's "now"
        # on both platforms) and 48 false positives (image/audio/code file extensions,
        # MCP transport tokens, export-format tokens, DataStore enum values such as
        # "off"/"top"/"left"/"fill"/"none"). No lexical rule separates "now" from "off",
        # so recall here is traded away on purpose; see the declared recall limits.
        if H_CAMEL_HUMPS.match(text) or H_DOTTED.match(text) or H_LOWER_CAMEL.match(text):
            return False
    if H_CONSTANT_CASE.match(text) and "_" in text:
        return False
    return True


H_SCAN_ROOTS = ("shared", "core", "feature", "app")

# Re-derived by triaging the CURRENT output, not carried forward from an earlier
# calibration. Every FP class named here is enumerable — a reader can grep the stated
# file/rule and count the rows. Classes that a gate already removes are NOT listed: naming
# a class that contributes 0 rows makes the precision claim unauditable.
# The denominator is the triage sample size — a fact about when the triage happened — and
# is deliberately kept separate from the live `total`. Never inline a row count into the
# estimate text: a later detector change leaves a precision claim about a count that no
# longer exists, with nothing to flag the drift.
H_PRECISION_TRIAGED_TOTAL = 536
H_PRECISION_ESTIMATE = (
    f"~98% (8 enumerated false positives + ~8 debatable, triaged over "
    f"{H_PRECISION_TRIAGED_TOTAL} rows; compare against the live `total` field — if they "
    f"differ, the detector changed and this estimate is unverified)"
)

# A @Preview composable's body is throwaway sample data, not shipped UI. The path exclusion
# only drops files NAMED *Preview.kt; without this, every @Preview function living inside
# its own component file is scanned — 21 false positives in core/ui alone.
H_PREVIEW_ANNOTATION = re.compile(r"^\s*@Preview\w*\b")
H_FUN_DECL = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:private |internal |public )?fun\b")
H_TOP_LEVEL_DECL = re.compile(
    r"^(?:@|(?:private |internal |public |expect |actual )*"
    r"(?:fun|val|var|class|object|interface|enum|data|sealed|typealias)\b)"
)


def preview_line_ranges(lines: list[str]) -> list[tuple[int, int]]:
    """1-based inclusive line ranges of top-level functions annotated @Preview.

    Mechanical: no allowlist entry and no human judgment. A top-level Kotlin function body
    closes with `}` in column 0; if a top-level declaration starts first (an
    expression-bodied preview), the range ends there instead so it cannot swallow the file.
    """
    ranges: list[tuple[int, int]] = []
    n = len(lines)
    i = 0
    while i < n:
        if not H_PREVIEW_ANNOTATION.match(lines[i]):
            i += 1
            continue
        start = i
        # Skip the remaining annotation lines (@Composable, more @Preview*, ...).
        fun_line = None
        for j in range(i, min(i + 12, n)):
            if H_FUN_DECL.match(lines[j]):
                fun_line = j
                break
        if fun_line is None:
            i += 1
            continue
        end = n - 1
        for k in range(fun_line + 1, n):
            if lines[k] == "}":
                end = k
                break
            if H_TOP_LEVEL_DECL.match(lines[k]):
                end = k - 1
                break
        ranges.append((start + 1, end + 1))
        i = end + 1
    return ranges


def module_of(relpath: str) -> str:
    """Gradle module id for a source path, split on the /src/ boundary.

    Must agree with discover_modules (which splits base strings.xml paths the same way),
    because [P]/[S] and [H] totals are joined per module by this string. Splitting on path
    SEGMENTS instead yields 'shared/src' and 'app/src' for single-segment modules — ids
    that appear in no other detector's table, so those candidates drop silently out of any
    per-module sizing.
    """
    return relpath.split("/src/")[0] if "/src/" in relpath else relpath


def run_hardcoded(root: Path, allowlist: Allowlist) -> dict:
    candidates: list[dict] = []
    suppressed: list[dict] = []
    unreadable: list[str] = []

    files: list[Path] = []
    for scan_root in H_SCAN_ROOTS:
        base = root / scan_root
        if not base.is_dir():
            continue
        files.extend(sorted(base.rglob("*.kt")))

    for path in sorted(files):
        relpath = rel(path, root)
        if H_PATH_EXCLUDE.search(relpath):
            continue
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeDecodeError):
            # Heuristic detector, so an odd source file is an advisory rather than a hard
            # failure — but it is never silent: an unscanned file is unmeasured recall.
            unreadable.append(relpath)
            continue

        preview_ranges = preview_line_ranges(lines)

        window = 0
        for lineno, line in enumerate(lines, start=1):
            if H_SUPPRESS_LOGGING.search(line) or H_SUPPRESS_NON_UI.search(line):
                window = 0
                continue

            hits: list[tuple[str, str]] = []
            for rule, pattern, guard in H_SINKS:
                if guard is not None and not guard.search(line):
                    continue
                for m in pattern.finditer(line):
                    hits.append((rule, m.group(1)))

            enum_m = H_ENUM_LABEL.match(line)
            if enum_m is not None and enum_m.group(1) != enum_m.group(2):
                hits.append(("enum_label", enum_m.group(2)))

            if window > 0:
                m = H_CONT_LITERAL.match(line)
                if m:
                    hits.append(("continuation", m.group(1)))

            in_preview = any(a <= lineno <= b for a, b in preview_ranges)

            seen_literals: set[str] = set()
            for rule, literal in hits:
                if literal in seen_literals:
                    continue
                seen_literals.add(literal)
                # A nested Kotlin string template ("... ${if (x) \"a\" else \"b\"}") ends the
                # capture at the inner quote, so the printed literal would not exist in the
                # source and could not be grepped during triage. Show the source line and
                # flag it rather than trying to balance nested quotes with a regex.
                truncated = literal.count("${") > literal.count("}")
                row = {
                    "module": module_of(relpath),
                    "file": relpath,
                    "line": lineno,
                    "rule": rule,
                    "literal": literal,
                    "display": line.strip()[:120] if truncated else literal,
                    "truncated": truncated,
                    "confidence": "heuristic",
                    "source": line.strip()[:120],
                }
                hit = allowlist.match_site(relpath, lineno)
                if hit is not None:
                    suppressed.append({**row, "allowlist_line": hit})
                    continue
                if in_preview:
                    continue
                if not h_prose_gate(literal):
                    continue
                candidates.append(row)

            stripped = line.rstrip()
            if H_CONT_OPEN_TRAILING.search(stripped) or H_CONT_OPEN_IF.search(stripped):
                window = 4
            elif window > 0:
                window -= 1

    by_rule = Counter(c["rule"] for c in candidates)
    by_module = Counter(c["module"] for c in candidates)
    advisories = [
        f"UNREADABLE_SOURCE: {p} could not be decoded as UTF-8 or could not be read, so it "
        "was NOT scanned for unextracted literals. Recall for that file is unknown."
        for p in sorted(unreadable)
    ]
    return {
        # Sink-anchored regex over a language with no type-level marking of user-facing
        # strings. Stamped so a machine consumer cannot mistake a candidate for a proven
        # defect the way the exact detectors' findings can be trusted.
        "confidence": "heuristic",
        "precision_estimate": H_PRECISION_ESTIMATE,
        "candidates": sorted(candidates, key=lambda c: (c["module"], c["file"], c["line"], c["rule"], c["literal"])),
        "suppressed": sorted(suppressed, key=lambda c: (c["file"], c["line"], c["rule"], c["literal"])),
        "by_rule": {k: by_rule[k] for k in sorted(by_rule)},
        "by_module": {k: by_module[k] for k in sorted(by_module)},
        "total": len(candidates),
        "suppressed_total": len(suppressed),
        "advisories": sorted(advisories),
    }


# --------------------------------------------------------------------------------------
# Text rendering
# --------------------------------------------------------------------------------------

H_PRECISION_NOTE = (
    "Precision re-derived from THIS output by triaging every row of the six small\n"
    "  families and every distinct literal of the two large ones. Enumerated false\n"
    "  positives, all in one class:\n"
    "    sink_call  8  core/network/.../sse/HttpResponseParser.kt HTTP/1.1 wire-format\n"
    "                  parse diagnostics ('malformed header line', 'invalid chunk size').\n"
    "                  Matched on the function NAME `fail(`, but that `fail` is a private\n"
    "                  parser helper, not the voice-input sink of the same name. They\n"
    "                  reach the user only wrapped as SseStreamException(\"parser: …\").\n"
    "  Debatable, reported on purpose because they do reach the screen:\n"
    "    state_error ~7  developer-facing diagnostics that still surface (SseEventMapper\n"
    "                    'Parse error: …' x2, ConversationExporter 'Unexpected loading\n"
    "                    state' x4, RoleRepositoryImpl 'User load still in progress').\n"
    "    elvis_error ~1  ArtifactPanel 'Unknown error' (rendered in the WebView error page).\n"
    "  Clean families at 0 enumerated FPs: text_call, content_desc, continuation,\n"
    "  when_branch, ui_param, and enum_label (whose SCREAMING_SNAKE-equals-its-own-\n"
    "  literal wire tokens in core/model/permissions are dropped mechanically).\n"
    "  Note the classes an earlier calibration attributed to ui_param — animation\n"
    "  labels, the 'ChatGPT' brand placeholder, the OpenAPI JSON sample — contribute 0\n"
    "  rows: the hard suppressions, the identifier gate and the allowlist already remove\n"
    "  all of them, so they are no longer listed.\n"
    "  Known recall limits: (a) string-concatenation fragments beyond the 4-line\n"
    "  continuation window are reported once at the head line, not per fragment\n"
    "  (ChatApi.kt:42 and :52); (b) a `val message = \"…\"` bound far from its sink is\n"
    "  missed; (c) android:label in app/src/main/AndroidManifest.xml is out of scope —\n"
    "  only .kt files are scanned; (d) user-facing English inside raw-string (\"\"\"…\"\"\")\n"
    "  HTML/JS templates is NOT scanned — no sink pattern applies inside an inline web\n"
    "  document. Verified live in 6 files: artifact/ArtifactPanel.kt ('Failed to load\n"
    "  preview'), artifact/ArtifactWebContent.kt ('Component failed to render'),\n"
    "  artifact/MermaidWebContent.kt ('Mermaid parse error'), MermaidDiagram.kt and\n"
    "  PlatformMediaComponents.ios.kt ('Diagram error'); (e) a spaceless all-lowercase\n"
    "  word is dropped by the prose gate, so TimestampFormatter's 'now' is missed while\n"
    "  its four siblings are reported. Measured: admitting them yields 2 true positives\n"
    "  and 48 false ones (file extensions, MCP transport tokens, DataStore enum values),\n"
    "  so that recall is given up deliberately."
)


def render_text(report: dict, out) -> None:
    p = report.get("parity")
    s = report.get("stubs")
    h = report.get("hardcoded")

    w = lambda *a: print(*a, file=out)  # noqa: E731

    w("=" * 78)
    w("i18n COVERAGE REPORT — Switchboard")
    w("=" * 78)
    w(f"allowlist: {report['allowlist'] or '(none)'}")
    w(f"detectors: {report['detector']}")
    w("")

    if p is not None:
        w("-" * 78)
        w("[P] KEY PARITY — EXACT")
        w("-" * 78)
        w("Per module: base key count, locales with a strings.xml, locales whose strings.xml")
        w("is entirely gone ('gone' — scored as 100% missing), then missing (in base, not in")
        w("locale) and stale (in locale, not in base) totals summed over every scored locale.")
        w("Both directions are computed independently: a count-only comparison understates")
        w("real drift wherever a module has extras that cancel against its missing keys.")
        w("")
        w(f"{'module':24} {'base':>5} {'loc':>4} {'gone':>5} {'missing':>8} {'stale':>6} {'uniform':>8}")
        for row in p["per_module"]:
            w(
                f"{row['module']:24} {row['base_keys']:>5} {len(row['locales']):>4} "
                f"{len(row['absent_locales']):>5} "
                f"{row['missing_total']:>8} {row['stale_total']:>6} "
                f"{('yes' if row['uniform_across_locales'] else 'NO'):>8}"
            )
        w("")
        w(f"TOTAL missing={p['missing_total']}  stale={p['stale_total']}  "
          f"structural={p['structural_total']}  "
          f"sum={p['missing_total'] + p['stale_total'] + p['structural_total']}")
        w("")
        w("'uniform' = all locales in the module share one identical missing set and one")
        w("identical stale set, so the per-module key list below is lossless.")
        w("")
        for row in p["per_module"]:
            if not row["distinct_missing_keys"] and not row["distinct_stale_keys"]:
                continue
            if row["uniform_across_locales"]:
                w(f"  {row['module']} ({len(row['locales'])} locales)")
                if row["distinct_missing_keys"]:
                    w(f"    missing ({len(row['distinct_missing_keys'])} keys x locale):")
                    for key in row["distinct_missing_keys"]:
                        w(f"      {key}")
                if row["distinct_stale_keys"]:
                    w(f"    stale ({len(row['distinct_stale_keys'])} keys x locale):")
                    for key in row["distinct_stale_keys"]:
                        w(f"      {key}")
                w("")
                continue
            # NON-UNIFORM: the collapsed lists above are a union over locales, so the
            # 'K keys x locale' phrasing would multiply the real scope and never name the
            # affected locale — exactly the masked-key case this tool exists to catch.
            # List per locale instead, so the printed scope matches the totals column.
            affected = sorted(set(row["missing_by_locale"]) | set(row["stale_by_locale"]))
            w(f"  {row['module']} — NON-UNIFORM drift, listed per locale "
              f"({len(affected)} of {len(row['locales']) + len(row['absent_locales'])} affected)")
            for locale in affected:
                gone = " [strings.xml absent]" if locale in row["absent_locales"] else ""
                missing = row["missing_by_locale"].get(locale, [])
                stale = row["stale_by_locale"].get(locale, [])
                w(f"    {row['module']}/values-{locale}{gone}")
                if missing:
                    w(f"      missing ({len(missing)}): {', '.join(missing)}")
                if stale:
                    w(f"      stale ({len(stale)}): {', '.join(stale)}")
            w("")
        if p["structural_total"]:
            w(f"  STRUCTURAL DEFECTS ({p['structural_total']})")
            for f in p["findings"]:
                if f["kind"] in STRUCTURAL_KINDS:
                    w(f"    {f['kind']}: {f['detail']}")
            w("")
        else:
            w("  STRUCTURAL DEFECTS: none (0 absent locale files, 0 duplicate keys)")
            w("")

    if s is not None:
        w("-" * 78)
        w("[S] UNTRANSLATED STUBS — EXACT")
        w("-" * 78)
        w("A key present in a non-Latin-script locale whose value is byte-identical to the")
        w("English base and pure ASCII was never translated. Shared literals (no locale")
        w("anywhere translated them) and letterless values (symbols / pure format strings)")
        w("are auto-exempted mechanically. ERROR tier = at least 4 other locales did")
        w("translate the key; REVIEW tier = only 1-3 did, so it may be intentional.")
        w("")
        sc = s["counts"]
        raw = (
            sc["errors"] + sc["review"] + sc["suppressed"]
            + sc["auto_exempt_shared_literal"] + sc["auto_exempt_letterless"]
        )
        w(f"raw predicate hits         {raw} rows")
        w(f"ERROR   {sc['errors']} rows / {sc['error_keys']} distinct keys")
        w(f"REVIEW  {sc['review']} rows / {sc['review_keys']} distinct keys")
        w(f"suppressed by allowlist    {sc['suppressed']} rows")
        w(f"auto-exempt shared-literal {sc['auto_exempt_shared_literal']} rows")
        w(f"auto-exempt letterless     {sc['auto_exempt_letterless']} rows")
        w("")
        if sc["errors_per_locale"]:
            w("ERROR rows per locale: " + "  ".join(f"{k}={v}" for k, v in sc["errors_per_locale"].items()))
            w("")
        if s["errors"]:
            w("  ERROR — untranslated (paste-able suppression line shown for each)")
            for r in s["errors"]:
                w(f"    {r['module']}/values-{r['locale']}  {r['key']} = {r['value']!r}")
                w(f"      translated by: {', '.join(r['translated_by'])}")
                w(f"      suppress with: pair\\t{r['module']}/values-{r['locale']}\\t{r['key']}")
            w("")
        if s["review"]:
            w("  REVIEW — ambiguous (few other locales translated these)")
            for r in s["review"]:
                w(f"    {r['module']}/values-{r['locale']}  {r['key']} = {r['value']!r} "
                  f"(translated by: {', '.join(r['translated_by']) or 'none'})")
            w("")
        if s["runs"]:
            w("  CONTIGUOUS RUNS — >=3 adjacent untranslated keys (non-Latin locales only).")
            w("  A block shape is direct evidence a translator skipped a region.")
            for r in s["runs"]:
                w(f"    {r['file']}:{r['first_line']}-{r['last_line']}  ({r['length']} keys) "
                  f"{', '.join(r['keys'])}")
            w("")
        if s["info_latin"]:
            w(f"  INFO (--include-latin) — {sc['info_latin']} Latin-locale rows identical to base.")
            w("  Never an error: Latin identity is overwhelmingly legitimate coincidence")
            w("  ('Description' in French, 'Name' in German).")
            for r in s["info_latin"]:
                w(f"    {r['module']}/values-{r['locale']}  {r['key']} = {r['value']!r}")
            w("")
        if s["info_near_identical"]:
            w(f"  INFO (--near-identical) — {sc['info_near_identical']} rows differing only by")
            w("  case/punctuation. Low precision (German noun capitalization is correct).")
            for r in s["info_near_identical"]:
                w(f"    {r['module']}/values-{r['locale']}  {r['key']}: "
                  f"base={r['base']!r} locale={r['locale_value']!r}")
            w("")

    if h is not None:
        w("-" * 78)
        w("[H] UNEXTRACTED LITERALS — CANDIDATES, HEURISTIC, REQUIRES HUMAN TRIAGE")
        w("-" * 78)
        w("These are NOT confirmed defects. Unlike [P] and [S], which are exact set")
        w("operations over declared resources, this detector is sink-anchored regex")
        w("matching over a language with no type-level marking of user-facing strings.")
        w("")
        w(f"  {H_PRECISION_NOTE}")
        w("")
        w(f"TOTAL CANDIDATES {h['total']}   (suppressed by allowlist: {h['suppressed_total']})")
        w("")
        w("  by rule family:")
        for rule in sorted(h["by_rule"]):
            w(f"    {rule:14} {h['by_rule'][rule]:>5}")
        w("")
        w("  by module:")
        for module in sorted(h["by_module"], key=lambda m: (-h["by_module"][m], m)):
            w(f"    {module:20} {h['by_module'][module]:>5}")
        w("")
        current_file = None
        for c in h["candidates"]:
            if c["file"] != current_file:
                current_file = c["file"]
                w(f"  {current_file}")
            if c["truncated"]:
                # The capture stopped at a nested template quote; print the source line so
                # the row is greppable, and say so.
                w(f"    :{c['line']:<5} {c['rule']:14} [nested template, source line] {c['display']}")
            else:
                w(f"    :{c['line']:<5} {c['rule']:14} {c['literal']!r}")
        w("")

    adv = report["advisories"]
    w("-" * 78)
    w("ADVISORIES")
    w("-" * 78)
    if adv:
        for a in adv:
            w(f"  {a}")
    else:
        w("  none")
    w("")

    note = report["android_res"]
    if note.get("present"):
        w("-" * 78)
        w("ANDROID PLATFORM res/ (separate surface, excluded from parity math)")
        w("-" * 78)
        w(f"  {note['path']} holds {len(note['keys'])} string(s): {', '.join(note['keys'])}")
        w(f"  locale directories with strings.xml: {note['locale_dirs_with_strings'] or 'NONE'}")
        w("  Folding this into parity math would fabricate phantom 'entire locale missing'")
        w("  failures; it is reported here so the gap is still visible.")
        w("")

    w("-" * 78)
    w(f"EXIT CODE {report['exit_code']}  "
      f"(bits: 1=parity 2=stubs 4=hardcoded-candidates 8=advisories; "
      f"{EXIT_HARD_FAILURE}=hard failure, shares no bit with the finding mask {FINDING_MASK})")
    w("-" * 78)


# --------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------


class HardFailArgumentParser(argparse.ArgumentParser):
    """argparse exits 2 on a bad argument, which collides with EXIT_STUBS.

    A caller branching on the bitmask would read `--typo` as "2 stub findings". Every
    failure path in this script must land on EXIT_HARD_FAILURE, which shares no bits
    with FINDING_MASK.
    """

    def error(self, message: str) -> None:  # type: ignore[override]
        self.print_usage(sys.stderr)
        print(f"i18n-coverage: HARD FAILURE: {message}", file=sys.stderr)
        print(
            f"i18n-coverage: exiting {EXIT_HARD_FAILURE}. This is NOT a clean bill of health — the check did "
            "not run.",
            file=sys.stderr,
        )
        raise SystemExit(EXIT_HARD_FAILURE)


def build_parser() -> argparse.ArgumentParser:
    parser = HardFailArgumentParser(
        prog="i18n-coverage.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            "Deterministic i18n coverage checker for the Switchboard compose-resources\n"
            "localization surface (9 modules x 9 locales)."
        ),
        epilog="""
DETECTORS
  parity     [P] EXACT. Missing / stale keys per (module, locale); absent locale files;
                 duplicate keys within one file; unknown locale qualifiers.
  stubs      [S] EXACT. Locale values byte-identical to English in a non-Latin-script
                 locale (ASCII-only), minus mechanically auto-exempted shared literals
                 and letterless values. Tiered ERROR / REVIEW by cross-locale
                 corroboration; allowlist-suppressible.
  hardcoded  [H] HEURISTIC CANDIDATES. English literals in Kotlin/Compose that never
                 reached a strings.xml. Always labeled as candidates; ~98% precision.

EXIT CODES (bitmask, so a caller can distinguish exact findings from heuristics)
  0   clean
  1   parity findings              exact
  2   stub findings, ERROR tier    exact
  4   hardcoded candidates         heuristic
  8   advisories (stale allowlist directives, module/locale-set drift,
      parser disagreement with StringResourceParityTest's regex)
  16  HARD FAILURE — repo layout unrecognized, 0 modules discovered, a module with
      0 base keys, an unreadable/non-UTF-8/unparseable resource file, a bad CLI
      argument, a missing explicitly-passed --allowlist, or any unexpected exception.

  Test findings with `exit & 15`, and hard failure with `exit & ~15` (equivalently
  `exit >= 16`). Do NOT mask with a smaller value: `exit & 3` cannot distinguish a
  crash from findings for any hard-failure code that overlaps the low bits, which is
  exactly why the hard-failure code is 16 and not 70.

DETERMINISM
  Identical input produces byte-identical stdout on every run and every machine. No
  timestamps, hostnames, absolute paths, wall-clock, randomness, or reliance on
  set/dict iteration order. Discovery is sorted-glob based.

OUT OF SCOPE (deliberate)
  Format-specifier / placeholder-count comparison. Writing translations. Modifying any
  strings.xml. Running Gradle. Reading or regenerating
  config/l10n/strings-parity-baseline.txt (that baseline lives on the unmerged branch
  chore/review-loop-protocol and is stale).

EXAMPLES
  scripts/i18n-coverage.py                             full report, text
  scripts/i18n-coverage.py --detector parity           exact key parity only
  scripts/i18n-coverage.py --detector stubs --json     machine-readable stub findings
  scripts/i18n-coverage.py --include-latin             add Latin-locale identity INFO
  scripts/i18n-coverage.py --summary                   totals only, no per-item detail
""",
    )
    parser.add_argument(
        "--detector",
        choices=("all", "parity", "stubs", "hardcoded"),
        default="all",
        help="which detector(s) to run (default: all)",
    )
    parser.add_argument(
        "--format",
        choices=("text", "json"),
        default="text",
        help="output format (default: text)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="shorthand for --format json",
    )
    parser.add_argument(
        "--allowlist",
        default=None,
        help=f"repo-relative allowlist path (default: {ALLOWLIST_DEFAULT}). A path passed "
        "here that does not exist is a hard failure, never a silent fallback to "
        "no suppression — use --no-allowlist to disable suppression on purpose",
    )
    parser.add_argument(
        "--no-allowlist",
        action="store_true",
        help="ignore the allowlist entirely (shows the raw, unsuppressed finding set)",
    )
    parser.add_argument(
        "--include-latin",
        action="store_true",
        help="[S] also report Latin-locale values identical to base, at INFO tier "
        "(off by default: near-100%% false-positive rate)",
    )
    parser.add_argument(
        "--near-identical",
        action="store_true",
        help="[S] also report case/punctuation-only differences, at INFO tier "
        "(off by default: 1-in-3 precision)",
    )
    parser.add_argument(
        "--summary",
        action="store_true",
        help="omit per-item detail; print totals only",
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="override repo-root detection (normally found by walking up for settings.gradle.kts)",
    )
    return parser


def _force_utf8_streams() -> None:
    """Pin stdout/stderr to UTF-8.

    The report chrome and several base values contain non-ASCII (em dash, ellipsis, arrow).
    Under PYTHONUTF8=0 / PYTHONIOENCODING=ascii / an explicit non-UTF-8 locale, the writer
    raises UnicodeEncodeError mid-report, leaving truncated (and for --json, unparseable)
    output behind an exit code that looks like real findings.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


def main(argv: list[str]) -> int:
    # Everything lives inside the try, so no code path can produce a traceback + a
    # finding-bit exit code. argparse raises SystemExit, which is not an Exception and
    # therefore passes through with the code HardFailArgumentParser.error already set.
    try:
        _force_utf8_streams()
        args = build_parser().parse_args(argv)
        fmt = "json" if args.json else args.format

        root = Path(args.repo_root).resolve() if args.repo_root else find_repo_root(Path.cwd().resolve())
        if not (root / "settings.gradle.kts").is_file():
            raise HardFailure(f"{args.repo_root} is not a repo root (no settings.gradle.kts)")

        # An allowlist path that does not exist must never degrade quietly into "no
        # suppression": every suppressed row silently comes back as live debt, inflating
        # the headline number and manufacturing a regression when two dated reports are
        # diffed — behind an exit code identical to a good run. An explicit --allowlist is
        # a promise the file exists, so a typo is a hard failure. The DEFAULT path is
        # allowed to be absent (fresh checkout, or the file genuinely deleted), but says so
        # as an advisory rather than saying nothing.
        allowlist_path: Path | None = None
        allowlist_advisory: str | None = None
        if not args.no_allowlist:
            allowlist_path = (root / (args.allowlist or ALLOWLIST_DEFAULT)).resolve()
            if not allowlist_path.is_file():
                if args.allowlist is not None:
                    raise HardFailure(
                        f"--allowlist {args.allowlist} does not exist (resolved to "
                        f"{rel_or_str(allowlist_path, root)}). Refusing to run with "
                        "suppression silently disabled — pass --no-allowlist if that is "
                        "what you meant."
                    )
                allowlist_advisory = (
                    f"ALLOWLIST_ABSENT: {ALLOWLIST_DEFAULT} does not exist, so NO "
                    "suppression is in effect and previously-justified rows are reported "
                    "as live findings. Totals are not comparable with a run that had it."
                )
                allowlist_path = None

        allowlist = Allowlist(allowlist_path, root)
        allowlist_in_effect = allowlist.relpath if allowlist_path else None
        modules = discover_modules(root)

        want = args.detector
        report: dict = {
            "detector": want,
            "allowlist": allowlist_in_effect,
            "modules": [m.name for m in modules],
            "locales": sorted({loc for m in modules for loc in m.locales}),
            "android_res": android_res_note(root),
        }
        advisories: list[str] = list(allowlist.errors)
        if allowlist_advisory:
            advisories.append(allowlist_advisory)

        if want in ("all", "parity"):
            report["parity"] = run_parity(modules, root)
            advisories.extend(report["parity"]["advisories"])
        if want in ("all", "stubs"):
            report["stubs"] = run_stubs(modules, allowlist, args.include_latin, args.near_identical)
            advisories.extend(report["stubs"]["advisories"])
        if want in ("all", "hardcoded"):
            report["hardcoded"] = run_hardcoded(root, allowlist)
            advisories.extend(report["hardcoded"]["advisories"])

        # Shrink-only discipline: a directive that suppresses nothing is dead weight and
        # must be deleted, otherwise debt can silently grow back behind it. Only meaningful
        # when every detector that consumes the allowlist actually ran.
        stale_directives: list[str] = []
        if want == "all" and allowlist_in_effect:
            stale_directives = allowlist.stale()
            for line in stale_directives:
                advisories.append(
                    f"STALE_ALLOWLIST_DIRECTIVE: {line} — matched nothing. Delete it "
                    "(the allowlist is shrink-only)."
                )
        report["stale_allowlist_directives"] = stale_directives
        report["advisories"] = sorted(set(advisories))

        exit_code = 0
        if "parity" in report:
            pr = report["parity"]
            if pr["missing_total"] or pr["stale_total"] or pr["structural_total"]:
                exit_code |= EXIT_PARITY
        if "stubs" in report and report["stubs"]["errors"]:
            exit_code |= EXIT_STUBS
        if "hardcoded" in report and report["hardcoded"]["total"]:
            exit_code |= EXIT_HARDCODED
        if report["advisories"]:
            exit_code |= EXIT_ADVISORY
        report["exit_code"] = exit_code

        # --summary drops per-item detail only. Every printed total comes from a stored
        # count, never from len() of a list that this block clears.
        if args.summary:
            if "parity" in report:
                for row in report["parity"]["per_module"]:
                    row["distinct_missing_keys"] = []
                    row["distinct_stale_keys"] = []
                    row["missing_by_locale"] = {}
                    row["stale_by_locale"] = {}
                # Structural rows survive --summary: ABSENT_LOCALE_FILE and DUPLICATE_KEY are
                # the highest-signal, lowest-volume findings the tool produces, and clearing
                # them leaves the renderer printing a structural count with no rows.
                report["parity"]["findings"] = [
                    f for f in report["parity"]["findings"]
                    if f["kind"] in STRUCTURAL_KINDS
                ]
            if "stubs" in report:
                for key in ("errors", "review", "suppressed", "auto_exempt", "runs",
                            "info_latin", "info_near_identical"):
                    report["stubs"][key] = []
            if "hardcoded" in report:
                report["hardcoded"]["candidates"] = []
                report["hardcoded"]["suppressed"] = []

        if fmt == "json":
            json.dump(report, sys.stdout, indent=2, sort_keys=True, ensure_ascii=False)
            sys.stdout.write("\n")
        else:
            render_text(report, sys.stdout)
        return exit_code

    except BrokenPipeError:
        # `… | head` closes the pipe mid-report. The run itself succeeded and its findings
        # were computed; reporting a HARD FAILURE for a truncated *display* would be the
        # same lie in the opposite direction. Exit 0 is wrong too (findings existed), so
        # report the real bit that says "output was cut off": an advisory. stdout is
        # redirected to devnull first, or the interpreter re-raises on the final flush.
        devnull = os.open(os.devnull, os.O_WRONLY)
        os.dup2(devnull, sys.stdout.fileno())
        print(
            "i18n-coverage: stdout closed early (broken pipe). The check RAN; the report "
            "was truncated for display only. Exiting with the advisory bit.",
            file=sys.stderr,
        )
        return EXIT_ADVISORY
    except HardFailure as exc:
        print(f"i18n-coverage: HARD FAILURE: {exc}", file=sys.stderr)
        print(
            f"i18n-coverage: exiting {EXIT_HARD_FAILURE}. This is NOT a clean bill of health — the check did "
            "not run.",
            file=sys.stderr,
        )
        return EXIT_HARD_FAILURE
    except Exception as exc:  # noqa: BLE001 — deliberate catch-all
        # Anything unforeseen must land on EXIT_HARD_FAILURE, never on a finding bit, and
        # must never leak a machine-absolute path via a traceback: a caller branching on the
        # bitmask would otherwise read a crash as real findings over truncated output.
        print(
            f"i18n-coverage: HARD FAILURE: unexpected {type(exc).__name__}: {exc}",
            file=sys.stderr,
        )
        print(
            f"i18n-coverage: exiting {EXIT_HARD_FAILURE}. This is NOT a clean bill of health — the check did "
            "not run. Re-run with I18N_COVERAGE_TRACEBACK=1 for a traceback.",
            file=sys.stderr,
        )
        if os.environ.get("I18N_COVERAGE_TRACEBACK"):
            traceback.print_exc()
        return EXIT_HARD_FAILURE


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
