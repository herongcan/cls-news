# 部署上线

这份文档分三块：**线上现在到底是什么样**、**换机器怎么从零重建**、**日常运维/备份/排障**。

> 分工：[deploy/cloud/README.md](deploy/cloud/README.md) 是逐步命令版（含备份恢复的全部细节），
> 本文件偏「为什么这么定、边界在哪、出事看哪里」。两份有重叠，但不要一份写完另一份就不管 ——
> 重叠部分以本文件为准（它是按线上实测状态写的）。

---

## 零、线上现状（一页速览）

2026-11-13 在服务器上实测的数字，不是估计：

| 项 | 现状 |
|---|---|
| 机器 | 阿里云 ECS，杭州，2 vCPU / 1740 MB / 40G 系统盘（已用 16%），Ubuntu 24.04.5 LTS |
| 入口 | `https://news.herongcan.icu`（Cloudflare Tunnel，证书由 CF 边缘签发）；`http://8.136.19.57:8080` 仍可直连，作为隧道故障时的退路 |
| 容器 | `cls-news:0.1.0`（运行镜像 568MB，基础 JRE 432MB），`mem_limit 512m`，`restart: unless-stopped` |
| 实测占用 | 常驻 198 MiB / 512 MiB，CPU 0.09%，`load average 0.04` |
| 数据 | 命名卷 `cls-news_cls-data` → `/app/data/news.db`（WAL 模式） |
| 备份 | `/opt/cls-news-backup`，systemd timer 每天 03:30（`Persistent=true`），保留 7 天，一份约 230KB |
| 明细清理 | 应用内 `AccessLogCleaner` 每天 04:40 删 90 天前的 `access_log` |
| 发版 | 推 `main` → GitHub Actions 编译 → rsync → 远端 `docker compose up -d --build` |
| 账号 | 单账号 Basic，十来个人共用（**弱项，见第六节**） |
| 域名 | `news.herongcan.icu` CNAME → `<tunnel-id>.cfargotunnel.com`。**未做 ICP 备案**，靠 Tunnel 出站长连接绕开拦截，公网零入站端口 |

服务端依赖只有四样：Docker 29.1.3（compose 2.40.3）、宿主机 `sqlite3` 3.45.1（备份脚本用）、`cloudflared` 2026.11.0（systemd 常驻）、22 + 8080 两个开放端口。
没有 Nginx/Caddy、没有外部数据库、没有 Redis —— 一个容器、一个卷、一条出站隧道，就这些。

---

## 一、机器怎么选（当前这台就是按这个标准买的）

| 维度 | 结论 | 理由 |
|---|---|---|
| 地域 | 国内（杭州） | 财联社 API 在境内，海外机房实测会被风控，表现为「时好时坏 + 日志全是超时」 |
| 规格 | 2 核 2G 足够，1 核 1G 也能跑 | 容器限 512m，实测常驻 198MiB；**但 1G 机器必须加 swap**，否则 OOM killer 只留一句 `Killed` |
| 磁盘 | 40G 系统盘 | 库现在 600KB 级，日志有 `max-size: 10m / max-file: 3` 兜着，磁盘不是瓶颈 |
| 计费 | 包年包月 | 不只是便宜：**按量付费/试用实例不生成备案服务码**，这就是域名现在还用不了的原因 |
| 系统 | Ubuntu 24.04 LTS | 命令都按这个写；22.04 也行 |

**不要买香港/新加坡节点**：便宜一点，但财联社对海外 IP 有风控。

### 为什么不直接用云数据库/托管服务

- **不上 RDS**：现在库是 600KB、日增 400 行、单实例读写，换 RDS 只增加网络往返和月度账单。
  但要注意：**上 RDS 之前必须先补测试**，因为现有 SQL 里用了 `UPDATE ... RETURNING`
  （MySQL 不支持）、`INSERT ... ON CONFLICT`（PG 语法不同），不是改连接串就能切的。
- **不上 K8s**：SQLite 的 POSIX 文件锁只在单机有效，NFS/多节点共享被 SQLite 官方明确禁止（会损坏库）。
  要做高可用必须先把存储换掉，那是另一个项目。

---

## 二、从零重建（换机器时照这一节做）

前提：你手上有仓库写权限、能拿到 DeepSeek key、能改 DNS。

### 1. 系统准备

```bash
# 装 Docker（国内用阿里云镜像源，官方源在大陆机器上会卡在 TLS 握手）
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
systemctl enable --now docker

# 备份脚本要用宿主机的 sqlite3：运行镜像是 JRE，里面没有这个命令
apt-get update && apt-get install -y sqlite3

docker --version && docker compose version && sqlite3 --version
```

2G 及以下内存的机器加 swap 当保险（不是可选项）：

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

安全组只开两个端口：**22**（GitHub runner 要能 ssh 进来）和 **8080**（同事访问）。
80/443 不用开，以后也不用 —— HTTPS 走 Cloudflare Tunnel，公网零入站端口。

### 2. 放代码

```bash
ssh root@服务器IP 'mkdir -p /opt/cls-news'
rsync -az --exclude '.git' --exclude '.idea' --exclude '.env' \
  --exclude 'data' --exclude 'logs' --exclude '*.log' \
  ./ root@服务器IP:/opt/cls-news/
```

`.env` 必须排除在 rsync 之外 —— 里面是密码和 key，`--delete` 会把它删掉。

### 3. 写 `.env`（线上就是这 10 行）

```ini
TZ=Asia/Shanghai
DB_PATH=/app/data/news.db
APP_AUTH=true
APP_USER=admin
APP_PASSWORD=换成你自己的密码
BIND_ADDR=0.0.0.0
HOST_PORT=8080
MEM_LIMIT=512m
DOCKERFILE=deploy/cloud/Dockerfile.runtime
DEEPSEEK_API_KEY=sk-xxxx
```

`chmod 600 .env`。两个最容易错的：

- **`DOCKERFILE` 不写**就回落到根目录的多阶段 Dockerfile，远端要现拉 500MB 的 maven 镜像，
  4M 带宽十几分钟，还容易被 OOM —— 而 `cls-news:0.1.0` 只需要一个 36MB 的 jar + JRE。
- **`MEM_LIMIT`** 按 `free -m` 的总内存定：≤1000MB 用 `384m`（1G 机器）、≤1600MB 用 `448m`、否则 `512m`。
  写大了会被 OOM killer 反复杀，症状是「容器过一会儿就重启」。

> 不要留空 `APP_PASSWORD`。开了鉴权但密码为空时进程会**直接退出**，这是故意的：
> 「以为开了鉴权」比「明说没开」危险得多。

### 4. 构建并启动

```bash
ssh root@服务器IP 'cd /opt/cls-news && docker compose up -d --build'
ssh root@服务器IP 'cd /opt/cls-news && docker compose ps'      # 等 STATUS 变 healthy，约 20-40 秒
```

首次启动会立刻抓一轮，几秒后库里就有 50 条。

### 5. 验收（四条，缺一不可）

```bash
curl -s localhost:8080/healthz                      # ① 必须 {"status":"UP",...}
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/status   # ② 必须 401
curl -s -o /dev/null -w '%{http_code}\n' -u admin:密码 localhost:8080/api/status  # ③ 必须 200
curl -s -u admin:密码 localhost:8080/api/status | grep -o '"analysisReady":[a-z]*'  # ④ 看 key 生效没
```

第 ② 条最容易被跳过也最要命：**返回 200 说明 `.env` 没被读到**，全文库正在公网上裸奔。
排查用 `docker compose config | grep APP_AUTH`。

`analysisReady=false` 说明 key 没配好。这时采集和页面都正常，只是点「分析」会拿 503 ——
补上 key 后 `docker compose up -d` 即可，不需要 `retry-failed`。

### 6. 装备份

```bash
# 手动跑一次，确认能通
/opt/cls-news/deploy/cloud/backup.sh

# 装成 systemd timer（每天 03:30，Persistent=true 补跑）
cp /opt/cls-news/deploy/cloud/cls-news-backup.{service,timer} /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now cls-news-backup.timer
systemctl list-timers cls-news-backup.timer
```

细节见第五节和 [deploy/cloud/README.md](deploy/cloud/README.md)。

### 7. 接上自动发版

仓库 Settings → Secrets and variables → Actions：

| 名称 | 值 |
|---|---|
| `SSH_HOST` | 纯 IP，**不带 `root@`**（`ssh-keyscan` 不认） |
| `SSH_KEY` | 部署私钥全文，含 `-----BEGIN/END-----` 两行，**不要 passphrase**（CI 里没有终端） |
| `DEEPSEEK_API_KEY` | 可选；不配就沿用远端 `.env` 里已有的 |

私钥用 `ssh-keygen -t ed25519 -N '' -f ~/.ssh/cls-news-deploy` 生成，公钥 `ssh-copy-id` 到服务器。
之后每次推 `main` 就自动发版，不用再开终端。

---

## 三、日常运维

```bash
cd /opt/cls-news

docker compose logs -f --tail=100      # 实时日志（分析失败、采集超时都在这）
docker compose restart                 # 改了 .env 之后
docker compose ps                      # 看 healthy 和重启次数
docker stats --no-stream cls-news      # 内存/CPU 实际占用
```

### 改东西的正确姿势

| 改什么 | 怎么做 | 要不要重建镜像 |
|---|---|---|
| 密码、API key、抓取间隔 | 改 `.env` → `docker compose up -d` | 不要 |
| Java 代码 / 提示词 | 本地提交 → 推 `main` → Actions 自动发版 | 要（打在 jar 里） |
| 应急：直接改线上代码 | `./deploy/cloud/deploy.sh root@IP` 手动推一次 | 要 |

> 提示词改一次就要重建镜像，是这个设计的已知代价。好处是配置和代码一起版本化，
> 不会出现「线上提示词和仓库里不一致」。真变频繁了就把 `prompts/` 挂成卷。

**改密码没有热更新**：`BasicAuthFilter` 继承 `OncePerRequestFilter`，它的 `doFilter` 是 `final`，
CGLIB 生成不了刷新代理，所以接 Nacos 之类的配置中心也刷新不到它。换密码 = 改 `.env` + 重启容器。

### 三种「定时任务」别混

| 时间 | 谁跑的 | 干什么 |
|---|---|---|
| 每 90 秒 | 应用内 `IngestJob` | 抓一轮财联社（`app.interval-ms`） |
| 每天 03:30 | systemd `cls-news-backup.timer` | `sqlite3 .backup` → 校验 → gzip → 清理 7 天前 |
| 每天 04:40 | 应用内 `AccessLogCleaner` | 删 90 天前的访问明细（错开备份） |

### 查数据

运行镜像是 JRE，**里面没有 sqlite3**，别用 `docker compose exec` 去查。两条路：

```bash
# ① 接口（首选，页面和脚本都用它）
curl -s -u admin:密码 'localhost:8080/api/status' | python3 -m json.tool
curl -s -u admin:密码 'localhost:8080/api/visits/logs?limit=50'
curl -s -u admin:密码 'localhost:8080/api/visits/logs?day=2026-11-12&device=wechat'
curl -s -u admin:密码 'localhost:8080/api/visits/logs?format=csv' -o visits.csv

# ② 宿主机上只读查库（一定要 -readonly，别抢写锁）
DB=/var/lib/docker/volumes/cls-news_cls-data/_data/news.db
sqlite3 -readonly "$DB" "select analysis_status, count(*) from news group by 1"
sqlite3 -readonly "$DB" "select day, count(distinct ip) uv, sum(hits) pv from visit group by day order by day desc"
```

> 8080 是直接映射到公网的，所以**在服务器本机 curl `localhost:8080` 时，`remoteAddr` 是 Docker 网桥网关
> `172.18.0.1`**（走了 docker-proxy），不是真实客户端 IP。外部访问走 iptables DNAT，源 IP 是保留的。
> 所以 `/api/visits/logs` 里 `requesterIp=172.18.0.1` 只在你自己本机自测时出现，不是 bug。

---

## 四、发版与回滚

**常规路径：推 `main`。** 流水线：编译（runner 上）→ 校验 fat jar → `ssh-keyscan` 写 `known_hosts`
→ `deploy.sh`（连通性 → 复用远端 key → rsync → 写 `.env` → compose → healthz 轮询）→ 失败自动抓远端日志。

一次完整发版实测 **56 秒**，其中容器重启窗口 20-40 秒，期间页面打不开（SQLite 单实例，不能滚动更新）。

**`paths-ignore` 会挡掉三类改动**，所以「推了 main 但线上没变」先看这个：

```yaml
paths-ignore:
  - '**.md'            # 只改文档不发版
  - 'deploy/laptop/**'
  - 'tools/eval/**'
```

**回滚**：没有自动回滚，镜像 tag 固定 `cls-news:0.1.0`，新镜像会覆盖旧的。正确做法是
`git revert <坏提交>` 再推一次。急的时候可以直接 `docker compose up -d`（不重建）先把容器拉起来，
但代码还是新的 —— 别把「重启成功」当成「回滚成功」。

---

## 五、备份与恢复

数据只有一个 SQLite 文件，丢了就是全丢。**这条是唯一不能省的运维动作。**

### 为什么不能 `cp` / `docker cp` / `tar`

库跑在 `journal_mode=WAL` + `synchronous=NORMAL` 下，最近的写入可能还躺在 `-wal` 里没回主库。
实测：造一个 2 行的库、让其中 1 行只在 WAL 里，然后两种方式各拷一次 ——

| 方式 | 备份里的数据 |
|---|---|
| `cp news.db` | 1 行，WAL 里那条没了 |
| `sqlite3 news.db ".backup ..."` | 2 行，完整 |

那次实验里主库 8192 字节、`-wal` 12392 字节：**WAL 比主库还大**，`cp` 丢的不是一点点。
更糟的是两个文件分别拷贝之间应用还在写，拼起来可能是从未存在过的状态 —— 能打开、能查询、内容是坏的。

### `backup.sh` 做四件事

1. `sqlite3 .backup`（Online Backup API）拿一致快照，不停服务、不加锁；
2. 对副本跑 `PRAGMA integrity_check`，不是 `ok` 直接失败，不产出「看起来成功」的坏备份；
3. `PRAGMA wal_checkpoint(TRUNCATE)` + 删副本的 `-wal`/`-shm`（否则恢复时会回放不属于它的 WAL）；
4. gzip + 权限 600 落盘 `/opt/cls-news-backup`，清 7 天前的；配了 `OSS_URL` 再传一份异地。

```bash
systemctl list-timers cls-news-backup.timer     # 下次/上次执行时间
journalctl -u cls-news-backup -n 30 --no-pager  # 失败原因
ls -lh /opt/cls-news-backup                     # 现有备份
```

### 恢复：先演练，别拿生产练手

```bash
./deploy/cloud/restore.sh                      # 列出可用备份
./deploy/cloud/restore.sh --drill              # 只读演练最新一份，一个字节都不写
./deploy/cloud/restore.sh <文件> --drill       # 演练指定的那份
./deploy/cloud/restore.sh <文件>               # 真恢复，必须手打 yes
./deploy/cloud/restore.sh <文件> --yes         # 跳过确认，只给脚本用
```

演练输出重点是最后三行的 **RPO**（备份落后线上多久）。实测一次：备份 678 行 / 线上 768 行 /
落后 2 小时 2 分 / 分析状态分布与线上一致 —— 说明备份可用、只是不够新。

真恢复会：校验 gzip → 校验 integrity → 打印体检报告 → 要求手打 `yes` → 停容器 →
把当前库另存为 `news-before-restore-*.db` → **无条件删 `-wal`/`-shm`** → 覆盖 → 起容器 →
轮询 `/healthz` → 核对行数。全程约 30 秒。

> **删 `-wal` 这一步不能省**。实测过：库里有 v1/v2，备份出 b.db，之后写入 v3（进了 WAL），
> 用 b.db 覆盖主库但不删 `-wal`，再打开得到 `v1, v2, v3` —— v3 被复活了，而且 `integrity_check` 返回 **ok**。
> 既不是备份也不是现场的第三种状态，只能靠人肉核对行数发现。

### 当前备份的缺口（说白了）

| 缺口 | 后果 | 补法 |
|---|---|---|
| 备份和库在**同一块盘** | 磁盘坏 / 整机丢 = 数据和备份一起没 | 配 `OSS_URL`（`cls-news-backup.service` 里加一行），或 rsync 到另一台机器 |
| **RPO 24 小时** | 最坏丢一天数据 | `OnCalendar` 由 `daily` 改 `hourly`，脚本不用动（库才 600KB） |
| 部署前不自动备份 | 改坏代码后重启，回不到上一个数据状态 | 在 `deploy.sh` 的 `compose up` 前加一次 `backup.sh`，失败就中止 |

---

## 六、已知缺口（不粉饰）

| 缺口 | 影响 | 什么时候必须补 |
|---|---|---|
| **HTTPS 链路慢** | 已上 Tunnel（`https://news.herongcan.icu`），但 CF 免费版给境内分配到美西/欧洲 PoP，实测首字节 1.2–3s、偶发 10s+，比 IP 直连的 69ms 差一个量级 | 想快就迁香港/新加坡轻量，或把备案办下来走境内直连 |
| **8080 仍对公网开放** | 能绕过 Cloudflare 直连源站；`APP_TRUST_XFF=true` 下可伪造 IP 污染统计 | 隧道稳定后收掉安全组入方向的 8080 |
| **单账号共用** | 十来个人一个密码，谁泄露都查不到是谁；`/api/visits/logs` 能看所有人 IP | 想认真管权限时改成一人一号（要动代码，`BasicAuthFilter` 只支持单账号） |
| **单实例** | 不能滚动更新，重启有 20-40 秒不可用 | 需要高可用时换 Postgres + 改认领逻辑 |
| **没有监控告警** | 挂了只有等人发现 | 加个定时 `curl /healthz` + 告警（最省事的是外部拨测服务） |
| **`fulltext=true`** | 每个登录的人都能看财联社全文，性质是「小范围转载」 | 随时可改 `false`（只返回 60 字摘要 + 原文链接），代价很小 |
| **`src/test` 为空** | 改 SQL 全靠手点验证 | 换数据库之前必须先补 |

---

## 七、故障排查（都是线上真实遇到过的）

| 现象 | 最可能的原因 | 怎么确认 |
|---|---|---|
| 浏览器打开是运营商「域名暂时无法访问」 | 域名未备案，**按 Host 头拦截，跟端口无关** | 换成 `IP:8080` 能开就是它；走 `https://news.herongcan.icu` 则不受此限（流量不经过备案层） |
| `https://news.herongcan.icu` 卡住十几秒或 502 | cloudflared 掉线，或 QUIC 在跨境链路上超时 | `journalctl -u cloudflared -n 30`；确认 `config.yml` 里是 `protocol: http2` |
| 走隧道后访客 IP 全变成 `127.0.0.1` | `.env` 少了 `APP_TRUST_XFF=true`，或 `deploy.sh` 重写 `.env` 时冲掉了 | `grep TRUST_XFF /opt/cls-news/.env` |
| 推了 `main` 但线上代码没变 | 提交里只有 `.md` 等被 `paths-ignore` 的文件 | `gh run list` 根本没有新记录 |
| 部署成功但页面报「需要登录」 | 正常，页面内的登录层在等密码 | 输一次密码；或 `curl -u` 验接口 |
| 用 `curl localhost:8080` 查访客看到 `172.18.0.1` | 本机请求走了 docker-proxy，源地址被改写 | 从外部机器访问就是真实 IP |
| `/api/visits/logs` 结果很少 | `access_log` 是 11-13 部署时才建的，历史明细永久没有 | `sqlite3` 查 `visit` 表看部署前的聚合数据 |
| 容器反复重启 | `MEM_LIMIT` 给大了，被 OOM killer 杀 | `docker inspect cls-news \| grep -i oomkilled` |
| 容器一直 `unhealthy` | `/healthz` 读库失败（磁盘满、卷没挂上） | `docker exec cls-news ls -l /app/data`、`df -h` |
| 页面上有凭据但要不到数据 | `DB_PATH` 没指向 `/app/data/news.db` | `docker compose config \| grep DB_PATH` |
| 启动直接退出 | `APP_AUTH=true` 但密码为空 | `docker compose logs \| tail -30` 会写明 |
| `total` 不增长 | 财联社改接口 / 被风控 | `/api/status` 看 `lastError` 和 `runs` |
| `failed` 在涨 | key 失效或余额不足 | `docker compose logs \| grep 分析失败`，修好后 `POST /api/analysis/retry-failed` |
| 日志时间差 8 小时 | 容器没设 `TZ` | `docker exec cls-news date` |
| 想清数据重来 | `down` 不删数据，**`down -v` 会删卷** | 别手滑 |

---

## 八、最后几条实话

1. **它现在不是「只有你能打开的链接」，是十来个人共用一个密码的站点。**
   这个变化不是小事：全文字段 + 公网可访问 + 多人共用账号，三件事同时成立时，性质就是「小范围转载」。
   守住风险的办法只有两个 —— 改 `fulltext=false`，或者把账号收成一人一号。别靠「应该没人会传出去」。

2. **账号密码是现在最薄的一层。** 一个共用密码、没有 HTTPS、没有登录日志（`access_log` 只记访客不记操作），
   出了事既防不住也查不到。它比「加功能」「上 K8s」都更值得先处理。

3. **别把「采集不到新消息」当成故障。** 财联社约 30 条/小时，夜里更少，打开发现没更新是正常的。
   把间隔调到 10 秒只会招来风控，然后把 `lastError` 变成常态 —— 这是唯一一次真被限流的教训。
