#!/usr/bin/env bash
#
# Bump the app version in version.properties (the single source of truth).
#
# Versions are calendar-based: YYYY.MM.PATCH (month zero-padded, PATCH 0-99).
# Year and month come from the current UTC date — CI runners are UTC — so the
# only choice a release makes is patch-level vs release-candidate.
#
# Usage:
#   scripts/bump-version.sh <bump>
#
# Stable bumps (clear any pre-release suffix):
#   patch     new month        -> YYYY.MM.0
#             same month       -> YYYY.MM.(PATCH+1)
#
# Pre-release bumps (append/advance an -rcN suffix):
#   prepatch  like patch, but emits YYYY.MM.P-rc1
#   rc        x.y.z-rcN        -> x.y.z-rc(N+1)   (requires an existing -rcN)
#   finalize  x.y.z-rcN        -> x.y.z           (promote a candidate to stable)
#
# rc/finalize never touch the version core: a candidate started in June and
# finalized in July still ships as 2026.06.x — the date reflects when the
# release train started.
#
# A version with an -rcN suffix is treated as a pre-release: the release workflow
# marks the GitHub Release as a pre-release so Obtainium ignores it unless the user
# opts in. versionCode is NOT stored here — it is derived from versionName at build
# time by the Android convention plugin (YEAR*10000 + MONTH*100 + PATCH), which
# strips the suffix, so an -rcN and its final release share a versionCode.
#
# Set BUMP_DATE=YYYY-MM to override the current date (for testing).
#
# Output:
#   - prints the new version name to stdout
#   - when running in GitHub Actions, also writes version_name and tag -> $GITHUB_OUTPUT
#
set -euo pipefail

BUMP="${1:-}"
case "$BUMP" in
  patch|prepatch|rc|finalize) ;;
  *)
    echo "Usage: $0 <patch|prepatch|rc|finalize>" >&2
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="$SCRIPT_DIR/../version.properties"

if [[ ! -f "$PROPS" ]]; then
  echo "version.properties not found at $PROPS" >&2
  exit 1
fi

current_name="$( (grep -m1 -E '^versionName=' "$PROPS" || true) | cut -d'=' -f2 | tr -d '[:space:]')"

if [[ -z "$current_name" ]]; then
  echo "versionName missing or malformed in $PROPS" >&2
  exit 1
fi

# Current UTC year/month, overridable for tests via BUMP_DATE=YYYY-MM.
if [[ -n "${BUMP_DATE:-}" ]]; then
  if [[ ! "$BUMP_DATE" =~ ^[0-9]{4}-[0-9]{2}$ ]]; then
    echo "BUMP_DATE must be YYYY-MM (got '$BUMP_DATE')" >&2
    exit 1
  fi
  now_year="${BUMP_DATE%-*}"
  now_month="${BUMP_DATE#*-}"
else
  now_year="$(date -u +%Y)"
  now_month="$(date -u +%m)"
fi

# Parse the version core (everything before the first '-').
core_name="${current_name%%-*}"
IFS='.' read -r cur_year cur_month cur_patch <<< "$core_name"
cur_year="${cur_year:-0}"; cur_month="${cur_month:-0}"; cur_patch="${cur_patch:-0}"

# Extract N from a current -rcN suffix, if any (else empty -> not a pre-release).
rc=""
if [[ "$current_name" == *-rc* ]]; then
  rc="${current_name##*-rc}"
fi

# Compare numerically (10# guards against zero-padded months being read as octal).
same_month=false
if [[ "$cur_year" =~ ^[0-9]+$ && "$cur_month" =~ ^[0-9]+$ ]]; then
  if (( 10#$cur_year == 10#$now_year && 10#$cur_month == 10#$now_month )); then
    same_month=true
  elif (( 10#$cur_year * 100 + 10#$cur_month > 10#$now_year * 100 + 10#$now_month )); then
    # A stored version ahead of today (clock skew, BUMP_DATE typo, bad hand edit)
    # would regress the derived versionCode and break updates — refuse.
    echo "stored version '$current_name' is ahead of the current date ${now_year}-${now_month}; fix the clock (or BUMP_DATE) or version.properties" >&2
    exit 1
  fi
fi

new_core=""  # set by patch/prepatch; rc/finalize keep the current core
pre=""       # set to "-rcN" to emit a pre-release version
case "$BUMP" in
  patch|prepatch)
    if $same_month; then
      if [[ ! "$cur_patch" =~ ^[0-9]+$ ]]; then
        echo "current patch component '$cur_patch' is not numeric in '$current_name'" >&2
        exit 1
      fi
      new_patch=$((10#$cur_patch + 1))
      if (( new_patch > 99 )); then
        echo "patch component would exceed 99 ('$current_name'); the YYYYMMPP versionCode scheme caps PATCH at 99" >&2
        exit 1
      fi
      new_core="${now_year}.${now_month}.${new_patch}"
    else
      new_core="${now_year}.${now_month}.0"
    fi
    if [[ "$BUMP" == "prepatch" ]]; then
      pre="-rc1"
    fi
    ;;
  rc)
    if [[ -z "$rc" || ! "$rc" =~ ^[0-9]+$ ]]; then
      echo "'rc' requires a current -rcN pre-release (got '$current_name'); start one with prepatch" >&2
      exit 1
    fi
    new_core="$core_name"
    pre="-rc$((10#$rc + 1))"   # core stays put; only the candidate number advances
    ;;
  finalize)
    if [[ -z "$rc" || ! "$rc" =~ ^[0-9]+$ ]]; then
      echo "'finalize' requires a current -rcN pre-release to promote (got '$current_name')" >&2
      exit 1
    fi
    new_core="$core_name"   # core stays put; pre cleared -> stable release
    ;;
esac

new_name="${new_core}${pre}"
tag="v${new_name}"

# Rewrite version.properties, preserving the header comments (and, by writing
# through the original file rather than mv-ing a mktemp over it, its file mode).
tmp="$(mktemp)"
while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ "$line" == versionName=* ]]; then
    printf '%s\n' "versionName=${new_name}"
  else
    printf '%s\n' "$line"
  fi
done < "$PROPS" > "$tmp"
cat "$tmp" > "$PROPS"
rm -f "$tmp"

echo "$new_name"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "version_name=${new_name}"
    echo "tag=${tag}"
  } >> "$GITHUB_OUTPUT"
fi
