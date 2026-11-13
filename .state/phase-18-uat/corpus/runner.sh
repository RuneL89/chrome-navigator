#!/usr/bin/env bash
# PHASE_18 Stage 18.E item 3 — the acceptance corpus runner (gate 18.E4).
# One attended run: set the engine policy, pre-load the webview shell on the goal's
# URL, type the goal into the overlay, submit, wait for the run's terminal line in
# the on-device evidence record, and pull the record into the corpus dir.
#
# Usage: runner.sh <policy-serial> <run-label> <goal> <fixture-url>
#   policy-serial: always-vision | tree-only | tree-first
set -u
ADB="C:/Users/atavi/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
OUT="$(cd "$(dirname "$0")" && pwd)"
POLICY="$1"; LABEL="$2"; GOAL="$3"; URL="$4"
EV="/storage/emulated/0/Android/data/navigator.app/files/run-reports/vision-exp"

fail() { echo "[corpus] $LABEL ABORT: $1"; exit 2; }

echo "[corpus] $LABEL policy=$POLICY"
# The overlay service is NOT exported: `am start-foreground-service` needs adbd root
# (a framework restart earlier downgraded it — the silent overlay failure that broke
# a corpus attempt). Ensure root before anything else.
"$ADB" root >/dev/null 2>&1
"$ADB" wait-for-device
"$ADB" shell am broadcast -a navigator.app.action.SET_ENGINE_POLICY --es policy "$POLICY" \
  -n navigator.app/.overlay.OverlayDebugReceiver >/dev/null
sleep 1
# The emulator hosts a pre-existing demo app (com.atavi.satu) registered as a HOME
# app; a run against it is lawfully refused (WRONG_WINDOW / OFF_PAGE) but is not a
# corpus run. Force-stop it and put the shell front BEFORE the overlay opens.
# (No HOME keyevent: HOME is what surfaces that app.)
"$ADB" shell am force-stop com.atavi.satu >/dev/null 2>&1
"$ADB" shell am force-stop com.atavi.satu.test >/dev/null 2>&1
# A fresh shell process: a force-stopped-then-restored WebView task can render a WHITE
# page (the 2026-11-13 corpus noise) — always cold-start it on the goal's URL.
"$ADB" shell am force-stop org.chromium.webview_shell >/dev/null 2>&1
sleep 1
"$ADB" shell am start -a android.intent.action.VIEW -d "$URL" org.chromium.webview_shell >/dev/null
sleep 2
SVC=$("$ADB" shell am start-foreground-service -n navigator.app/.overlay.NavigatorService \
  -a navigator.app.action.SHOW_GOAL_INPUT 2>&1)
case "$SVC" in
  *Error*) fail "the goal-input service refused: $SVC" ;;
esac
sleep 2
"$ADB" shell input tap 450 1045 >/dev/null
sleep 1
# Clear any retained text, then type per character (the proven injection method;
# spaces ride `input text %s`; MSYS path conversion disabled above).
"$ADB" shell input keycombination 113 29 >/dev/null
sleep 0.4
"$ADB" shell input keyevent 67 >/dev/null
sleep 0.4
SENT=$(printf '%s' "$GOAL" | sed 's/ /~/g')
for c in $(echo "$SENT" | fold -w1); do
  case "$c" in
    "~") "$ADB" shell input text "%s" >/dev/null ;;
    *)   "$ADB" shell input text "$c" >/dev/null ;;
  esac
  sleep 0.06
done
sleep 1
"$ADB" exec-out screencap -p > "$OUT/$LABEL-typed.png"
# Capture the last completed run BEFORE submitting (the run's own dir must differ),
# plus the submit epoch (the task dir's suffix must be newer — a stale-log guard for
# a transient adb outage that would otherwise "find" the previous run's terminal).
BEFORE=$("$ADB" shell "ls -t $EV 2>/dev/null | head -1" | tr -d '\r')
SUBMIT_MS=$(( $(date +%s) * 1000 ))
"$ADB" shell input tap 331 1495 >/dev/null
echo "[corpus] submitted; confirming the run started"
# The run must START within ~40s (a new task dir). A miss = the overlay/submit did
# not take (the typed-goal screenshot is kept for diagnosis) — abort loudly.
NEW=""
for i in $(seq 1 8); do
  sleep 5
  NEW=$("$ADB" shell "ls -t $EV 2>/dev/null | head -1" | tr -d '\r')
  TS=${NEW#task-}
  if [ -n "$NEW" ] && [ "$NEW" != "$BEFORE" ] && [ "${TS:-0}" -ge "$(( SUBMIT_MS - 60000 ))" ] 2>/dev/null; then
    break
  fi
  NEW=""
done
[ -z "$NEW" ] && fail "no run started (see $LABEL-typed.png)"
echo "[corpus] run $NEW started; waiting for the terminal line"
mkdir -p "$OUT/$LABEL"
# Poll the run's events.log for the terminal line (up to ~9 minutes); keep periodic
# progress screenshots — the host's per-step lines ride only the corner log, so the
# screenshots are the diagnosis record for the hybrid arm.
N=0
for i in $(seq 1 54); do
  T=$("$ADB" shell "grep -c 'terminal=' $EV/$NEW/events.log 2>/dev/null" | tr -d '\r')
  if [ "${T:-0}" -ge 1 ]; then
    mkdir -p "$OUT/$LABEL"
    "$ADB" shell "cat $EV/$NEW/events.log" > "$OUT/$LABEL/events.log"
    "$ADB" exec-out screencap -p > "$OUT/$LABEL/final.png"
    echo "[corpus] $LABEL terminal: $(grep 'terminal=' "$OUT/$LABEL/events.log" | tail -1)"
    exit 0
  fi
  N=$((N + 1))
  if [ $((N % 3)) -eq 0 ]; then
    "$ADB" exec-out screencap -p > "$OUT/$LABEL/progress-$N.png"
  fi
  sleep 10
done
# Timeout: still pull whatever exists for the honest record.
mkdir -p "$OUT/$LABEL"
"$ADB" shell "cat $EV/$NEW/events.log" > "$OUT/$LABEL/events.log" 2>/dev/null
"$ADB" exec-out screencap -p > "$OUT/$LABEL/final.png"
echo "[corpus] $LABEL TIMEOUT (no terminal line within 9 min)"
exit 1
