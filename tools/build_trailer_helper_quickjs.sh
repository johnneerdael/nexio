#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_ROOT="$ROOT_DIR/app/src/main/assets/trailer-helper/runtime"
WORK_DIR="${TMPDIR:-/tmp}/nexio-quickjs-build"
QUICKJS_ARCHIVE="quickjs-2025-09-13-2.tar.xz"
QUICKJS_URL="https://bellard.org/quickjs/${QUICKJS_ARCHIVE}"
QUICKJS_SOURCE_DIR="$WORK_DIR/quickjs-2025-09-13"
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}}"
TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin"

if [[ ! -d "$TOOLCHAIN_BIN" ]]; then
  echo "Android NDK toolchain not found at $TOOLCHAIN_BIN" >&2
  exit 1
fi

SDKROOT_VALUE=""
if command -v xcrun >/dev/null 2>&1; then
  SDKROOT_VALUE="$(xcrun --show-sdk-path)"
fi

mkdir -p "$WORK_DIR"
if [[ ! -f "$WORK_DIR/$QUICKJS_ARCHIVE" ]]; then
  curl -L "$QUICKJS_URL" -o "$WORK_DIR/$QUICKJS_ARCHIVE"
fi

rm -rf "$QUICKJS_SOURCE_DIR"
mkdir -p "$WORK_DIR"
tar -xf "$WORK_DIR/$QUICKJS_ARCHIVE" -C "$WORK_DIR"

build_abi() {
  local abi="$1"
  local cross_prefix strip_bin
  case "$abi" in
    arm64-v8a)
      cross_prefix="aarch64-linux-android26-"
      ;;
    armeabi-v7a)
      cross_prefix="armv7a-linux-androideabi26-"
      ;;
    x86_64)
      cross_prefix="x86_64-linux-android26-"
      ;;
    x86)
      cross_prefix="i686-linux-android26-"
      ;;
    *)
      echo "Unsupported ABI: $abi" >&2
      exit 1
      ;;
  esac

  strip_bin="${TOOLCHAIN_BIN}/llvm-strip"
  pushd "$QUICKJS_SOURCE_DIR" >/dev/null
  export PATH="$TOOLCHAIN_BIN:$PATH"
  if [[ -n "$SDKROOT_VALUE" ]]; then
    export SDKROOT="$SDKROOT_VALUE"
  fi
  make clean >/dev/null 2>&1 || true
  make qjs CONFIG_CLANG=y CROSS_PREFIX="$cross_prefix" HOST_CC=cc LIBS='-lm -ldl'
  "$strip_bin" qjs || true
  popd >/dev/null

  mkdir -p "$ASSET_ROOT/$abi/quickjs"
  cp "$QUICKJS_SOURCE_DIR/qjs" "$ASSET_ROOT/$abi/quickjs/qjs"
  chmod +x "$ASSET_ROOT/$abi/quickjs/qjs"
}

if [[ $# -eq 0 ]]; then
  set -- arm64-v8a x86_64 armeabi-v7a x86
fi

for abi in "$@"; do
  build_abi "$abi"
done

echo "QuickJS helper assets staged under $ASSET_ROOT"
