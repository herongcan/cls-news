#!/usr/bin/env bash
#
# 卸载常驻服务。只停服务，不动 data/ 里的数据库。
#
set -euo pipefail

LABEL="com.herc.clsnews"
PLIST_DST="$HOME/Library/LaunchAgents/$LABEL.plist"

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$PLIST_DST"

echo "已卸载 $LABEL"
echo "数据库保留在 $(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/data"
