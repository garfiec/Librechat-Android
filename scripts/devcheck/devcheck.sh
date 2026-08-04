#!/usr/bin/env bash
# Replayable device checks for the session-expiry navigation fixes.
#
# The app data dir is snapshotted while the session is dead, then restored before each run, so the
# same expired-refresh-token cold start can be replayed as many times as needed.
#
# NEVER uninstall the app: the AndroidKeyStore master key backing EncryptedSharedPreferences is
# dropped on uninstall, which makes a restored snapshot undecryptable. Update with `adb install -r`.
#
# Set ANDROID_SERIAL when more than one device is attached; every adb call below honors it.
set -euo pipefail

PKG=com.garfiec.librechat
STATE_DIR="${DEVCHECK_STATE_DIR:-${TMPDIR:-/tmp}/switchboard-devcheck}"
SNAPSHOT="$STATE_DIR/broken-session.tar"
REMOTE_TAR=/data/local/tmp/devcheck-restore.tar

mkdir -p "$STATE_DIR"

die() { echo "devcheck: $*" >&2; exit 1; }

cmd_snapshot() {
  adb shell am force-stop "$PKG"
  adb exec-out run-as "$PKG" tar cf - shared_prefs databases files no_backup > "$SNAPSHOT"
  echo "snapshot -> $SNAPSHOT ($(tar tf "$SNAPSHOT" | wc -l | tr -d ' ') entries)"
}

cmd_restore() {
  [ -f "$SNAPSHOT" ] || die "no snapshot at $SNAPSHOT"
  adb shell am force-stop "$PKG"
  # Pushed to a world-readable path rather than piped over stdin: adb's pty mangles binary input.
  adb push "$SNAPSHOT" "$REMOTE_TAR" >/dev/null
  adb shell chmod 644 "$REMOTE_TAR"
  adb shell "run-as $PKG sh -c 'rm -rf shared_prefs databases files && tar xf $REMOTE_TAR'"
  adb shell rm -f "$REMOTE_TAR"
  echo "restored $(adb shell run-as "$PKG" ls shared_prefs | tr -d '\r' | wc -l | tr -d ' ') shared_prefs files"
}

# Number of stored <string> rows in the encrypted token store. Two of them are Tink keysets
# (key + value); every persisted token adds one more. Keys are encrypted too, so the count is the
# only thing readable from outside the app.
cmd_tokens() {
  adb shell run-as "$PKG" cat shared_prefs/librechat_tokens.xml 2>/dev/null |
    grep -c '<string name=' || echo 0
}

# Named explicitly: the debug build ships LeakCanary, which registers a second LAUNCHER activity,
# so `monkey -c android.intent.category.LAUNCHER` opens the leak list instead of the app.
launch() {
  # Always force-stop first: `am start` onto a live task just resumes it, so nothing re-composes and
  # no cold-start work runs — a "clean" capture that only proves the app was already open.
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
}

capture() {
  local label="$1"
  adb logcat -d -v threadtime > "$STATE_DIR/$label.log"
  adb shell uiautomator dump /sdcard/devcheck-ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/devcheck-ui.xml "$STATE_DIR/$label.ui.xml" >/dev/null 2>&1 || true
  echo "captured -> $STATE_DIR/$label.log"
}

# Reads a uiautomator dump on stdin, prints "x1 y1 x2 y2" for the first node with the given text.
boundsOf() {
  grep "text=\"$1\"" |
    sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/p' | head -1
}

tapBounds() {
  # shellcheck disable=SC2086
  set -- $1 "$2"
  local x=$((($1 + $3) / 2)) y=$((($2 + $4) / 2))
  adb shell input tap "$x" "$y"
  echo "tapped $5 at $x,$y (t+${SECONDS}s)"
}

cmd_run() {
  local label="${1:?usage: run <label> [seconds]}" secs="${2:-15}"
  echo "tokens before: $(cmd_tokens)"
  adb logcat -c
  launch
  sleep "$secs"
  capture "$label"
  echo "tokens after:  $(cmd_tokens)"
}

# Launch, then tap Connect as soon as the ServerUrl screen paints — this races the tail of the 401
# storm, which is what used to yank the user off Login back to ServerUrl.
cmd_tap_connect() {
  local label="${1:?usage: tap-connect <label> [seconds]}" secs="${2:-20}"
  echo "tokens before: $(cmd_tokens)"
  adb logcat -c
  launch
  local deadline=$((SECONDS + 15)) bounds=""
  while [ $SECONDS -lt $deadline ]; do
    adb shell uiautomator dump /sdcard/devcheck-poll.xml >/dev/null 2>&1 || true
    local dump
    dump=$(adb shell cat /sdcard/devcheck-poll.xml 2>/dev/null | tr '>' '\n' || true)
    # The signed-out notice covers the screen; clear it before reaching for Connect.
    local dismiss
    dismiss=$(printf '%s\n' "$dump" | boundsOf 'Dismiss' || true)
    if [ -n "$dismiss" ]; then tapBounds "$dismiss" "Dismiss"; continue; fi
    bounds=$(printf '%s\n' "$dump" | boundsOf 'Connect' || true)
    [ -n "$bounds" ] && break
  done
  if [ -n "$bounds" ]; then
    tapBounds "$bounds" "Connect"
  else
    echo "WARN: Connect button never appeared" >&2
  fi
  sleep "$secs"
  capture "$label"
  echo "tokens after:  $(cmd_tokens)"
}

cmd_assert() {
  local label="${1:?usage: assert <label>}"
  local log="$STATE_DIR/$label.log"
  [ -f "$log" ] || die "no capture at $log"
  echo "=== $label ==="
  printf '%-42s %s\n' \
    "401 received (refresh legs entered)" "$(grep -c '401 received, attempting token refresh' "$log" || true)" \
    "refresh_rejected (refresh POSTs)"    "$(grep -c 'refresh_rejected' "$log" || true)" \
    "Token refresh failed (settled)"      "$(grep -c 'Token refresh failed - session expired' "$log" || true)" \
    "session_torn_down"                   "$(grep -c 'session_torn_down' "$log" || true)" \
    "401 after retry"                     "$(grep -c '401 after retry' "$log" || true)"
  echo "screen sequence:"
  { grep -o '"screen":"[A-Za-z]*"' "$log" || true; } |
    sed 's/.*:"//;s/"//' | awk 'NR==1||$0!=p{print "  "NR": "$0}{p=$0}'
}

case "${1:-}" in
  snapshot)    shift; cmd_snapshot "$@" ;;
  restore)     shift; cmd_restore "$@" ;;
  run)         shift; cmd_run "$@" ;;
  tap-connect) shift; cmd_tap_connect "$@" ;;
  assert)      shift; cmd_assert "$@" ;;
  tokens)      shift; cmd_tokens "$@" ;;
  *) die "usage: devcheck.sh {snapshot|restore|run <label> [s]|tap-connect <label> [s]|assert <label>|tokens}" ;;
esac
