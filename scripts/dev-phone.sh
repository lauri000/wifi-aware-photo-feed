#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-/Users/l/Library/Android/sdk/platform-tools/adb}"
JAVA_HOME_DEFAULT="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
APP_ID="com.lauri000.nostrwifiaware"
ACTIVITY="$APP_ID/.MainActivity"
LOG_TAG="NostrWifiAware"

if [[ ! -x "$ADB" ]]; then
  echo "adb not found at $ADB"
  echo "Set ADB=/path/to/adb or add adb to PATH."
  exit 1
fi

if [[ ! -d "$JAVA_HOME" ]]; then
  echo "JAVA_HOME not found at $JAVA_HOME"
  echo "Set JAVA_HOME to a working JDK, for example openjdk@21."
  exit 1
fi

check_device() {
  local device_count
  device_count="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
  if [[ "$device_count" -lt 1 ]]; then
    echo "No authorized Android device detected."
    echo "Check USB cable, Developer options, USB debugging, and the device authorization prompt."
    exit 1
  fi
}

build() {
  (cd "$ROOT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug)
}

install() {
  check_device
  (cd "$ROOT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew installDebug)
}

run_app() {
  check_device
  "$ADB" shell am start -n "$ACTIVITY"
}

logcat_app() {
  check_device
  "$ADB" logcat -c
  "$ADB" logcat -s "$LOG_TAG"
}

case "${1:-help}" in
  build)
    build
    ;;
  install)
    install
    ;;
  run)
    run_app
    ;;
  logcat)
    logcat_app
    ;;
  all)
    build
    install
    run_app
    ;;
  *)
    cat <<'EOF'
Usage:
  scripts/dev-phone.sh build
  scripts/dev-phone.sh install
  scripts/dev-phone.sh run
  scripts/dev-phone.sh logcat
  scripts/dev-phone.sh all
EOF
    ;;
esac
