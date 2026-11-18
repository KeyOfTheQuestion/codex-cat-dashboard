#!/bin/bash
set -euo pipefail

cd -- "$(dirname -- "$0")"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
if ! command -v node >/dev/null || ! command -v codex >/dev/null; then
  if [[ -s "$HOME/.nvm/nvm.sh" ]]; then
    set +u
    source "$HOME/.nvm/nvm.sh"
    set -u
  fi
fi

node_bin="$(command -v node)" || { echo "找不到 Node.js"; exit 1; }
codex_bin="$(command -v codex)" || { echo "找不到 Codex CLI"; exit 1; }
label="local.codex-usage-display"
port=8765
url="http://127.0.0.1:$port/"
log_dir="$(getconf DARWIN_USER_TEMP_DIR)"

if ! curl --fail --silent --max-time 1 "${url}api/usage" >/dev/null; then
  if launchctl list "$label" >/dev/null 2>&1; then
    launchctl remove "$label"
  fi
  launchctl submit -l "$label" -o "${log_dir}codex-usage-display.log" \
    -e "${log_dir}codex-usage-display.err" -- \
    /usr/bin/env "PATH=$PATH" "CODEX_DISPLAY_CLI=$codex_bin" "CODEX_DISPLAY_PORT=$port" \
    "CODEX_HOME=${CODEX_HOME:-$HOME/.codex}" "CODEX_DISPLAY_WORKBUDDY=${CODEX_DISPLAY_WORKBUDDY:-auto}" \
    "WORKBUDDY_CONFIG_DIR=${WORKBUDDY_CONFIG_DIR:-$HOME/.workbuddy}" "$node_bin" "$PWD/server.mjs"
fi

for attempt in {1..20}; do
  if curl --fail --silent --max-time 1 "${url}api/usage" >/dev/null; then
    break
  fi
  if [[ "$attempt" == 20 ]]; then
    echo "Mac 用量服务未启动。日志：${log_dir}codex-usage-display.err"
    exit 1
  fi
  sleep 0.25
done

if [[ "${1:-}" == "--mac-only" ]]; then
  echo "Mac 看板：$url"
  exit 0
fi

use_web=false
if [[ "${1:-}" == "--web" ]]; then
  use_web=true
  shift
fi

serial="${1:-}"
if [[ -n "$serial" && "$serial" == *:* ]]; then
  adb connect "$serial"
fi
if [[ -z "$serial" ]]; then
  devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  if [[ -z "$devices" ]] || [[ "$devices" == *$'\n'* ]]; then
    echo "请连接一台红米，或把无线调试主界面的 IP:端口作为启动参数。"
    echo "Mac 看板：$url"
    exit 2
  fi
  serial="$devices"
fi

adb -s "$serial" reverse "tcp:$port" "tcp:$port" >/dev/null
adb_bin="$(command -v adb)"
device_serial="$(adb -s "$serial" shell getprop ro.serialno | tr -d '\r' || true)"
if [[ -n "$device_serial" ]]; then
  watch_label="local.codex-usage-display-adb"
  if launchctl list "$watch_label" >/dev/null 2>&1; then
    launchctl remove "$watch_label"
  fi
  launchctl submit -l "$watch_label" -e "${log_dir}codex-usage-display-adb.err" -- \
    "$node_bin" "$PWD/adb-reverse.mjs" "$adb_bin" "$serial" "$device_serial" "$port"
fi
if [[ "$use_web" == false ]] && adb -s "$serial" shell pm path local.codex.display | grep -q '^package:'; then
  adb -s "$serial" shell am start -n local.codex.display/.MainActivity
  echo "已打开红米上的橘猫看板。"
else
  adb -s "$serial" shell am start -a android.intent.action.VIEW -d "$url"
  echo "红米网页看板：$url"
fi
