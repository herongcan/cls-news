#!/usr/bin/env bash
#
# 从备份恢复 SQLite 数据库，或演练一份备份能不能用。
#
# 在目标服务器上执行。三种模式：
#
#   ./restore.sh                          列出可用备份
#   ./restore.sh --drill                  演练最新一份（只读，不碰生产）
#   ./restore.sh <文件> --drill           演练指定备份（只读，不碰生产）
#   ./restore.sh <文件>                   真恢复，覆盖线上数据，需输入 yes
#   ./restore.sh <文件> --yes             真恢复且跳过确认（脚本化用）
#
# 演练模式回答的是「如果现在出事，这份备份能救回多少」，而不是「文件能不能解压」。
# 它只读备份文件，可以在生产机上随时跑，不需要停服务。
#
# 真恢复前会把当前库另存为 news-before-restore-<时间>.db，恢复错了还能退回去。
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

# epoch 秒 → 可读时间。GNU date 用 -d @，BSD date（macOS）用 -r，
# 演练脚本可能被人拉到本地跑，两个都兜住。
ts_fmt() {
  date -d "@$1" '+%Y-%m-%d %H:%M:%S' 2>/dev/null \
    || date -r "$1" '+%Y-%m-%d %H:%M:%S' 2>/dev/null \
    || printf '@%s' "$1"
}

# 秒数 → 「3 天 4 小时」这种人话，用于 RPO 表达
fmt_dur() {
  local s=$1
  if   (( s < 60 ));    then printf '%d 秒' "$s"
  elif (( s < 3600 ));  then printf '%d 分 %d 秒' $((s / 60)) $((s % 60))
  elif (( s < 86400 )); then printf '%d 小时 %d 分' $((s / 3600)) $((s % 3600 / 60))
  else                       printf '%d 天 %d 小时' $((s / 86400)) $((s % 86400 / 3600))
  fi
}

# ---------- 0. 参数解析 ----------
SRC=""
DRILL=0
ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    --drill)  DRILL=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) die "未知参数：$arg（可用：--drill / --yes）" ;;
    *)  [[ -z "$SRC" ]] || die "只接受一个文件参数，收到多个：$SRC 和 $arg"
        SRC="$arg" ;;
  esac
done

command -v sqlite3 >/dev/null 2>&1 || die "宿主机没有 sqlite3，先 apt-get install -y sqlite3"

# ---------- 1. 没给文件时的行为 ----------
if [[ -z "$SRC" ]]; then
  if (( DRILL )); then
    # 演练最常用的形态是「演练最新那份」，不该逼人先 ls 一遍再抄文件名
    shopt -s nullglob
    files=("$BACKUP_DIR"/news-*.db.gz)
    shopt -u nullglob
    (( ${#files[@]} )) || die "没有备份可演练（$BACKUP_DIR 下没有 news-*.db.gz）"
    SRC="${files[${#files[@]}-1]}"
  else
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
      # 用 basename 而不是 $0：从别处调用时 $0 是完整路径，提示行会被顶歪
      PROG="$(basename "$0")"
      echo
      printf '  演练：%s --drill           只读，验证最新一份能不能用\n' "$PROG"
      printf '  恢复：%s <文件>            覆盖线上，需输入 yes\n' "$PROG"
    else
      echo "  （没有备份。先跑一次 backup.sh）"
    fi
    exit 0
  fi
fi

[[ -f "$SRC" ]] || die "文件不存在：$SRC"
gzip -t "$SRC" || die "gzip 校验失败，这个备份文件已损坏，换一份"
say "备份文件 gzip 完整"

# ---------- 2. 解压到临时文件 ----------
# 先解到 /tmp 校验，再动线上文件：校验失败的备份绝不能覆盖正在用的库。
PENDING="$(mktemp "${TMPDIR:-/tmp}/cls-restore.XXXXXX.db")"
trap 'rm -f "$PENDING"' EXIT
gzip -dc "$SRC" > "$PENDING"

# ---------- 3. 体检 ----------
INTEGRITY="$(sqlite3 "$PENDING" 'PRAGMA integrity_check;' 2>&1)"
[[ "$INTEGRITY" == "ok" ]] || die "备份 integrity_check 未通过：$INTEGRITY"

sqlite3 "$PENDING" "SELECT 1 FROM sqlite_master WHERE type='table' AND name='news';" 2>/dev/null | grep -q 1 \
  || die "备份里没有 news 表，这不是这个应用的库"

NEW_ROWS="$(sqlite3 "$PENDING" 'SELECT COUNT(*) FROM news;' 2>/dev/null || echo 0)"
NEW_MAX_TS="$(sqlite3 "$PENDING" 'SELECT COALESCE(MAX(created_at),0) FROM news;' 2>/dev/null || echo 0)"
NEW_MIN_TS="$(sqlite3 "$PENDING" 'SELECT COALESCE(MIN(created_at),0) FROM news;' 2>/dev/null || echo 0)"
TITLE_EMPTY="$(sqlite3 "$PENDING" "SELECT COUNT(*) FROM news WHERE TRIM(COALESCE(title,''))='';" 2>/dev/null || echo '?')"
BODY_EMPTY="$(sqlite3 "$PENDING" "SELECT COUNT(*) FROM news WHERE TRIM(COALESCE(content,''))='';" 2>/dev/null || echo '?')"
STATUS_DIST="$(sqlite3 "$PENDING" "SELECT group_concat(s,'  ') FROM (SELECT analysis_status||' '||COUNT(*) AS s FROM news GROUP BY analysis_status);" 2>/dev/null || echo '?')"

# ---------- 4. 与线上对比，算真实 RPO ----------
# 卷定位失败不算致命：演练可以在任意装了 sqlite3 的机器上跑，
# 那台机器没有 docker 也应该能验证备份本身。
VOL=""; MOUNT=""; TARGET=""
LIVE_ROWS="?"; LIVE_MAX_TS=0
try_locate() {
  command -v docker >/dev/null 2>&1 || return 1
  local v
  if [[ -n "$VOLUME_NAME" ]]; then v="$VOLUME_NAME"
  else v="$(docker volume ls -q --filter "label=com.docker.compose.volume=$VOLUME_LABEL" 2>/dev/null | head -1)"; fi
  [[ -n "$v" ]] || return 1
  MOUNT="$(docker volume inspect "$v" --format '{{.Mountpoint}}' 2>/dev/null)" || return 1
  [[ -n "$MOUNT" && -d "$MOUNT" ]] || return 1
  VOL="$v"; TARGET="$MOUNT/$DB_FILE"
  [[ -f "$TARGET" ]] || return 1
  LIVE_ROWS="$(sqlite3 "$TARGET" 'SELECT COUNT(*) FROM news;' 2>/dev/null || echo '?')"
  LIVE_MAX_TS="$(sqlite3 "$TARGET" 'SELECT COALESCE(MAX(created_at),0) FROM news;' 2>/dev/null || echo 0)"
  return 0
}
HAVE_LIVE=0
try_locate && HAVE_LIVE=1 || true

report() {
  local size; size="$(du -h "$SRC" | cut -f1)"
  echo
  echo "──────────────────────────────────────────────"
  echo "  备份体检：$(basename "$SRC")  ($size)"
  echo "──────────────────────────────────────────────"
  printf '  gzip            完整\n'
  printf '  integrity_check %s\n' "$INTEGRITY"
  printf '  表结构          news 表存在\n'
  echo
  printf '  行数            %s\n' "$NEW_ROWS"
  printf '  最早入库        %s\n' "$(ts_fmt "$NEW_MIN_TS")"
  printf '  最后入库        %s\n' "$(ts_fmt "$NEW_MAX_TS")"
  if (( NEW_MAX_TS > NEW_MIN_TS )); then
    printf '  数据跨度        %s\n' "$(fmt_dur $((NEW_MAX_TS - NEW_MIN_TS)))"
  fi
  echo
  printf '  标题为空        %s / %s\n' "$TITLE_EMPTY" "$NEW_ROWS"
  printf '  正文为空        %s / %s\n' "$BODY_EMPTY" "$NEW_ROWS"
  printf '  分析状态        %s\n' "$STATUS_DIST"

  if (( HAVE_LIVE )); then
    echo
    echo "  ── 与线上对比 ──"
    printf '  线上行数        %s' "$LIVE_ROWS"
    if [[ "$LIVE_ROWS" =~ ^[0-9]+$ && "$NEW_ROWS" =~ ^[0-9]+$ ]]; then
      printf '（备份少 %s 行）' $((LIVE_ROWS - NEW_ROWS))
    fi
    echo
    printf '  线上最后入库    %s\n' "$(ts_fmt "$LIVE_MAX_TS")"
    if (( LIVE_MAX_TS > NEW_MAX_TS && NEW_MAX_TS > 0 )); then
      echo "  ▶ RPO          落后 $(fmt_dur $((LIVE_MAX_TS - NEW_MAX_TS)))"
    else
      echo "  ▶ RPO          没有落后（备份至少和线上一样新）"
    fi
  else
    echo
    echo "  （没找到线上库，跳过 RPO 对比。在没装 docker 的机器上跑演练时正常）"
  fi
  echo "──────────────────────────────────────────────"
}

# ---------- 5. 演练：报告完就退出，一个字节都不写 ----------
if (( DRILL )); then
  report
  if [[ "$NEW_ROWS" == "0" ]]; then
    echo
    echo "  警告：这份备份里 news 是 0 行。要么备份时库确实是空的，"
    echo "       要么备份脚本备到了错的文件 —— 后者更常见，别把它当好备份。"
  fi
  echo
  say "演练结束，线上未做任何修改。"
  exit 0
fi

# ---------- 6. 真恢复前的确认 ----------
[[ "$VOL" != "" ]] || { try_locate || die "找不到 compose 卷（label com.docker.compose.volume=$VOLUME_LABEL）。可用 VOLUME_NAME 指定卷名。"; }
say "目标库：$TARGET"

report

cat <<EOF
  即将把线上数据库替换为这份备份。
  当前库会被另存为 news-before-restore-<时间>.db（失败可退回）。
EOF

if (( ASSUME_YES )); then
  echo
  echo "  ⚠ 已指定 --yes，跳过确认，3 秒后开始覆盖。"
  sleep 3
else
  # 不用 read -p 配默认值：这里必须是显式输入 yes，
  # 回车、y、Y 一律当作取消 —— 恢复是不可逆操作，不该有「顺手一按」的路径。
  echo
  printf '确认继续？输入 yes 回车：'
  read -r ANSWER
  [[ "$ANSWER" == "yes" ]] || { echo "已取消，未做任何修改。"; exit 1; }
fi

# ---------- 7. 停服务 ----------
say "停止容器"
( cd "$REMOTE_DIR" && docker compose stop ) || die "停止容器失败，未做任何修改"

# ---------- 8. 保存现场 ----------
if [[ -f "$TARGET" ]]; then
  SAVED="$MOUNT/news-before-restore-$(date '+%Y%m%d_%H%M%S').db"
  say "当前库另存为 $(basename "$SAVED")"
  # 此时容器已停，没有写入；但 WAL 里可能仍有未 checkpoint 的数据，
  # 所以用 .backup 而不是 cp，保证这一份「现场」自己也是一致的。
  sqlite3 "$TARGET" ".backup '$SAVED'" || die "保存现场失败，已中止（容器仍处于停止状态）"
fi

# ---------- 9. 覆盖 ----------
say "写入恢复数据"
# 先删 WAL/SHM，再覆盖主库（顺序不能反，理由见文件头注释）
rm -f "$TARGET-wal" "$TARGET-shm"
cp "$PENDING" "$TARGET"
# 容器内以 app 用户运行（Dockerfile.runtime 里 useradd -r app），
# 属主不对会导致启动正常、第一次写入就 read-only。
chown 999:999 "$TARGET" 2>/dev/null || true   # 取不到 app 的 uid 就保留现状，由容器自行处理
chmod 644 "$TARGET"

# ---------- 10. 启动并验证 ----------
say "启动容器"
( cd "$REMOTE_DIR" && docker compose up -d ) || die "启动失败。手动查看：cd $REMOTE_DIR && docker compose logs --tail=80"

say "等待服务健康（最多 90 秒）"
PORT="$(grep -E '^HOST_PORT=' "$REMOTE_DIR/.env" 2>/dev/null | cut -d= -f2)"
PORT="${PORT:-8080}"
for i in $(seq 1 30); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/healthz" 2>/dev/null || echo 000)"
  if [[ "$CODE" == "200" ]]; then
    # healthz 只证明进程活着，还证明不了数据对。再核一次恢复后库里的行数。
    AFTER_ROWS="$(sqlite3 "$TARGET" 'SELECT COUNT(*) FROM news;' 2>/dev/null || echo '?')"
    say "服务已就绪（/healthz 200），库内 $AFTER_ROWS 行"
    if [[ "$AFTER_ROWS" != "$NEW_ROWS" ]]; then
      echo "  ⚠ 行数与备份不一致（备份 $NEW_ROWS / 现在 $AFTER_ROWS）。"
      echo "    可能是恢复了备份之后应用又采集到了新数据；如果差很多，去查日志。"
    fi
    echo
    say "恢复完成。建议立刻做一次新备份，确认备份链路仍然正常："
    say "  $REMOTE_DIR/deploy/cloud/backup.sh"
    exit 0
  fi
  sleep 3
done

die "90 秒内未就绪。看日志：cd $REMOTE_DIR && docker compose logs --tail=80
     若要退回恢复前状态，把 $MOUNT/news-before-restore-*.db 按同样步骤放回去。
     注意：放回去时同样要先删 news.db-wal 和 news.db-shm。"
