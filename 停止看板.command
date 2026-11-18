#!/bin/bash
set -euo pipefail

watch_label="local.codex-usage-display-adb"
if launchctl list "$watch_label" >/dev/null 2>&1; then
  launchctl remove "$watch_label"
fi
label="local.codex-usage-display"
if launchctl list "$label" >/dev/null 2>&1; then
  launchctl remove "$label"
fi
echo "Mac 用量服务已停止。"
