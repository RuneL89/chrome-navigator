#!/system/bin/sh
# Navigator run capture: ~1 screenshot/sec ON-DEVICE until a stop-file appears
# (or a 20-minute safety cap). JPEG to keep storage light (~150-300KB/frame).
# Start:  adb shell "nohup sh /data/local/tmp/navcap.sh >/dev/null 2>&1 &"
# Stop:   adb shell touch /data/local/tmp/navcap-stop
DIR=/data/local/tmp/navcap
STOP=/data/local/tmp/navcap-stop
END=$(( $(date +%s) + 1200 ))
i=0
while [ ! -f "$STOP" ] && [ "$(date +%s)" -lt "$END" ]; do
  i=$((i+1))
  screencap -j "$DIR/s-$(date +%s)-$(printf '%04d' $i).jpg"
  sleep 0.3
done
echo "captured $i" > "$DIR/done.txt"
