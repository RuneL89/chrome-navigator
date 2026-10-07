#!/bin/bash
# Card watcher: polls logcat for the harness card line; on sight captures dump +
# screenshot and taps KEEP GOING from the dump bounds within seconds.
ADB="C:/Users/atavi/AppData/Local/Android/Sdk/platform-tools/adb.exe"
EVID="C:/Users/atavi/Documents/projects/chrome navigator/.state/phase-08J-cardfix-smoke"
while true; do
  L=$("$ADB" -s emulator-5554 logcat -d -s NavigatorHarness 2>/dev/null)
  if echo "$L" | grep -q "card:"; then
    echo "[$(date +%H:%M:%S)] CARD LINE SEEN" >> "$EVID/watcher.log"
    "$ADB" -s emulator-5554 logcat -d -s NavigatorHarness > "$EVID/logcat-at-card.txt"
    "$ADB" -s emulator-5554 exec-out screencap -p > "$EVID/card-visible-screenshot.png" 2>/dev/null
    "$ADB" -s emulator-5554 exec-out uiautomator dump /dev/tty > "$EVID/card-visible-uiautomator-dump.xml" 2>/dev/null
    grep -o 'text="[^"]*"' "$EVID/card-visible-uiautomator-dump.xml" | grep -v 'text=""' | head -8 >> "$EVID/watcher.log"
    B=$(grep -oE 'text="KEEP GOING"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$EVID/card-visible-uiautomator-dump.xml" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
    if [ -n "$B" ]; then
      X1=$(echo "$B" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
      Y1=$(echo "$B" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
      X2=$(echo "$B" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
      Y2=$(echo "$B" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
      X=$(( (X1 + X2) / 2 )); Y=$(( (Y1 + Y2) / 2 ))
      echo "[$(date +%H:%M:%S)] tapping KEEP GOING at ($X,$Y)" >> "$EVID/watcher.log"
      "$ADB" -s emulator-5554 shell input tap "$X" "$Y"
      sleep 3
      "$ADB" -s emulator-5554 logcat -d -s NavigatorHarness > "$EVID/logcat-after-tap.txt"
    else
      echo "[$(date +%H:%M:%S)] NO KEEP GOING bounds in dump" >> "$EVID/watcher.log"
    fi
    break
  fi
  sleep 2
done
