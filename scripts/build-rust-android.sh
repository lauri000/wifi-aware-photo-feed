#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUST_ROOT="$PROJECT_ROOT/rust"
APP_MAIN_DIR="$PROJECT_ROOT/app/src/main"
JNI_DIR="$APP_MAIN_DIR/jniLibs"
BINDINGS_DIR="$APP_MAIN_DIR/java"
BUILD_ARGS=()

if [[ "${1:-}" == "--release" ]]; then
  BUILD_ARGS+=(--release)
fi

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk is required. Install it with: cargo install cargo-ndk"
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]] && [[ -f "$PROJECT_ROOT/local.properties" ]]; then
  ANDROID_HOME="$(sed -n 's/^sdk.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME is not set and local.properties did not provide sdk.dir"
  exit 1
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_CANDIDATE="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
  if [[ -z "$NDK_CANDIDATE" ]]; then
    echo "Could not find an Android NDK under $ANDROID_HOME/ndk"
    exit 1
  fi
  export ANDROID_NDK_HOME="$NDK_CANDIDATE"
fi

cd "$RUST_ROOT"

OUTPUT_DIR="$RUST_ROOT/target/android/jniLibs"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -t x86 \
  -o "$OUTPUT_DIR" \
  build "${BUILD_ARGS[@]}" -p nearby-hashtree-ffi

rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"
cp -R "$OUTPUT_DIR"/. "$JNI_DIR"/

LIB_FOR_BINDGEN="$OUTPUT_DIR/arm64-v8a/libnearby_hashtree_ffi.so"

cargo run -p nearby-hashtree-ffi --features uniffi/cli --bin uniffi-bindgen -- \
  generate --library "$LIB_FOR_BINDGEN" \
  --language kotlin \
  --out-dir "$BINDINGS_DIR"

echo "Built JNI libraries into $JNI_DIR"
echo "Generated Kotlin bindings into $BINDINGS_DIR"
