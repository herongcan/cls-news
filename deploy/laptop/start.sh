#!/usr/bin/env bash
#
# 笔记本常驻启动脚本。
#
# 这个脚本只做四件事，每一件都是「笔记本当服务器」踩过的坑：
#   1. 读本地密码配置，缺了就自动生成一个（否则公网裸奔）
#   2. 强制打开鉴权，密码为空直接拒绝启动
#   3. 用 caffeinate 包住 JVM，防止系统空闲休眠把服务冻住
#   4. exec 成 java 进程，让 launchd 能直接管理它（KeepAlive 靠这个生效）
#
# 手动前台跑：./deploy/laptop/start.sh
# 后台常驻跑：./deploy/laptop/install.sh（装成 LaunchAgent）
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_DIR"

CONFIG="$PROJECT_DIR/deploy/laptop/config.env"
JAR="$PROJECT_DIR/target/cls-news-0.1.0.jar"

# ---------- 1. 本地配置 ----------
# 不存在就生成一份随机密码。裸奔比麻烦更贵：这个工具存了财联社全文，
# 无鉴权暴露到公网等于做了个转贴站，版权风险是立刻兑现的。
if [[ ! -f "$CONFIG" ]]; then
  RAND_PW="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-14)"
  cat > "$CONFIG" <<EOF
# 本地配置，不要提交（.gitignore 已忽略）
APP_USER=admin
APP_PASSWORD=$RAND_PW
EOF
  chmod 600 "$CONFIG"
  echo "[init] 已生成随机密码并写入 $CONFIG"
fi

set -a
# shellcheck disable=SC1090
source "$CONFIG"
set +a

# ---------- 2. 鉴权强制开启 ----------
export APP_AUTH=true
if [[ -z "${APP_PASSWORD:-}" ]]; then
  echo "[fatal] APP_PASSWORD 为空，拒绝启动。" >&2
  echo "        请编辑 $CONFIG 填入密码。" >&2
  exit 1
fi

# ---------- 3. JDK ----------
# 本机默认 java 是 1.8，Spring Boot 3 要 17+，必须显式指定
export JAVA_HOME="${CLS_JAVA_HOME:-/opt/homebrew/opt/openjdk}"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "[fatal] 找不到 JDK：$JAVA_HOME/bin/java" >&2
  echo "        用 CLS_JAVA_HOME=/path/to/jdk 覆盖。" >&2
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "[fatal] 缺少 $JAR，先执行：mvn -DskipTests package" >&2
  exit 1
fi

echo "[start] $(date '+%Y-%m-%d %H:%M:%S') 启动 cls-news"
echo "[start] 鉴权已开启，用户=$APP_USER"
echo "[start] 监听 0.0.0.0:8080（局域网可直连，公网靠穿透）"

# ---------- 4. 防休眠 + 启动 ----------
# caffeinate -i 阻止「空闲睡眠」，-s 阻止「系统睡眠」（接电源时生效）。
# 注意：这两个参数都挡不住「合盖」——合盖时笔记本必然休眠，
# 所以这台机器必须保持开盖（屏幕可以熄，盖子不能合）。
exec caffeinate -i -s "$JAVA_HOME/bin/java" -Xmx512m -jar "$JAR" \
  --server.address=0.0.0.0 \
  --server.port=8080
