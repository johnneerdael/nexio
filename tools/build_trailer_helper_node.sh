#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_ROOT="$ROOT_DIR/app/src/main/assets/trailer-helper/runtime"
WORK_DIR="${TMPDIR:-/tmp}/nexio-node-build"
NODE_MOBILE_VERSION="v18.20.4"
NODE_MOBILE_ARCHIVE="nodejs-mobile-${NODE_MOBILE_VERSION}-android.zip"
NODE_MOBILE_URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/${NODE_MOBILE_VERSION}/${NODE_MOBILE_ARCHIVE}"
NODE_MOBILE_ROOT="$WORK_DIR/nodejs-mobile"
LAUNCHER_SOURCE="$ROOT_DIR/tools/trailer_helper_node_launcher.cpp"
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}}"
TOOLCHAIN_ROOT="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64"
TOOLCHAIN_BIN="$TOOLCHAIN_ROOT/bin"
SYSROOT="$TOOLCHAIN_ROOT/sysroot"

if [[ ! -d "$TOOLCHAIN_BIN" ]]; then
  echo "Android NDK toolchain not found at $TOOLCHAIN_BIN" >&2
  exit 1
fi

mkdir -p "$WORK_DIR"
if [[ ! -f "$WORK_DIR/$NODE_MOBILE_ARCHIVE" ]]; then
  curl -L "$NODE_MOBILE_URL" -o "$WORK_DIR/$NODE_MOBILE_ARCHIVE"
fi

rm -rf "$NODE_MOBILE_ROOT"
mkdir -p "$NODE_MOBILE_ROOT"
unzip -o -q "$WORK_DIR/$NODE_MOBILE_ARCHIVE" -d "$NODE_MOBILE_ROOT"

build_abi() {
  local abi="$1"
  local triple api_level libnode_input libcpp_input output_root bin_root lib_root strip_bin cxx_bin
  case "$abi" in
    arm64-v8a)
      triple="aarch64-linux-android"
      api_level="26"
      ;;
    armeabi-v7a)
      triple="armv7a-linux-androideabi"
      api_level="26"
      ;;
    x86_64)
      triple="x86_64-linux-android"
      api_level="26"
      ;;
    *)
      echo "Unsupported ABI: $abi" >&2
      exit 1
      ;;
  esac

  libnode_input="$NODE_MOBILE_ROOT/bin/$abi/libnode.so"
  if [[ ! -f "$libnode_input" ]]; then
    echo "nodejs-mobile asset missing libnode for $abi at $libnode_input" >&2
    exit 1
  fi

  case "$abi" in
    arm64-v8a)
      libcpp_input="$SYSROOT/usr/lib/aarch64-linux-android/libc++_shared.so"
      ;;
    armeabi-v7a)
      libcpp_input="$SYSROOT/usr/lib/arm-linux-androideabi/libc++_shared.so"
      ;;
    x86_64)
      libcpp_input="$SYSROOT/usr/lib/x86_64-linux-android/libc++_shared.so"
      ;;
  esac

  if [[ ! -f "$libcpp_input" ]]; then
    echo "NDK libc++_shared.so missing for $abi at $libcpp_input" >&2
    exit 1
  fi

  output_root="$ASSET_ROOT/$abi/node"
  bin_root="$output_root/bin"
  lib_root="$output_root/lib"
  mkdir -p "$bin_root" "$lib_root"

  cp "$libnode_input" "$lib_root/libnode.so"
  cp "$libcpp_input" "$lib_root/libc++_shared.so"
  chmod +x "$lib_root/libnode.so" "$lib_root/libc++_shared.so"

  cxx_bin="$TOOLCHAIN_BIN/${triple}${api_level}-clang++"
  strip_bin="$TOOLCHAIN_BIN/llvm-strip"

  "$cxx_bin" \
    --sysroot="$SYSROOT" \
    -std=c++17 \
    -fPIE \
    -pie \
    -I"$NODE_MOBILE_ROOT/include/node" \
    "$LAUNCHER_SOURCE" \
    -L"$lib_root" \
    -lnode \
    -llog \
    -ldl \
    -lm \
    -Wl,--enable-new-dtags \
    -Wl,-rpath,'$ORIGIN/../lib' \
    -o "$bin_root/node"

  "$strip_bin" "$bin_root/node" || true
  chmod +x "$bin_root/node"
}

if [[ $# -eq 0 ]]; then
  set -- arm64-v8a armeabi-v7a x86_64
fi

for abi in "$@"; do
  build_abi "$abi"
done

echo "Node helper assets staged under $ASSET_ROOT"
