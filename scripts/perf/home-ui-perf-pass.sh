#!/usr/bin/env bash
set -euo pipefail

DEVICE="${1:-192.168.50.71:5555}"
PACKAGE="${2:-com.nexio.tv.profileable}"
ACTIVITY="${3:-com.nexio.tv.MainActivity}"
OUT_DIR="${4:-/tmp/nexio-home-perf}"

mkdir -p "$OUT_DIR"

adb -s "$DEVICE" shell am start -W -n "$PACKAGE/$ACTIVITY" > "$OUT_DIR/launch.txt"
adb -s "$DEVICE" shell input keyevent 4 || true
sleep 2

run_case() {
  local name="$1"
  local body="$2"

  adb -s "$DEVICE" shell dumpsys gfxinfo "$PACKAGE" reset > "$OUT_DIR/${name}_reset.txt"
  adb -s "$DEVICE" shell logcat -c
  adb -s "$DEVICE" shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/${name}_mem_before.txt"

  adb -s "$DEVICE" shell "$body"
  sleep 2

  adb -s "$DEVICE" shell dumpsys gfxinfo "$PACKAGE" > "$OUT_DIR/${name}_gfxinfo.txt"
  adb -s "$DEVICE" shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/${name}_mem_after.txt"
  adb -s "$DEVICE" shell logcat -d -v time > "$OUT_DIR/${name}_logcat.txt"

  {
    echo "== $name gfx =="
    grep -E "Total frames|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed|Number Slow|Frame deadline" "$OUT_DIR/${name}_gfxinfo.txt" || true
    echo
    echo "== $name memory =="
    grep -E "Native Heap|Dalvik Heap|GL mtrack|Java Heap|Graphics:|TOTAL PSS|TOTAL RSS|TOTAL SWAP PSS" "$OUT_DIR/${name}_mem_before.txt" "$OUT_DIR/${name}_mem_after.txt" || true
    echo
    echo "== $name jank and gc =="
    grep -Ei "JankStats|GC freed|Choreographer|TvMetadataRouter|TvdbReliability" "$OUT_DIR/${name}_logcat.txt" | tail -n 120 || true
  } > "$OUT_DIR/${name}_summary.txt"
}

run_case "horizontal" "for i in \$(seq 1 18); do input keyevent 22; sleep 0.12; done; for i in \$(seq 1 18); do input keyevent 21; sleep 0.12; done"
run_case "vertical" "for i in \$(seq 1 10); do input keyevent 20; sleep 0.14; done; for i in \$(seq 1 10); do input keyevent 19; sleep 0.14; done"

echo "Wrote summaries to $OUT_DIR"
echo "$OUT_DIR/horizontal_summary.txt"
echo "$OUT_DIR/vertical_summary.txt"
