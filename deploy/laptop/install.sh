#!/usr/bin/env bash
#
# 把 cls-news 装成用户级常驻服务（LaunchAgent）。
# 效果：登录后自动启动、进程崩了 30 秒内自动拉起、系统空闲不休眠。
#
set -euo pipefail

LAPTOP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$LAPTOP_DIR/../.." && pwd)"
LABEL="com.herc.clsnews"
PLIST_DST="$HOME/Library/LaunchAgents/$LABEL.plist"

cd "$PROJECT_DIR"
mkdir -p "$PROJECT_DIR/logs" "$PROJECT_DIR/data" "$HOME/Library/LaunchAgents"
chmod +x "$LAPTOP_DIR/start.sh"

# ---------- 预检 ----------
if [[ ! -f "$PROJECT_DIR/target/cls-news-0.1.0.jar" ]]; then
  echo "[fatal] 缺少 jar，先执行：./mvnw -DskipTests package" >&2
  exit 1
fi

if [[ ! -f "$LAPTOP_DIR/config.env" ]]; then
  echo "[init] 首次安装，生成随机访问密码…"
  RAND_PW="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-14)"
  cat > "$LAPTOP_DIR/config.env" <<EOF
# 本地配置，不要提交（.gitignore 已忽略）
APP_USER=admin
APP_PASSWORD=$RAND_PW
EOF
  chmod 600 "$LAPTOP_DIR/config.env"
fi

# ---------- 生成并加载 plist ----------
sed "s#__PROJECT_DIR__#$PROJECT_DIR#g" "$LAPTOP_DIR/$LABEL.plist" > "$PLIST_DST"

# 先卸旧的（存在才卸，第一次装会报错，忽略即可）
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_DST"
launchctl enable "gui/$(id -u)/$LABEL"

# ---------- 报告 ----------
echo
echo "已安装：$LABEL"
echo "  plist : $PLIST_DST"
echo "  日志  : $PROJECT_DIR/logs/app.log"
echo

PASS="$(grep '^APP_PASSWORD=' "$LAPTOP_DIR/config.env" | cut -d= -f2-)"
USER_NAME="$(grep '^APP_USER=' "$LAPTOP_DIR/config.env" | cut -d= -f2-)"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || echo '取不到')"

echo "  账号密码：$USER_NAME / $PASS"
echo "  局域网址：http://$LAN_IP:8080"
echo
echo "等 5 秒后自检："
sleep 5
if curl -fsS -o /dev/null -w "  /healthz  -> HTTP %{http_code}\n" http://127.0.0.1:8080/healthz; then
  echo "  ✓ 服务已就绪"
else
  echo "  ✗ 还没起来，看日志：tail -30 $PROJECT_DIR/logs/app.err.log"
fi
