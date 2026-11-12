#!/usr/bin/env bash
#
# 从备份恢复 SQLite 数据库。**会覆盖线上数据**，只在确认要回滚时用。
#
# 在目标服务器上执行。
#
# 用法：
#   ./restore.sh /opt/cls-news-backup/news-2026-11-12_0330.db.gz
#   ./restore.sh                     不带参数时列出可用备份，不做任何修改
#
# 恢复前会把当前库另存为 news-before-restore-<时间>.db，恢复错了还能退回去。
#
# ── 为什么必须删掉 -wal 和 -shm ─────────────────────────────────────
# 这是整个恢复流程里最容易漏、后果最严重的一步。实测（SQLite 3.4x，WAL 模式）：
#
#   库里有 v1/v2，备份出 b.db；之后又写入 v3（进了 -wal）；进程被 kill 留 WAL；
#   用 b.db 覆盖主库、但不删 -wal，再打开：
#       内容 = v1, v2, v3      ← v3 从 WAL 里被复活了
#        integrity_check = ok   ← 校验发现不了
#
# 得到的是「备份的底座 + 现场遗留的尾部」这种从未存在过的状态，能正常打开、
# 能查询、不报错，只有人肉核对行数才看得出来。
#
# 更极端的实测：把另一个完全无关的库的 -wal 放到某库旁边，该库原有数据会被
# 整个覆盖成 WAL 里那份数据 —— 同样不报错。所以不要依赖 SQLite 去「识别
# 这个 WAL 是不是我的」，无条件删除是唯一不需要推理的做法。
#
# 补充：正常情况下 `docker compose stop` 会让最后一个连接优雅关闭并自动
# checkpoint + 删除 -wal；但崩溃、OOM（ExitOnOutOfMemoryError）、断电之后
# 恰恰不会 —— 而那正是最需要恢复的时候。
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/opt/cls-news}"
DB_FILE="${DB_FILE:-news.db}"
VOLUME_LABEL="${VOLUME_LABEL:-cls-data}"
VOLUME_NAME="${VOLUME_NAME:-}"
BACKUP_DIR="${BACKUP_DIR:-/opt/cls-news-backup}"

say() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { printf '[%s] [fatal] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; exit 1; }

command -v sqlite3 >/dev/null 2>&1 || die "宿主机没有 sqlite3，先 apt-get install -y sqlite3"

# ---------- 0. 不带参数：只列清单 ----------
if [[ $# -lt 1 ]]; then
  # 变量后面紧跟全角字符时必须用 ${} 界定：bash 在多字节 locale 下
  # 会把「）」的首字节当成标识符的一部分，于是 $BACKUP_DIR）被解析成一个
  # 叫 BACKUP_DIR<乱码> 的变量，在 set -u 下直接 unbound variable。
  echo "可用备份（${BACKUP_DIR}）："
  if compgen -G "$BACKUP_DIR/news-*.db.gz" >/dev/null; then
    # 不用 ls | awk 切片：字段位置随 locale、是否硬链接、有无 group 列而变，
    # 之前按 GNU 的 $9 取文件名，在 macOS 的 ls 上文件名就整个丢了。
    # 文件名本身带时间戳（news-2026-11-12_0330），所以不必再解析 mtime。
    for f in "$BACKUP_DIR"/news-*.db.gz; do
      [[ -e "$f" ]] || continue
      printf '  %-42s %s\n' "$(basename "$f")" "$(du -h "$f" | cut -f1)"
    done
    echo
    echo "恢复：$0 <上面某个文件>"
  else
    echo "  （没有备份。先跑一次 backup.sh）"
  fi
  exit 0
fi

SRC="$1"
[[ -f "$SRC" ]] || die "文件不存在：$SRC"
gzip -t "$SRC" || die "gzip 校验失败，这个备份文件已损坏，换一份"
say "备份文件 gzip 完整"

# ---------- 1. 定位卷 ----------
discover_volume() {
  if [[ -n "$VOLUME_NAME" ]]; then printf '%s' "$VOLUME_NAME"; return; fi
  local found
  found="$(docker volume ls -q --filter "label=com.docker.compose.volume=$VOLUME_LABEL" 2>/dev/null | head -1)"
  [[ -n "$found" ]] || die "找不到 compose 卷（label com.docker.compose.volume=$VOLUME_LABEL）"
  printf '%s' "$found"
}
VOL="$(discover_volume)"
MOUNT="$(docker volume inspect "$VOL" --format '{{.Mountpoint}}')"
TARGET="$MOUNT/$DB_FILE"
say "目标库：$TARGET"

# ---------- 2. 解压到临时文件并校验 ----------
# 先解到 /tmp 校验，再动线上文件：校验失败的备份绝不能覆盖正在用的库。
PENDING="$(mktemp "${TMPDIR:-/tmp}/cls-restore.XXXXXX.db")"
trap 'rm -f "$PENDING"' EXIT

say "解压并校验备份"
gzip -dc "$SRC" > "$PENDING"
INTEGRITY="$(sqlite3 "$PENDING" 'PRAGMA integrity_check;' 2>&1)"
[[ "$INTEGRITY" == "ok" ]] || die "备份 integrity_check 未通过：$INTEGRITY"
NEW_ROWS="$(sqlite3 "$PENDING" 'SELECT COUNT(*) FROM news;')"

CUR_ROWS="(无现有库)"
if [[ -f "$TARGET" ]]; then
  CUR_ROWS="$(sqlite3 "$TARGET" 'SELECT COUNT(*) FROM news;' 2>/dev/null || echo '读取失败')"
fi

# ---------- 3. 人工确认 ----------
cat <<EOF

──────────────────────────────────────────────
  即将把线上数据库替换为这份备份
    来源：$SRC
    恢复后：news $NEW_ROWS 行
    当前线上：news $CUR_ROWS 行
    当前库会被另存为 news-before-restore-<时间>.db
──────────────────────────────────────────────
EOF

# 不用 read -p 配默认值：这里必须是显式输入 yes，
# 回车、y、Y 一律当作取消 —— 恢复是不可逆操作，不该有「顺手一按」的路径。
printf '确认继续？输入 yes 回车：'
read -r ANSWER
[[ "$ANSWER" == "yes" ]] || { echo "已取消，未做任何修改。"; exit 1; }

# ---------- 4. 停服务 ----------
say "停止容器"
( cd "$REMOTE_DIR" && docker compose stop ) || die "停止容器失败，未做任何修改"

# ---------- 5. 保存现场 ----------
if [[ -f "$TARGET" ]]; then
  SAVED="$MOUNT/news-before-restore-$(date '+%Y%m%d_%H%M%S').db"
  say "当前库另存为 $(basename "$SAVED")"
  # 此时容器已停，没有写入；但 WAL 里可能仍有未 checkpoint 的数据，
  # 所以用 .backup 而不是 cp，保证这一份「现场」自己也是一致的。
  sqlite3 "$TARGET" ".backup '$SAVED'" || die "保存现场失败，已中止（容器仍处于停止状态）"
fi

# ---------- 6. 覆盖 ----------
say "写入恢复数据"
# 先删 WAL/SHM，再覆盖主库（顺序不能反，理由见文件头注释）
rm -f "$TARGET-wal" "$TARGET-shm"
cp "$PENDING" "$TARGET"
# 容器内以 app 用户运行（Dockerfile.runtime 里 useradd -r app），
# 属主不对会导致启动正常、第一次写入就 read-only。
chown 999:999 "$TARGET" 2>/dev/null || true   # 取不到 app 的 uid 就保留现状，由容器自行处理
chmod 644 "$TARGET"

# ---------- 7. 启动并验证 ----------
say "启动容器"
( cd "$REMOTE_DIR" && docker compose up -d ) || die "启动失败。手动查看：cd $REMOTE_DIR && docker compose logs --tail=80"

say "等待服务健康（最多 90 秒）"
PORT="$(grep -E '^HOST_PORT=' "$REMOTE_DIR/.env" 2>/dev/null | cut -d= -f2)"
PORT="${PORT:-8080}"
for i in $(seq 1 30); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/healthz" 2>/dev/null || echo 000)"
  if [[ "$CODE" == "200" ]]; then
    say "服务已就绪（/healthz 200）"
    echo
    say "恢复完成。建议立刻做一次新备份，确认备份链路仍然正常："
    say "  $REMOTE_DIR/deploy/cloud/backup.sh"
    exit 0
  fi
  sleep 3
done

die "90 秒内未就绪。看日志：cd $REMOTE_DIR && docker compose logs --tail=80
     若要退回恢复前状态，把 $MOUNT/news-before-restore-*.db 按同样步骤放回去。"
