#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_ROOT="$ROOT/mpv-android/app/src/main/libs"
DEST_ROOT="$ROOT/third_party/libmpv/jniLibs"

if [[ ! -d "$SOURCE_ROOT" ]]; then
  echo "mpv runtime libs not found at $SOURCE_ROOT" >&2
  exit 1
fi

mkdir -p "$DEST_ROOT"

shopt -s nullglob
found=0
for abi_dir in "$SOURCE_ROOT"/*; do
  [[ -d "$abi_dir" ]] || continue
  abi="$(basename "$abi_dir")"
  libs=("$abi_dir"/*.so)
  (( ${#libs[@]} > 0 )) || continue
  found=1
  mkdir -p "$DEST_ROOT/$abi"
  rm -f "$DEST_ROOT/$abi"/*.so
  cp "${libs[@]}" "$DEST_ROOT/$abi/"
  echo "Synced $abi (${#libs[@]} libs)"
done

if [[ "$found" -eq 0 ]]; then
  echo "No built mpv shared libraries found under $SOURCE_ROOT" >&2
  exit 1
fi
