#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

if [[ $# != 2 ]]; then
  echo "用法：./连接双屏.command 用量屏IP:端口 小窝屏IP:端口" >&2
  exit 2
fi
for serial in "$@"; do
  if [[ ! "$serial" =~ ^[[:alnum:]_.:%-]+$ || "$serial" == -* ]]; then
    echo "无效的 ADB 地址或序列号。" >&2
    exit 2
  fi
  if [[ "$serial" == *:* ]]; then
    adb connect "$serial"
  fi
  if [[ "$(adb -s "$serial" get-state)" != device ]]; then
    echo "请先连接设备 $serial。" >&2
    exit 1
  fi
done

home_id="$(adb -s "$1" shell getprop ro.serialno | tr -d '\r')"
nest_id="$(adb -s "$2" shell getprop ro.serialno | tr -d '\r')"
if [[ "$1" == "$2" || ( -n "$home_id" && "$home_id" == "$nest_id" ) ]]; then
  echo "请指定两台不同的手机。" >&2
  exit 2
fi

if ! command -v node >/dev/null && [[ -s "$HOME/.nvm/nvm.sh" ]]; then
  set +u
  source "$HOME/.nvm/nvm.sh"
  set -u
fi
node_bin="$(command -v node)" || { echo "需要 Node.js 18 或更高版本。" >&2; exit 1; }
adb_bin="$(command -v adb)"
port=8767
health="http://127.0.0.1:$port/health"
label="local.codex-cat-visit"
logs="$(getconf DARWIN_USER_TEMP_DIR)"

relay_healthy() {
  [[ "$(curl --fail --silent --max-time 1 "$health")" == "cat-visit-v3" ]]
}

if ! relay_healthy; then
  if launchctl list "$label" >/dev/null 2>&1; then
    launchctl remove "$label"
    for attempt in {1..20}; do
      if ! curl --fail --silent --max-time 0.3 "$health" >/dev/null; then
        break
      fi
      sleep 0.15
    done
  fi
  launchctl submit -l "$label" -o "${logs}cat-visit.log" -e "${logs}cat-visit.err" -- \
    "$node_bin" "$PWD/cat-visit-server.mjs"
  for attempt in {1..30}; do
    if relay_healthy; then
      break
    fi
    if [[ "$attempt" == 30 ]]; then
      echo "串门服务未启动。日志：${logs}cat-visit.err" >&2
      exit 1
    fi
    sleep 0.25
  done
fi

link_screen() {
  local role="$1" transport="$2" id="$3"
  shift 3
  local watch_label="local.codex-cat-visit-$role"
  local port_number
  for port_number in "$@"; do
    adb -s "$transport" reverse "tcp:$port_number" "tcp:$port_number"
  done
  if launchctl list "$watch_label" >/dev/null 2>&1; then
    launchctl remove "$watch_label"
  fi
  if [[ -n "$id" ]]; then
    launchctl submit -l "$watch_label" -e "${logs}cat-visit-$role.err" -- \
      "$node_bin" "$PWD/adb-reverse.mjs" "$adb_bin" "$transport" "$id" "$@"
  fi
}

link_screen home "$1" "$home_id" "$port"
link_screen nest "$2" "$nest_id" "$port" 8765
./启动看板.command "$1"
echo "双屏已连接。打开两台手机的橘猫看板，在「串门」里选择各自的角色。"
