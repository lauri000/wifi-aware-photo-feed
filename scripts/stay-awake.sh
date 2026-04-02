#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-/Users/l/Library/Android/sdk/platform-tools/adb}"
ACTION="${1:-status}"

if [[ ! -x "$ADB" ]]; then
  echo "adb not found at $ADB"
  echo "Set ADB=/path/to/adb or add adb to PATH."
  exit 1
fi

DEVICES=()
while IFS= read -r serial; do
  DEVICES+=("$serial")
done < <("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')

if [[ "${#DEVICES[@]}" -eq 0 ]]; then
  echo "No authorized Android devices detected."
  exit 1
fi

set_stay_awake() {
  local serial="$1"
  local mode="$2"
  "$ADB" -s "$serial" shell svc power stayon "$mode" >/dev/null
  local value
  value="$("$ADB" -s "$serial" shell settings get global stay_on_while_plugged_in | tr -d '\r')"
  echo "$serial stay_on_while_plugged_in=$value"
}

show_status() {
  local serial="$1"
  local value
  value="$("$ADB" -s "$serial" shell settings get global stay_on_while_plugged_in | tr -d '\r')"
  echo "$serial stay_on_while_plugged_in=$value"
}

case "$ACTION" in
  on)
    for serial in "${DEVICES[@]}"; do
      set_stay_awake "$serial" true
    done
    ;;
  off)
    for serial in "${DEVICES[@]}"; do
      set_stay_awake "$serial" false
    done
    ;;
  usb|ac|wireless|dock)
    for serial in "${DEVICES[@]}"; do
      set_stay_awake "$serial" "$ACTION"
    done
    ;;
  status)
    for serial in "${DEVICES[@]}"; do
      show_status "$serial"
    done
    ;;
  *)
    cat <<'EOF'
Usage:
  scripts/stay-awake.sh status
  scripts/stay-awake.sh on
  scripts/stay-awake.sh off
  scripts/stay-awake.sh usb
  scripts/stay-awake.sh ac
  scripts/stay-awake.sh wireless
  scripts/stay-awake.sh dock
EOF
    exit 1
    ;;
esac
