#!/usr/bin/env bash
#
# SQLite 在线备份（不停服务、不加锁、不留半个事务）。
#
# 在**目标服务器上**执行，不是在 CI 里跑 —— 它要直接读 Docker 卷的真实路径。
#
# 用法：
#   ./backup.sh                    默认备份到 /opt/cls-news-backup，保留 7 天
#   KEEP_DAYS=30 ./backup.sh       改保留天数
#   OSS_URL=oss://my-bucket/cls-news ./backup.sh   额外上传一份到 OSS（需 ossutil）
#
# 退出码：0 = 成功，非 0 = 失败（systemd timer 会记为 failed）
#
# ── 为什么不能用 cp / docker cp / tar ────────────────────────────────
# 数据库跑在 journal_mode=WAL + synchronous=NORMAL 下，这意味着：
#   1. 最近的写入可能还躺在 news.db-wal 里，没 checkpoint 回主库；
#   2. 主库文件在写入过程中会短暂处于「页已改、校验未完成」的中间态。
# 所以 `cp news.db` 丢数据，`cp news.db news.db-wal` 也不是原子的 ——
# 两个文件分别拷贝之间，应用还在写，拼起来可能是一个从未存在过的状态。
# 恢复出来能打开、能查询，但某几条记录的内容是坏的。这种备份比没有更危险，
# 因为它会让人以为安全。
#
# `.backup` 走的是 SQLite 的 Online Backup API：在源库上开读事务逐页拷贝，
# 拷贝期间应用照常读写，拿到的是某一次提交的完整快照。
#
# ── 为什么在宿主机跑而不是 docker exec ──────────────────────────────
# 运行镜像是 eclipse-temurin:17-jre，里面只有 JRE，没有 sqlite3 命令。
# 装一个带 sqlite3 的临时镜像还要能拉到镜像 —— 这台机器连 registry-1.docker.io
# 都超时（见 deploy.yml 的注释），所以最省事的是在宿主机 apt 装一次 sqlite3。
#
# ── 为什么备份完还要 integrity_check ────────────────────────────────
# 磁盘坏道、写满、进程被 kill 都可能留下一个「文件存在、大小正常、但内容损坏」的库。
# 不校验的话，这个坏备份会一直躺在目录里，直到某天真的需要它。
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/opt/cls-news-backup}"
KEEP_DAYS="${KEEP_DAYS:-7}"
DB_FILE="${DB_FILE:-news.db}"
VOLUME_LABEL="${VOLUME_LABEL:-cls-data}"
VOLUME_NAME="${VOLUME_NAME:-}"
MIN_FREE_MB="${MIN_FREE_MB:-500}"

ts()  { date '+%Y-%m-%d %H:%M:%S'; }
say() { printf '[%s] %s\n' "$(ts)" "$*"; }
die() { printf '[%s] [fatal] %s\n' "$(ts)" "$*" >&2; exit 1; }

# ---------- 0. 前置检查 ----------
# 缺 sqlite3 就直接失败，不做「跳过备份继续跑」的降级：
# 一个会静默跳过的备份任务，和没有备份任务的区别只是让人更安心。
command -v sqlite3 >/dev/null 2>&1 || die \
  "宿主机没有 sqlite3。执行：apt-get install -y sqlite3  （约 1MB，一次性）"
command -v docker  >/dev/null 2>&1 || die "没有 docker 命令"

# ---------- 1. 找到卷和库文件 ----------
# 不写死 cls-news_cls-data：compose 的卷名前缀取自项目目录名，
# 换个目录部署（或改名）前缀就变了，写死只会在换机器时炸。
discover_volume() {
  if [[ -n "$VOLUME_NAME" ]]; then
    printf '%s' "$VOLUME_NAME"
    return
  fi
  local found
  found="$(docker volume ls -q --filter "label=com.docker.compose.volume=$VOLUME_LABEL" 2>/dev/null | head -1)"
  if [[ -n "$found" ]]; then
    printf '%s' "$found"
    return
  fi
  # 卷不存在（还没部署过）时给个明确错误，而不是让 inspect 抛一句看不懂的话
  die "找不到 compose 卷（label com.docker.compose.volume=$VOLUME_LABEL）。服务还没部署过？"
}

VOL="$(discover_volume)"
MOUNT="$(docker volume inspect "$VOL" --format '{{.Mountpoint}}' 2>/dev/null)" \
  || die "docker volume inspect $VOL 失败"
DB="$MOUNT/$DB_FILE"
[[ -f "$DB" ]] || die "卷里找不到 $DB —— 确认 DB_PATH 是 /app/data/news.db"
say "源库：$DB"

# ---------- 2. 空间检查 ----------
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"   # 备份里有全部正文，目录不能对所有人可读

DB_MB=$(( $(stat -c %s "$DB" 2>/dev/null || stat -f %z "$DB") / 1024 / 1024 + 1 ))
FREE_MB="$(df -Pm "$BACKUP_DIR" | awk 'NR==2{print $4}')"
# 按「库大小 × 3」估：临时文件 + gz 后的副本 + 余量。
# 磁盘写满时 SQLite 的表现是写入静默失败，排查起来极难，所以在这里提前拦住。
NEED_MB=$(( DB_MB * 3 > MIN_FREE_MB ? DB_MB * 3 : MIN_FREE_MB ))
if (( FREE_MB < NEED_MB )); then
  die "备份目录所在分区只剩 ${FREE_MB}MB，需要约 ${NEED_MB}MB。先清理旧备份或扩容。"
fi

# ---------- 3. 在线备份 ----------
STAMP="$(date '+%Y-%m-%d_%H%M')"
TARGET="$BACKUP_DIR/news-$STAMP.db"
TMP="$TARGET.part"

# .part 后缀是刻意的：中途失败时目录里会留一个半成品，
# 但循环清理只认 *.db.gz，所以它不会被误当成一份可用的备份。
# 必须连 -wal / -shm 一起清：.backup 建出来的目标库继承了源库的 WAL 模式，
# SQLite 会在它旁边生成这两个附属文件，只删主文件会在备份目录里越积越多。
cleanup_tmp() { rm -f "$TMP" "$TMP-wal" "$TMP-shm"; }
trap cleanup_tmp EXIT

say "开始在线备份（${DB_MB}MB）"
if ! sqlite3 "$DB" ".backup '$TMP'"; then
  die "备份失败。若是 'database is locked'，说明有长事务占着写锁，稍后重试。"
fi
[[ -s "$TMP" ]] || die "备份文件为空"

# 把目标库 WAL 里的残余页强制推回主文件，并截断 WAL。
# 不做这一步的风险很具体：下面 gzip 只打包 $TMP 这一个文件，
# 如果还有数据躺在 $TMP-wal 里，压缩包里就是一份缺了尾部写入的库 ——
# 而 integrity_check 照样 ok，因为它本来就是个合法状态，只是少了几条。
# TRUNCATE 而非 PASSIVE：要的是「WAL 归零」，不留尾巴。
sqlite3 "$TMP" 'PRAGMA wal_checkpoint(TRUNCATE);' >/dev/null 2>&1 || true

# ---------- 4. 校验 ----------
# 这两步是整份脚本的重点。不校验的备份等于没有备份 —— 只是没人知道而已。
INTEGRITY="$(sqlite3 "$TMP" 'PRAGMA integrity_check;' 2>&1)"
if [[ "$INTEGRITY" != "ok" ]]; then
  die "integrity_check 未通过：$INTEGRITY
       备份不可用。若源库同时报错，说明线上数据已经损坏，优先查磁盘。"
fi

ROWS="$(sqlite3 "$TMP" 'SELECT COUNT(*) FROM news;' 2>&1)"
[[ "$ROWS" =~ ^[0-9]+$ ]] || die "统计行数失败：$ROWS"
# 空库不算致命（全新部署确实可能是 0），但要说出来：
# 线上明明有数据、备份却是空的时候，多半是备到了错的库文件。
if (( ROWS == 0 )); then
  say "警告：备份里的 news 表为 0 行。如果线上有数据，说明备错了库。"
else
  say "校验通过：integrity ok，news $ROWS 行"
fi

# ---------- 5. 压缩落盘 ----------
gzip -9 -c "$TMP" > "$TARGET.gz"
cleanup_tmp
# gz 写完立刻验证能被完整解开：网络传输中断、磁盘满都可能留下截断的 gz，
# 而 .gz 的截断在 gunzip 之前完全看不出来。
gzip -t "$TARGET.gz" || { rm -f "$TARGET.gz"; die "gzip 完整性校验失败，已删除损坏文件"; }
chmod 600 "$TARGET.gz"
say "已生成 $(du -h "$TARGET.gz" | cut -f1)  $TARGET.gz"

# ---------- 6. 异地副本（可选） ----------
# 同一块盘上的备份只能防误删和逻辑损坏，防不了磁盘故障和整机丢失。
# 配了 OSS_URL 就多一份异地；没配不报错，但打印提醒 —— 别让「以为有异地」变成隐患。
if [[ -n "${OSS_URL:-}" ]]; then
  if command -v ossutil >/dev/null 2>&1; then
    ossutil cp "$TARGET.gz" "${OSS_URL%/}/" -f >/dev/null \
      && say "已上传异地：${OSS_URL%/}/$(basename "$TARGET.gz")" \
      || say "警告：上传 OSS 失败，本地副本仍然有效"
  else
    say "警告：设了 OSS_URL 但没有 ossutil，跳过异地备份"
  fi
else
  say "提示：未配置 OSS_URL，本次只有本地副本（同盘，防不了磁盘故障）"
fi

# ---------- 7. 清理过期 ----------
# -mtime 用的是修改时间，与文件名里的时间戳一致；
# 按时间删而不是按「保留 N 个」，可以避免备份频率变化时把保留期算错。
DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -name 'news-*.db.gz' -type f -mtime "+$KEEP_DAYS" -print -delete 2>/dev/null | wc -l)"
(( DELETED > 0 )) && say "清理过期备份 $DELETED 份（保留 ${KEEP_DAYS} 天）"

COUNT="$(find "$BACKUP_DIR" -maxdepth 1 -name 'news-*.db.gz' -type f | wc -l)"
TOTAL="$(du -sh "$BACKUP_DIR" 2>/dev/null | cut -f1)"
say "完成。现有 $COUNT 份，占用 $TOTAL"
