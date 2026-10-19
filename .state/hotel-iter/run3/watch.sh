#!/bin/bash
# Run-3 terminal watcher: polls the Navigator FGS notification state and the
# log.db terminal-name delta. On terminal (or 20-min timeout) it stops the
# device-side screenshot timer and exits — which re-invokes the agent.
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="adb-R5CY601ENFY-HYRGVl._adb-tls-connect._tcp"
TERM_RE='GOAL_ACHIEVED|BUDGET_EXHAUSTED|STUCK_KILL|GUARD_ABORT|USER_CANCEL|LADDER_REFUSED|[^_]REFUSED'
base=""
started=0
for i in $(seq 1 400); do
  d=$("$ADB" -s "$S" shell "dumpsys notification --noredact" 2>/dev/null | tr -d '\r')
  w=$(printf '%s' "$d" | grep -c "Navigator working")
  r=$(printf '%s' "$d" | grep -c "Navigator ready")
  [ "$w" -ge 1 ] && started=1
  c=$("$ADB" -s "$S" exec-out run-as navigator.app sh -c 'cat databases/log.db databases/log.db-wal 2>/dev/null' 2>/dev/null | grep -acE "$TERM_RE")
  c=${c:-0}
  [ -z "$base" ] && base=$c
  echo "$(date +%H:%M:%S) working=$w ready=$r started=$started terms=$c base=$base"
  if [ "$started" = 1 ] && [ "$w" = 0 ] && [ "$r" -ge 1 ]; then
    sleep 4
    echo "TERMINAL-VIA-NOTIFICATION: working=$w ready=$r"
    "$ADB" -s "$S" shell "touch /data/local/tmp/navcap-stop"
    exit 0
  fi
  if [ "$started" = 1 ] && [ "$c" -gt "$base" ] 2>/dev/null; then
    sleep 4
    echo "TERMINAL-VIA-DB: terms=$c base=$base"
    "$ADB" -s "$S" shell "touch /data/local/tmp/navcap-stop"
    exit 0
  fi
  sleep 3
done
echo "WATCHER-TIMEOUT"
"$ADB" -s "$S" shell "touch /data/local/tmp/navcap-stop"
exit 1
