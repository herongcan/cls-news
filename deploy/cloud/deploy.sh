#!/usr/bin/env bash
#
# 一键部署到云服务器。
#
# 用法（首次）：
#   DEEPSEEK_API_KEY=sk-xxxx ./deploy/cloud/deploy.sh root@1.2.3.4
#
# 用法（之后只更新代码，key 沿用远端已有的）：
#   ./deploy/cloud/deploy.sh root@1.2.3.4
#
# 可覆盖的变量：
#   REMOTE_DIR  远端目录，默认 /opt/cls-news
#   HOST_PORT   宿主机端口，默认 8080
#   APP_PASSWORD 访问密码，默认 admin
#   APP_USER     访问账号，默认 admin
#
set -euo pipefail

HOST="${1:-}"
if [[ -z "$HOST" ]]; then
  cat >&2 <<'USAGE'
用法：DEEPSEEK_API_KEY=sk-xxxx ./deploy/cloud/deploy.sh root@<公网IP>

第一次必须带 DEEPSEEK_API_KEY（服务器上没有 ~/.codex/auth.json 可回落，
不带的话分析功能会静默降级成「只采集不分析」）。
USAGE
  exit 1
fi

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE_DIR="${REMOTE_DIR:-/opt/cls-news}"
HOST_PORT="${HOST_PORT:-8080}"
cd "$PROJECT_DIR"

say() { printf '\033[36m[deploy]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[fatal]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- 0. 本地构建 jar ----------
# jar 在本地打，不交给 2核2G 的服务器去跑 Maven：
# 那边内存不够会被 OOM killer 直接杀掉，日志里只有一句 Killed，很难查。
JAR="target/cls-news-0.1.0.jar"
if [[ -n "${CI:-}" ]]; then
  # GitHub Actions 里 jar 由流水线的「打包」步骤产出，这里不重复构建。
  # 顺带不设 JAVA_HOME —— 下面的默认值 /opt/homebrew/opt/openjdk 是 macOS 路径，
  # 在 ubuntu runner 上不存在。
  say "CI 环境：跳过本地构建（jar 来自流水线上一步）"
else
  export JAVA_HOME="${CLS_JAVA_HOME:-/opt/homebrew/opt/openjdk}"
  if [[ ! -f "$JAR" ]] || [[ -n "$(find src pom.xml -newer "$JAR" -type f -print -quit 2>/dev/null)" ]]; then
    say "本地打包（源码比 jar 新，或 jar 不存在）"
    # 用 mvnw 而不是系统 mvn：Maven 版本锁在仓库里，不依赖本机装的是哪个版本。
    # 之前就因为本机 3.5.9 编译 maven-compiler-plugin 3.14.0 直接失败。
    [[ -x ./mvnw ]] || chmod +x ./mvnw
    ./mvnw -q -B -DskipTests package || die "本地构建失败，先把编译错误修掉"
  else
    say "jar 是最新的，跳过构建"
  fi
fi
[[ -f "$JAR" ]] || die "构建后仍找不到 $JAR"

# ---------- 1. 连通性 ----------
say "检查 SSH 连通性 $HOST"
if ! ssh -o ConnectTimeout=8 -o BatchMode=yes "$HOST" 'echo ok' >/dev/null 2>&1; then
  if [[ -n "${CI:-}" ]]; then
    die "连不上 ${HOST}：检查仓库 Secrets 里的 SSH_HOST / SSH_KEY，以及安全组是否放行 22 端口（来源 0.0.0.0/0）。"
  fi
  die "连不上 ${HOST}：确认 IP、SSH 密钥已配置（ssh-copy-id）、安全组放行了 22 端口。"
fi

# ---------- 2. 远端 Docker ----------
if ! ssh "$HOST" 'command -v docker' >/dev/null 2>&1; then
  die "远端没装 Docker。先执行 deploy/cloud/README.md 里的「装 Docker」一节。"
fi

say "检查远端 Docker 是否在跑"
ssh "$HOST" 'docker info' >/dev/null 2>&1 || die "Docker 装了但没运行：ssh $HOST 'systemctl enable --now docker'"

# ---------- 3. 复用远端已有的 key ----------
REMOTE_ENV="$REMOTE_DIR/.env"

# 这里必须把远端 key 先读进本地变量，不能在「写 .env 的管道内部」才去取。
# 踩过的坑：写 .env 用 `cat > $REMOTE_ENV`，这个重定向在 SSH 连接一建立就把远端文件
# 截断了；如果取 key 的 `ssh grep $REMOTE_ENV` 放在同一个管道的 else 分支里，
# 读到的一定是刚被清空的文件，于是 key 静默丢失、analysisReady 变 false，
# 而脚本第 3 步的「远端已有 key」检查是在截断之前跑的、照样通过 —— 极难察觉。
API_KEY="${DEEPSEEK_API_KEY:-}"
if [[ -z "$API_KEY" ]]; then
  API_KEY="$(ssh "$HOST" "sed -n 's/^DEEPSEEK_API_KEY=//p' $REMOTE_ENV 2>/dev/null | head -1" 2>/dev/null || true)"
  if [[ -z "$API_KEY" ]]; then
    die "没有 DEEPSEEK_API_KEY，远端也没有。带上再跑：DEEPSEEK_API_KEY=sk-xxxx $0 $HOST"
  fi
  say "沿用远端已有的 DEEPSEEK_API_KEY（${#API_KEY} 字符）"
fi

# 访问密码固定 admin/admin（可用 APP_PASSWORD 覆盖）。
# 这里从「随机生成 + 沿用远端旧密码」改成固定值，是因为多人共用一个账号：
# 每次部署重新生成，十个同事的浏览器会集体 401，而他们只会觉得「你这工具又坏了」；
# 沿用旧密码又会让密码散落在每个人的历史记录里、自己都说不清是哪个。
# 固定一个大家事先说好的，反而最省事。
APP_PW="${APP_PASSWORD:-admin}"
APP_USER_NAME="${APP_USER:-admin}"
say "访问账号 ${APP_USER_NAME} / ${APP_PW}"

# ---------- 4. 推代码 ----------
say "同步代码到 $HOST:$REMOTE_DIR"
ssh "$HOST" "mkdir -p $REMOTE_DIR"
# 排除项说明：
#   data/ logs/ 是本机产物，传上去只会污染云端、甚至用旧库覆盖新库
#   target/ 只放行那一个 jar（服务器上要用它打运行镜像），其余 class 文件不传
#   .env 单独写（里面有密码），不进 rsync，避免被 --delete 误删
rsync -az --delete \
  --exclude '.git' \
  --exclude '.idea' \
  --exclude '*.iml' \
  --include 'target/' \
  --include 'target/cls-news-0.1.0.jar' \
  --exclude 'target/*' \
  --exclude 'data' \
  --exclude 'logs' \
  --exclude '.env' \
  --exclude 'deploy/laptop/config.env' \
  --exclude '*.log' \
  --exclude '.DS_Store' \
  "$PROJECT_DIR/" "$HOST:$REMOTE_DIR/"

# ---------- 5. 写 .env ----------
# 按远端实际内存自动定容器上限。1G 的小机器（阿里云试用、轻量最低配）
# 如果按 512m 跑，加上系统本身和 Docker，很容易被 OOM killer 干掉，
# 表现是容器反复重启、日志只有一句 Killed，极难往内存上想。
REMOTE_MEM_MB="$(ssh "$HOST" "free -m 2>/dev/null | awk '/^Mem:/{print \$2}'" 2>/dev/null || echo "")"
if [[ "$REMOTE_MEM_MB" =~ ^[0-9]+$ ]] && (( REMOTE_MEM_MB <= 1000 )); then
  # 1G 规格的机器标称 1G，free 只能看到 ~894MB；其中阿里云 aegis 安全组件、
  # aliyun-assist、Ubuntu 默认服务已占掉 370MB 左右，实际可用不到 520MB。
  # 448m 会贴到天花板，384m（堆约 268MB）实测够这个应用跑。
  MEM_LIMIT=384m
  say "远端内存 ${REMOTE_MEM_MB}MB（1G 规格），容器上限设为 384m"
elif [[ "$REMOTE_MEM_MB" =~ ^[0-9]+$ ]] && (( REMOTE_MEM_MB <= 1600 )); then
  MEM_LIMIT=448m
  say "远端内存 ${REMOTE_MEM_MB}MB（小内存机器），容器上限设为 448m"
else
  MEM_LIMIT=512m
  [[ -n "$REMOTE_MEM_MB" ]] && say "远端内存 ${REMOTE_MEM_MB}MB，容器上限 512m"
fi

say "写入远端 .env"
{
  echo "# 由 deploy.sh 生成，不要提交"
  echo "TZ=Asia/Shanghai"
  echo "DB_PATH=/app/data/news.db"
  echo "APP_AUTH=true"
  echo "APP_USER=$APP_USER_NAME"
  echo "APP_PASSWORD=$APP_PW"
  echo "BIND_ADDR=0.0.0.0"
  echo "HOST_PORT=$HOST_PORT"
  echo "MEM_LIMIT=$MEM_LIMIT"
  # 服务器只用运行镜像（见 deploy/cloud/Dockerfile.runtime 里的说明）
  echo "DOCKERFILE=deploy/cloud/Dockerfile.runtime"
  # key 已在上面读进 API_KEY（见第 3 步的注释），这里只负责写，不再回读远端文件
  echo "DEEPSEEK_API_KEY=$API_KEY"
} | ssh "$HOST" "cat > $REMOTE_ENV && chmod 600 $REMOTE_ENV"

# 写后自检：不校验的话，key 丢了要等到有人点「分析」报 503 才发现
WROTE_KEY_LEN="$(ssh "$HOST" "sed -n 's/^DEEPSEEK_API_KEY=//p' $REMOTE_ENV | head -1 | tr -d '\n' | wc -c" 2>/dev/null || echo 0)"
if [[ "$WROTE_KEY_LEN" -lt 20 ]]; then
  die "远端 .env 里的 DEEPSEEK_API_KEY 异常（长度 ${WROTE_KEY_LEN}），分析功能会不可用"
fi
say "远端 .env 已写入，key 长度 ${WROTE_KEY_LEN}"

# ---------- 6. 构建并启动 ----------
say "构建镜像并启动（运行镜像只拉 JRE，不跑 Maven）"
# 只用 compose v2。不兜底到 docker-compose(v1)：
# v1 在 2023 年就停止维护，新系统上都没有；兜底只会把真正的构建错误
# 变成一句 "docker-compose: command not found"，让人查错方向。
ssh "$HOST" "cd $REMOTE_DIR && docker compose up -d --build"

# ---------- 7. 等健康检查 ----------
say "等待服务健康（最多 90 秒）"
for i in $(seq 1 30); do
  code="$(ssh "$HOST" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$HOST_PORT/healthz" 2>/dev/null || echo 000)"
  if [[ "$code" == "200" ]]; then
    say "服务已就绪"
    break
  fi
  [[ $i -eq 30 ]] && die "90 秒仍未就绪。看日志：ssh $HOST 'cd $REMOTE_DIR && docker compose logs -f --tail=80'"
  sleep 3
done

# ---------- 8. 报告 ----------
PUBLIC_IP="${HOST#*@}"
echo
echo "════════════════════════════════════════════"
echo "  部署完成"
echo "════════════════════════════════════════════"
echo "  访问地址：http://$PUBLIC_IP:$HOST_PORT"
echo "  账号密码：$APP_USER_NAME / $APP_PW"
echo
echo "  直接发给同事的说明："
echo "  ─────────────────────────────────────"
echo "  财联社快讯（内部工具）"
echo "  地址：http://$PUBLIC_IP:$HOST_PORT"
echo "  账号：$APP_USER_NAME"
echo "  密码：$APP_PW"
echo "  ─────────────────────────────────────"
echo
echo "  常用命令："
echo "    看日志  ssh $HOST 'cd $REMOTE_DIR && docker compose logs -f --tail=50'"
echo "    重启    ssh $HOST 'cd $REMOTE_DIR && docker compose restart'"
echo "    查进度  curl -s -u $APP_USER_NAME:$APP_PW http://$PUBLIC_IP:$HOST_PORT/api/news | python3 -m json.tool | head -20"
echo "            （注意：运行镜像是 JRE，里面没有 sqlite3 命令，别用 docker compose exec 查库）"
echo
