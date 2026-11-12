#!/usr/bin/env bash
#
# 云效流水线在 ECS 本机执行的部署脚本。
#
# 和 deploy/cloud/deploy.sh 的分工：
#   deploy.sh         在本地/CI 跑，负责「构建 + ssh 传输 + 重建」
#   deploy-flow.sh    在目标机跑，负责「接收制品 + 重建容器 + 验健康」
#
# 云效的 Runner 是常驻在这台机器上的，构建产物已经被它下载到本地路径了，
# 所以这里既不用 ssh 也不用 rsync —— 这正是不需要公网开放 22 端口的原因。
#
# 云效「部署」步骤里这样填：
#   下载路径  /home/admin/app/package.tgz
#   执行用户  root
#   部署脚本  bash /opt/cls-news/deploy/cloud/deploy-flow.sh
#
# 前提：/opt/cls-news 下已有 docker-compose.yml 和 deploy/cloud/Dockerfile.runtime
#      （首次用 deploy.sh 部署时 rsync 上去的），这里只替换 jar。
#
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/cls-news}"
PKG="${1:-${PACKAGE_PATH:-/home/admin/app/package.tgz}}"
PORT="${HOST_PORT:-8080}"

say() { printf '\033[36m[flow]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[fatal]\033[0m %s\n' "$*" >&2; exit 1; }

[[ -f "$PKG" ]] || die "找不到制品包 $PKG —— 检查云效部署步骤里的「下载路径」是否填错"
[[ -f "$APP_DIR/docker-compose.yml" ]] || die "$APP_DIR 下没有 docker-compose.yml，先跑一次 ./deploy/cloud/deploy.sh 把配置铺上去"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

say "解压制品 $(du -h "$PKG" | cut -f1)"
tar -xzf "$PKG" -C "$TMP" || die "解压失败，确认制品是 .tgz/.tar.gz"

# 不写死解压后的目录层级：云效「构建物上传」里路径填 target/ 还是 target/*，
# 出来的结构会差一层。按文件名找最省事，也不会因为改了配置就静默失效。
JAR="$(find "$TMP" -type f -name 'cls-news-*.jar' -print -quit)"
[[ -n "$JAR" ]] || die "制品里找不到 cls-news-*.jar —— 检查构建物路径是否包含 target/"

say "替换 jar：$(basename "$JAR")"
mkdir -p "$APP_DIR/target"
install -m 644 "$JAR" "$APP_DIR/target/cls-news-0.1.0.jar"

say "重建容器（旧容器会被替换，约 20-40 秒不可用）"
cd "$APP_DIR"
docker compose up -d --build

say "等待健康检查（最多 90 秒）"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1; then
    say "服务已就绪"
    exit 0
  fi
  [[ $i -eq 30 ]] && die "90 秒仍未就绪。看日志：cd $APP_DIR && docker compose logs --tail=80"
  sleep 3
done
