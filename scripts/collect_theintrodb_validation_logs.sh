#!/usr/bin/env bash

# collect_theintrodb_validation_logs.sh
# Capture focused logs and a UI snapshot while manually validating TheIntroDB behavior.
#
# Usage:
#   ./collect_theintrodb_validation_logs.sh [ADB_SERIAL_OR_IP[:PORT]] [--package com.nexiodebug.tv] [--wait-sec 45]
#
# Flow:
#   1) connect/wait for device
#   2) clear logcat
#   3) launch app
#   4) wait N seconds while you reproduce the TIDB scenario
#   5) dump full + filtered logcat
#   6) capture UIAutomator XML + screenshot

set -euo pipefail

ADB_TARGET=""
PACKAGE_NAME="com.nexiodebug.tv"
WAIT_SEC=45

while [ "$#" -gt 0 ]; do
    case "$1" in
        --package)
            PACKAGE_NAME="${2:-}"
            [ -n "$PACKAGE_NAME" ] || { echo "--package requires a value"; exit 1; }
            shift 2
            ;;
        --wait-sec)
            WAIT_SEC="${2:-}"
            [[ "$WAIT_SEC" =~ ^[0-9]+$ ]] || { echo "--wait-sec must be an integer"; exit 1; }
            shift 2
            ;;
        *)
            if [ -z "$ADB_TARGET" ]; then
                ADB_TARGET="$1"
                shift
            else
                echo "Unexpected argument: $1"
                exit 1
            fi
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/theintrodb_validation_logs_$(date +%Y%m%d_%H%M%S)"
FULL_LOG_FILE="$OUTPUT_DIR/theintrodb_logcat_full.txt"
FILTERED_LOG_FILE="$OUTPUT_DIR/theintrodb_logcat_filtered.txt"
UI_XML_FILE="$OUTPUT_DIR/window_dump.xml"
SCREENSHOT_FILE="$OUTPUT_DIR/screen.png"
REPORT_FILE="$OUTPUT_DIR/theintrodb_report.txt"

mkdir -p "$OUTPUT_DIR"

ADB_CMD=(adb)
CONNECTED_REMOTE=0
if [ -n "$ADB_TARGET" ]; then
    if [[ "$ADB_TARGET" == *.*:* || "$ADB_TARGET" == *.*.*.* ]]; then
        echo "Connecting to remote ADB target: $ADB_TARGET"
        adb connect "$ADB_TARGET" >/dev/null || true
        CONNECTED_REMOTE=1
    fi
    ADB_CMD=(adb -s "$ADB_TARGET")
fi

echo "Waiting for device..."
"${ADB_CMD[@]}" wait-for-device
echo "Device ready."

echo "Clearing logcat..."
"${ADB_CMD[@]}" logcat -c || true

echo "Force-stopping package: $PACKAGE_NAME"
"${ADB_CMD[@]}" shell am force-stop "$PACKAGE_NAME" || true
sleep 1

echo "Launching package: $PACKAGE_NAME"
"${ADB_CMD[@]}" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || \
    "${ADB_CMD[@]}" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

echo "Now reproduce the TheIntroDB scenario. Waiting ${WAIT_SEC}s..."
sleep "$WAIT_SEC"

echo "Dumping logcat..."
"${ADB_CMD[@]}" logcat -d -v threadtime > "$FULL_LOG_FILE"

rg -i \
    "TheIntroDB|theintrodb|SkipIntro|skip intro|skip recap|skip credits|skip preview|PlayerViewModel|showNextEpisodeCard|activeSkipInterval|fetchSkipIntervals|OnPlayNextEpisode|next episode" \
    "$FULL_LOG_FILE" > "$FILTERED_LOG_FILE" || true

echo "Capturing UI dump and screenshot..."
"${ADB_CMD[@]}" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
"${ADB_CMD[@]}" pull /sdcard/window_dump.xml "$UI_XML_FILE" >/dev/null 2>&1 || true
"${ADB_CMD[@]}" shell screencap -p /sdcard/theintrodb_validation.png >/dev/null 2>&1 || true
"${ADB_CMD[@]}" pull /sdcard/theintrodb_validation.png "$SCREENSHOT_FILE" >/dev/null 2>&1 || true

python3 - "$FULL_LOG_FILE" "$FILTERED_LOG_FILE" "$REPORT_FILE" <<'PY'
import re
import sys
from pathlib import Path

full_log = Path(sys.argv[1]).read_text(errors="ignore").splitlines()
filtered_path = Path(sys.argv[2])
report_path = Path(sys.argv[3])

def count(pattern: str) -> int:
    rx = re.compile(pattern, re.I)
    return sum(1 for line in full_log if rx.search(line))

def first(pattern: str):
    rx = re.compile(pattern, re.I)
    for line in full_log:
        if rx.search(line):
            return line
    return "NOT FOUND"

out = []
out.append("TheIntroDB Validation Report")
out.append("=" * 32)
out.append(f"Filtered log: {filtered_path}")
out.append("")
out.append(f"theintrodb_lines: {count(r'theintrodb')}")
out.append(f"skip_cta_lines: {count(r'skip intro|skip recap|skip credits|skip preview')}")
out.append(f"play_next_lines: {count(r'OnPlayNextEpisode|showNextEpisodeCard|next episode')}")
out.append("")
out.append("First matches")
out.append("-" * 32)
out.append(f"TheIntroDB: {first(r'theintrodb')}")
out.append(f"skip CTA: {first(r'skip intro|skip recap|skip credits|skip preview')}")
out.append(f"play next: {first(r'OnPlayNextEpisode|showNextEpisodeCard|next episode')}")

report_path.write_text("\n".join(out) + "\n")
print("\n".join(out))
PY

if [ "$CONNECTED_REMOTE" -eq 1 ] && [ -n "$ADB_TARGET" ]; then
    echo "Disconnecting $ADB_TARGET"
    adb disconnect "$ADB_TARGET" >/dev/null || true
fi

echo
echo "Done."
echo "Full log:     $FULL_LOG_FILE"
echo "Filtered log: $FILTERED_LOG_FILE"
echo "UI dump:      $UI_XML_FILE"
echo "Screenshot:   $SCREENSHOT_FILE"
echo "Report:       $REPORT_FILE"
