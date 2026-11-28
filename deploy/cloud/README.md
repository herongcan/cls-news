# 云服务器部署手册

目标：十来个同事，通过一个公网地址打开就能看，你自己不用守着笔记本。

---

## 一、这台机器现在是什么（选型结论）

**阿里云 ECS，杭州，2 vCPU / 1740 MB / 40G 系统盘，Ubuntu 24.04 LTS，包年包月。**
实测常驻内存 198 MiB（容器上限 512m）、CPU 0.09%，一台最低配的机器就跑满了。

| 维度 | 结论 | 理由 |
|---|---|---|
| 地域 | 国内（杭州/北京/上海） | 财联社 API 和 DeepSeek 都在境内，海外机房抓财联社会被风控，表现为「时好时坏 + 日志全是超时」 |
| 规格 | 2 核 2G 够用，1 核 1G 也能跑 | 容器限 512m；**1G 机器必须加 swap**，否则 OOM killer 只留一句 `Killed` |
| 磁盘 | 40G 系统盘 | 库 600KB 级，容器日志有 `max-size: 10m / max-file: 3` 兜着 |
| 计费 | **包年包月** | 不只是便宜：阿里云备案服务码要求包年包月、剩余时长 > 3 个月、内地节点、有公网带宽。**按量付费和试用实例不生成服务码** —— 域名现在用不了就是这个原因 |
| 系统 | Ubuntu 24.04 LTS | 下面命令都按这个写；22.04 也可以 |

**不要买香港/新加坡节点**：便宜一点，但财联社对海外 IP 有风控。

> 当初这份文档推荐的是「腾讯云轻量 99 元/年」，最后买的是阿里云这台。两者的差别只在计费和备案：
> 真要办备案，买之前先确认订单是**包年包月且剩余 > 3 个月**，否则后面还得换机器重来一遍。


---

## 二、买完先做三件事

### 1. 放行端口（最容易忘，忘了就是「服务明明起来了但打不开」）

阿里云控制台 → **ECS → 实例 → 安全组 → 入方向**（不是「轻量应用服务器」那个防火墙页面）添加规则：

```
协议类型：自定义 TCP
端口范围：8080/8080
授权对象：0.0.0.0/0
备注：cls-news 访问端口
```

**22 也必须对 `0.0.0.0/0` 开放** —— GitHub Actions 的 runner 在境外，收紧到自己家 IP 会让自动发版直接失效
（报错是「连不上」，看着像密钥问题）。

**80/443 不需要开，以后也不需要**：HTTPS 走 Cloudflare Tunnel（出站长连接，公网零入站端口），
见第五节。8080 是现在唯一的入站业务端口；隧道稳定后这一步可以收掉。

> 为什么用 8080 不用 80：国内服务器**用 IP 访问 80 端口不需要备案，但用域名必须备案**。
> 注意备案拦截按 HTTP Host 头判定、**与端口无关**：域名没备案时，走 80 和走 8080
> 都会返回运营商的拦截页。所以「换个非标端口就能用域名」是不成立的，
> 只有三条路：备案、继续用 IP 直连、或者走 Cloudflare Tunnel（第五节，已采用）。


### 2. 加 swap（内存越小越不能省）

```bash
ssh root@你的公网IP

# 2G 机器给 2G swap；1G 机器（如试用实例、轻量最低配）给 1G，且这一步是必须的
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab

free -h   # 确认 Swap 那行有值
```

部署时不在服务器上跑 Maven 了（见下文），但 JVM + SQLite + 系统本身，
留点 swap 当保险，成本是几 G 磁盘。

> **当前这台（1740MB）没有配 swap**：`free -h` 的 Swap 那行是 `0B`。到目前为止没出过事
> （容器常驻 198MiB / 上限 512m，宿主机还有 1GB 可用），但这是「暂时没踩到」而不是「不需要」。
> 想补就照上面四条命令做一次，`fallocate` 2G 大约占 2G 磁盘（40G 盘现在用了 16%）。

> **1 GiB 内存的机器**：swap 不是可选项。容器上限会被 `deploy.sh` 自动降到 **384m**
> （`free -m` ≤ 1000 就降；≤ 1600 用 448m），加上系统本身 ~200MB，正好卡在 1G 的天花板上，
> 没有 swap 兜底很容易被 OOM 杀掉。要在这类机器上同时跑别的服务，先关掉不用的（尤其是宝塔面板）。

### 3. 装 Docker

```bash
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
systemctl enable --now docker

docker --version           # 期望 Docker version 2x.x
docker compose version     # 期望 Docker Compose version v2.x
```

---

## 三、部署（在你自己的 Mac 上执行）

> **日常发版不用这一节**：现在推 `main` 就由 GitHub Actions 自动发版（见 [../../DEPLOY.md](../../DEPLOY.md) 第四节）。
> 这里的命令用于**首次装机**、Actions 出故障时的应急、以及换机器重来一遍。

### 前置：配置免密登录

```bash
ssh-copy-id root@你的公网IP
ssh root@你的公网IP 'echo 通了'   # 确认不用输密码
```

### 一条命令

```bash
cd /Users/herc/WorkBuddy/2026-11-10-12-44-38/cls-news

DEEPSEEK_API_KEY=sk-你的key ./deploy/cloud/deploy.sh root@你的公网IP
```

脚本会依次做：本地打 jar → 同步代码 → **重写**远端 `.env`（密码取 `APP_PASSWORD`，没给就用默认值 `admin`）
→ 构建镜像 → 起容器 → 轮询 `/healthz` 直到就绪 → 把访问地址和密码打印出来。

所以第一次跑就要把密码给对：

```bash
APP_PASSWORD='你的密码' DEEPSEEK_API_KEY=sk-你的key ./deploy/cloud/deploy.sh root@你的公网IP
```

首次大概 5-8 分钟（主要是拉 JRE 基础镜像，4M 带宽）。之后改代码重跑同一条命令，
只增量构建，一两分钟。

### 手动分步：脚本到底做了什么

想自己走一遍就照这 7 步，等价于 `deploy.sh`。

**0. 前置**（只需一次）

```bash
ssh-copy-id root@IP && ssh root@IP 'echo 通了'      # 免密
ssh root@IP 'docker --version && docker compose version'   # 要有 compose v2
```

安全组放行 8080，否则「服务起来了但打不开」。

**1. 本地打 jar**

```bash
cd cls-news
export JAVA_HOME=/opt/homebrew/opt/openjdk
./mvnw -q -B -DskipTests package
ls -l target/cls-news-0.1.0.jar        # 约 36MB
```

> 用 `./mvnw`（Maven 3.9.9，版本锁在仓库里），不要用系统的 `mvn`——本机可能是 3.5.9，
> 编译 `spring-boot 3.5.4` 时会报 `maven-compiler-plugin requires Maven version 3.6.3`。
>
> 一定在本地打。2核2G 的机器跑 Maven 会被 OOM killer 杀掉，日志只有一句 `Killed`，
> 看起来像网络问题，实际是内存。

**2. 建目录并同步**

```bash
ssh root@IP 'mkdir -p /opt/cls-news'
rsync -az --delete \
  --exclude '.git' --exclude '.idea' --exclude '*.iml' \
  --include 'target/' --include 'target/cls-news-0.1.0.jar' --exclude 'target/*' \
  --exclude 'data' --exclude 'logs' --exclude '.env' --exclude '*.log' \
  ./ root@IP:/opt/cls-news/
```

`--delete` 让远端与本地严格一致；`.env` 必须排除，否则会被删掉（里面是密码和 key）。

**3. 写 `.env`**（在远端 `/opt/cls-news/.env`，`chmod 600`）

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

> 手写这一份只适用于「不跑 deploy.sh 的纯手工部署」。**只要还跑 deploy.sh（或 Actions），它就会把
> 这个文件整个重写**，你手改的密码会被覆盖 —— 改密码的正确姿势见后面「更新代码」那一节。

两个变量最容易错：

- `DOCKERFILE` 不写就回落到根目录的多阶段 Dockerfile，远端要现拉 maven 镜像（约 500MB），
  4M 带宽十几分钟，还容易被 OOM。
- `MEM_LIMIT` 按远端 `free -m` 的**总内存**定：≤1000MB 用 `384m`，≤1600MB 用 `448m`，
  否则 `512m`。写大了会被 OOM killer 反复杀。

**4. 构建并启动**

```bash
ssh root@IP 'cd /opt/cls-news && docker compose up -d --build'
```

**5. 等健康检查**

```bash
for i in $(seq 1 30); do
  code=$(ssh root@IP "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/healthz")
  [ "$code" = "200" ] && { echo "就绪"; break; }
  sleep 3
done
```

**6. 验收**（三条都过才算成）

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://IP:8080/healthz           # 200
curl -s -o /dev/null -w '%{http_code}\n' http://IP:8080/api/news          # 401
# 密码只用来换票，换完带上 Bearer 再查：
TOK=$(curl -s -X POST -u admin:密码 http://IP:8080/api/auth/token \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -H "Authorization: Bearer $TOK" http://IP:8080/api/status | python3 -m json.tool
```

> `curl -u admin:密码 http://IP:8080/api/news` 现在拿到的是 **401**，不是数据 ——
> 密码不能直连业务接口，只能换票（见下面「会话与重启」）。

第 3 条重点看 `analysisReady`：`false` 说明 `.env` 里的 key 没生效，
同事点「分析」只会拿到 503。

**7. 回滚**（注意：这不是「回到上一个版本」）

镜像 tag 固定 `cls-news:0.1.0`，每次发版直接覆盖，所以本地没有「上一版镜像」可用：

```bash
# 只是重启当前版本，不换代码 —— 别把它当回滚
ssh root@IP 'cd /opt/cls-news && docker compose down && docker compose up -d'
```

真正回滚：`git revert <坏提交>` → 推 `main` 重新发一次。数据在命名卷 `cls-news_cls-data` 里，
`down` 不会丢；**`down -v` 会丢**。

### 部署完的自检

```bash
# 1. 健康检查（不需要密码，返回 UP 就是活的）
curl http://你的公网IP:8080/healthz

# 2. 鉴权是否生效（不带凭据应该 401）
curl -i http://你的公网IP:8080/api/news | head -3

# 3. 换票 + 带票访问应该 200
TOK=$(curl -s -X POST -u admin:密码 http://你的公网IP:8080/api/auth/token \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -H "Authorization: Bearer $TOK" http://你的公网IP:8080/api/news | head -c 200
```

三条都对了，再打开浏览器确认页面能出列表。

---

## 四、日常运维

```bash
# 看日志（分析失败、采集超时都在这）
ssh root@IP 'cd /opt/cls-news && docker compose logs -f --tail=50'

# 重启
ssh root@IP 'cd /opt/cls-news && docker compose restart'

# 看采集和分析进度（走 API，最省事；先换一张票）
TOK=$(curl -s -X POST -u admin:密码 http://IP:8080/api/auth/token \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -H "Authorization: Bearer $TOK" http://IP:8080/api/status | python3 -m json.tool

# 查库：运行镜像是 JRE，里面没有 sqlite3，别用 docker compose exec 去查。
# 宿主机装了 sqlite3 才能这么读；加 -readonly，别在线上库上抢写锁。
ssh root@IP "sqlite3 -readonly /var/lib/docker/volumes/cls-news_cls-data/_data/news.db \
  \"select analysis_status, count(*) from news group by 1\""

# 看有没有分析失败
ssh root@IP "sqlite3 -readonly /var/lib/docker/volumes/cls-news_cls-data/_data/news.db \
  \"select id, substr(last_error,1,80) from news where analysis_status='failed' order by id desc limit 10\""
```

### 会话与重启（别把「重启后要重登」当故障）

- **重启不会让任何人掉线。** 令牌存在容器的 `/app/data/news.db`（`auth_token` 表），
  `docker compose restart`、`deploy.sh` 滚动更新、改配置重启都只是换个进程，原会话照旧有效。
  只有三种情况会要求重新登录：refresh 到期（默认 7 天）、用户自己点了退出、
  或者库被清空/换掉了（那时令牌行没了，服务端只能回 401）。
- **密码不能直连业务接口**，只能用来换票。任何 `curl -u` 打 `/api/**` 都会拿到 401
  和一句「Basic 凭证只能用于 POST /api/auth/token」—— 这是刻意收窄的，不是坏了。
- **换票有失败限流**：同一 IP + 同一账号连续错 5 次锁 15 分钟，期间连正确密码也回 `429`，
  带 `Retry-After`。日志里会出现 `换令牌连续失败 N 次，锁定 900s`，看到它就该考虑换密码。
- **默认密码 `admin` 会在启动日志里留一条 WARN**（`仍在使用默认密码 admin`）。

### 看访问明细（谁、什么设备、什么时候）

访客统计分两张表，口径不同，别混着看：

| 表 | 口径 | 用途 |
|---|---|---|
| `visit` | IP + 自然日去重 | 页面顶栏「今日访客 / 累计」，含 11-13 之前的历史脏数据 |
| `access_log` | 每次打开记一行（同 IP 60 秒内只记一次） | 设备、UA、时间分布，保留 90 天 |

**明细有接口了，优先用它**（`/api/visits/logs`，走 Bearer 令牌，形状按命令行设计）：

```bash
TOK=$(curl -s -X POST -u admin:密码 'http://IP:8080/api/auth/token' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -H "Authorization: Bearer $TOK" 'http://IP:8080/api/visits/logs'                 # 今天最新 50 条
curl -s -H "Authorization: Bearer $TOK" 'http://IP:8080/api/visits/logs?day=2026-11-12'   # 指定日期
curl -s -H "Authorization: Bearer $TOK" 'http://IP:8080/api/visits/logs?ip=114.247.'      # IP 前缀
curl -s -H "Authorization: Bearer $TOK" 'http://IP:8080/api/visits/logs?device=wechat'    # 只看微信
curl -s -H "Authorization: Bearer $TOK" 'http://IP:8080/api/visits/logs?format=csv' -o visits.csv
```

需要聚合口径（设备分布、按日 UV、疑似爬虫）时再查库：

```bash
D=/var/lib/docker/volumes/cls-news_cls-data/_data/news.db

# 设备分布（判断移动端占比）
ssh root@IP "sqlite3 -header -column $D \
  \"select device, count(*) n from access_log group by 1 order by n desc\""

# 疑似爬虫：device='bot'。这个数现在应该接近 0
ssh root@IP "sqlite3 -header -column $D \
  \"select ip, count(*) n, max(ua) from access_log where device='bot' group by ip\""

# 按 vid 去重的每日访客（比 visit 表准，同 IP 下的不同人分开算）
ssh root@IP "sqlite3 -header -column $D \
  \"select day, count(distinct coalesce(nullif(vid,''), ip)) uv
    from access_log group by day order by day desc limit 14\""
```

两个数对不上是正常的：`visit.today` 按 IP 算，`access_log` 按 vid 算。
公司 NAT 出口下十个同事，前者会显示 1，后者显示 10。

> `access_log` 是 2026-11-13 那次发版才建的，所以**它之前没有任何明细**，只有 `visit` 的聚合数字。
> 看到「今天只有 1 条」先别当故障：那是当天唯一一次带凭证的 ping。

**历史脏数据不会自己消失。** 改成"登录后才上报"之前的 `visit` 记录里，
83% 是公网扫描器（只来过一次就再没出现）。它们会一直计入 `visitTotal`：

```bash
# 先只看，别直接删
ssh root@IP "sqlite3 -header -column $D \
  \"select ip, day, hits from visit where hits=1 and ip not in (select ip from access_log) limit 50\""
```

不提供自动清理，是因为这里面也混着"真来过一次就没再来的同事"，删不删由你判断。

### 更新代码

**常规路径是推 `main`**，GitHub Actions 编译 + rsync + `compose up -d --build`，实测 56 秒，不用开终端。

手动兜底（Actions 挂了、或想临时推一版）：

```bash
./deploy/cloud/deploy.sh root@你的公网IP
```

脚本会沿用远端已有的 `DEEPSEEK_API_KEY`，不会让你重填。

> **改密码的坑，先看这一段。** `deploy.sh` 每次都把远端 `.env` **整个重写**，
> `APP_PASSWORD` 取的是环境变量 `${APP_PASSWORD:-admin}`。所以：
>
> - 只手改远端 `/opt/cls-news/.env` 里的密码 → **下一次发版会被覆盖回默认值**，同事突然又能用老密码进；
> - 想永久改：`APP_PASSWORD=新密码 ./deploy/cloud/deploy.sh root@IP`，**并且**在仓库 Secrets 里加
>   `APP_PASSWORD` 让流水线也传（现在 workflow 没接这个 secret，不接的话每次自动发版又会被重置）。
>
> 这条是当前设计的一个真实缺口，比「文档没写全」严重 —— 换密码前务必两边一起改。

### 备份

**先说结论：不要用 `cp` / `tar` / `docker cp` 备 SQLite。** 实测过，会丢数据：

这个库跑在 `journal_mode=WAL` + `synchronous=NORMAL` 下。造一个 2 行的库、
让其中 1 行只待在 `-wal` 里，然后两种方式各拷一次：

| 方式 | 备份里的数据 |
|---|---|
| `cp news.db` | 1 行，WAL 里那条没了 |
| `sqlite3 news.db ".backup ..."` | 2 行，完整 |

那次实验里主库 8192 字节、`-wal` 12392 字节 —— WAL 比主库还大，`cp` 丢的不是
「一点点」，是超过一半的最新写入。

#### 一次性安装（在服务器上执行）

```bash
# 1. 宿主机装 sqlite3（约 1MB，只需一次）
apt-get install -y sqlite3

# 2. 手动跑一次，确认能通
/opt/cls-news/deploy/cloud/backup.sh

# 3. 装成每天 03:30 自动执行
cp /opt/cls-news/deploy/cloud/cls-news-backup.{service,timer} /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now cls-news-backup.timer

# 4. 确认定时器已就位
systemctl list-timers cls-news-backup.timer
```

`backup.sh` 做四件事：用 Online Backup API 拿一致快照 → `PRAGMA integrity_check`
校验 → gzip 落盘（权限 600）→ 清理过期。默认存 `/opt/cls-news-backup`，保留 7 天。

想改保留天数：编辑 `cls-news-backup.service` 里的 `KEEP_DAYS`，`daemon-reload` 后重启 timer。

#### 异地副本（强烈建议）

本地备份和数据库在同一块盘上，防得了误删，防不了磁盘故障和整机丢失。
有 OSS 的话，在 `cls-news-backup.service` 里加一行就多一份异地：

```
Environment=OSS_URL=oss://你的bucket/cls-news
```

需要宿主机装了 `ossutil` 并配好凭据。没配也能正常跑，只是每次日志里会提醒一句。

> **现状：没配。** 服务器上 `ossutil` 未安装，`cls-news-backup.service` 里也没有 `OSS_URL`，
> 所以备份只有本地那一份、和库在同一块盘上 —— 磁盘坏或整机丢，数据和备份一起没。
> 这是目前最值得补的一件事，补法是：装 ossutil → 配 `ossutil config` → 在 service 里加上面那行 →
> `systemctl daemon-reload && systemctl restart cls-news-backup.timer` → 手动跑一次确认能看到「已上传异地」。

#### 演练：不动生产地验证备份能不能用（至少每月一次）

备份失败会静默，恢复失败会致命。演练就是让第二个问题在平时暴露出来。

```bash
# 自动挑最新一份，只读，不停服务，一个字节都不写
/opt/cls-news/deploy/cloud/restore.sh --drill
```

输出长这样（**示例，行数和 RPO 每次都不一样**），**重点看最后三行的 RPO**：

```
──────────────────────────────────────────────
  备份体检：news-2026-11-12_0330.db.gz  (172K)
──────────────────────────────────────────────
  gzip            完整
  integrity_check ok
  表结构          news 表存在

  行数            745
  最早入库        2026-11-10 09:12:03
  最后入库        2026-11-12 03:28:41
  数据跨度        1 天 18 小时

  标题为空        0 / 745
  正文为空        5 / 745
  分析状态        done 700  pending 40  failed 5

  ── 与线上对比 ──
  线上行数        768（备份少 23 行）
  线上最后入库    2026-11-12 18:23:49
  ▶ RPO          落后 14 小时 55 分
──────────────────────────────────────────────
```

怎么读：

| 看到 | 含义 | 该做什么 |
|---|---|---|
| `RPO 落后` 约等于备份间隔（如 15 小时） | 正常，说明备份抓到了最新的写入 | 无 |
| `RPO` 突然变成好几天 | 备份从某天起就没成功过 | 查 `journalctl -u cls-news-backup` |
| `行数` 是 0 而线上有数据 | 备到了错的文件 | **别把它当好备份**，查 `DB_FILE` 配置 |
| `integrity_check` 不是 ok | 备份文件损坏 | 换一份，并检查磁盘 |
| `标题为空` 大量出现 | 采集逻辑可能写入了空壳记录 | 查 `IngestJob` 日志 |
| 最后一行是「没找到线上库」 | 在没装 docker 的机器上跑的 | 正常，只是跳过对比 |

想验某一份特定的：`restore.sh /opt/cls-news-backup/news-2026-11-10_0330.db.gz --drill`

**演练只验证数据层**（文件完整、表结构对、内容新鲜）。它不能替代真恢复演练——
真正动手跑一次恢复（后面那节），你才知道整个流程要花多久、会在哪一步卡住。
建议：先在一份**旧备份**上完整走一次恢复流程，确认自己会了，再等真出事。

#### 恢复：真出事时

```bash
# 不带参数先看有哪些备份
/opt/cls-news/deploy/cloud/restore.sh

# 挑一份恢复
/opt/cls-news/deploy/cloud/restore.sh /opt/cls-news-backup/news-2026-11-12_0330.db.gz
```

脚本会：校验 gzip → 校验 integrity → **打印完整体检报告（含 RPO）** → 让你输入
`yes` 确认 → 停容器 → 把当前库另存为 `news-before-restore-*.db`（恢复错了还能退）
→ 删 `-wal`/`-shm` → 覆盖 → 起容器 → 轮询 `/healthz` → 核对恢复后行数。

确认那一步**必须手打 `yes`**，回车、`y`、`Y` 一律取消。恢复是不可逆操作，不该有
「顺手一按」的路径。

`--yes` 可以跳过确认，只给脚本化场景用（比如定时演练自动化），别手动敲。

全程约 30 秒：停容器 2 秒 + 覆盖 1 秒 + 启动 9 秒 + 健康检查若干秒。

**如果恢复后服务起不来**：

```bash
cd /opt/cls-news && docker compose logs --tail=80
```

退回到恢复前状态：把 `/var/lib/docker/volumes/cls-news_cls-data/_data/` 下的
`news-before-restore-*.db` 按同样步骤放回去（**同样要先删 `-wal` 和 `-shm`**）。

**为什么不删 `-wal` 会出事**，实测记录：

> 库里有 v1/v2，备份出 b.db；之后又写入 v3（进了 `-wal`）；进程被 kill 留下 WAL；
> 用 b.db 覆盖主库但不删 `-wal`，再打开 —— 内容是 `v1, v2, v3`，v3 被复活了，
> 而 `integrity_check` 返回 **ok**。

得到一个既不是备份、也不是现场的第三种状态，能正常查询、不报错，只能靠人肉核对行数发现。
更极端的情况：把另一个无关的库的 `-wal` 放到某库旁边，该库数据会被整个覆盖成 WAL 里那份。
所以脚本里是无条件 `rm`，不依赖 SQLite 去判断「这个 WAL 是不是我的」。

> 顺带说明：正常情况下 `docker compose stop` 会让最后一个连接优雅关闭并自动
> checkpoint、删除 `-wal`。但崩溃、OOM（`ExitOnOutOfMemoryError`）、断电之后恰恰不会
> —— 而那正是最需要恢复的时候。所以这一步不能省。

#### 还没做的一件事

部署前自动备份（`deploy.sh` 在 `compose up` 之前先跑一次 `backup.sh`，
失败就中止部署）。这样「没有备份不动生产数据」能被机器强制住，而不是靠人记得。
需要时可以加，代价是每次部署多 3 秒、且服务器必须先装好 sqlite3。

---

## 五、两个你必须知道的限制

### 1. HTTPS：走 Cloudflare Tunnel（2026-11-16 上线）

**当前入口：`https://news.herongcan.icu`**，证书由 Cloudflare 边缘签发，源站不装证书、不开 80/443。

为什么是这条路，而不是 Caddy：**域名没有 ICP 备案**，直连 80/443 会被运营商按 Host 头拦掉（跟端口无关，
换 8080 一样拦）；而 Let's Encrypt / ZeroSSL **都不给裸 IP 签 DV 证书**，自签会满屏红警告。
Tunnel 绕开了这两条：它是**出站**长连接 —— 本机主动连 Cloudflare 边缘，公网没有任何入站端口，
请求从边缘顺着这条连接推进来，不经过备案拦截那一层。

```
用户 → 最近的 Cloudflare 边缘 → (QUIC/HTTP2 出站隧道) → 本机 cloudflared → 127.0.0.1:8080
```

#### 组成（都已就位）

| 位置 | 内容 |
|---|---|
| 服务器 `/usr/local/bin/cloudflared` | 2026.11.0，官方二进制 |
| `/etc/cloudflared/config.yml` | 配置，仓库里那份是 `deploy/cloud/cloudflared.yml`（`tunnel:` 里的 ID 是这台的，别拿去别的机器用） |
| `/etc/cloudflared/<tunnel-id>.json` | 隧道凭据，权限 600，**泄漏等于把这条隧道交出去** |
| systemd | `cloudflared.service`，`restart` 重启、`journalctl -u cloudflared -f` 看日志 |
| DNS | `news.herongcan.icu` CNAME → `<tunnel-id>.cfargotunnel.com`（橙云代理必须开着） |
| `.env` | `APP_TRUST_XFF=true` |

域名的 NS 早就在 Cloudflare（`may.ns` / `bob.ns`），所以这一步没碰注册商。

#### 为什么 `protocol: http2` 而不是默认的 quic

默认走 QUIC。这条链路（杭州 → 洛杉矶）实测 QUIC 极易超时：10 分钟里 8 次
`failed to dial to edge with quic`，TTFB 飙到 12 秒。`http2` 走 TCP，有拥塞控制兜底，抗丢包明显更好。
**别改回默认值**，除非你换到了离边缘更近的机房。

#### 实测延迟（2026-11-16，这就是这条路线的真实代价）

| 路径 | 首字节 |
|---|---|
| 本机 → `127.0.0.1:8080`（容器内） | 6 ms |
| 本机 → `http://8.136.19.57:8080`（IP 直连） | 69 ms |
| 本机 → `https://news.herongcan.icu` | **1.2 – 3 s，偶发 10 s+** |

原因不是配置问题，是地理：Cloudflare 免费版给境内用户分配的 PoP 在**美西/欧洲**（实测 `cf-ray` 落在
`LAX` 和 `AMS`），链路变成「深圳 → 洛杉矶 → 杭州」，跨两次太平洋，且丢包会导致 `total` 远大于 `ttfb`。
TLS 握手本身就要 0.4–1.8 秒。

**结论**：这条路线解决的是「有没有 HTTPS」和「要不要备案」，不解决「快不快」。
给朋友看、面试演示够用；日常十来个人高频用，体感会明显比以前差。

#### 2026-11-23 复测：比上面更糟，已定位到根因

用户反馈「访问要刷新好久」。12 次采样复测：TCP 到 CF 边缘稳定 0.17–0.32 s，但 **TLS 握手在 0.45–15.7 s 之间抖动**，
10 次里 3 次 30 s 超时；源站 `:8080` 直连仍是 0.076 s。拆开是两层病：

1. **入站**：域名解析到 `104.21.6.139` / `172.67.154.228`（CF 免费版分配的段）。两个 IP 各测 6 次，**都是 2/6 成功**。
   失败分两种：`tls=0`（握手阶段就被掐断）、`tls=0.52` 但拿不到响应（握手过了，跨境回源卡住）。
   对照组同机访问 `https://www.cloudflare.com`（解析到 `104.16.132.229`）TLS 稳 0.45 s —— 不是本机网络的问题，
   是这两个段被重点干扰。
2. **IPv6 反而健康**：`2606:4700:3035::` 两次测试都是 200、TLS 0.44 s。有 IPv6 的网络体感会明显好于纯 IPv4。

#### 直连方案：已尝试，已证伪（别重踩）

思路是绕开 CF，让 ECS 自己监听非标端口 8443 + 上真证书。过程与结论：

- 8443 上起自签探针，**DNS 里还没有记录**时用 `curl --resolve` 测 → 域名访问 5/5 成功、0.12 s，
  当时误判为可行（这个误判的代价见下）。
- 装 nginx 反代 8443 → `127.0.0.1:8080`。`X-Forwarded-For` 必须用 `$remote_addr` **覆盖**，
  不能用 `$proxy_add_x_forwarded_for` —— 前面没有可信代理，追加的值会被客户端伪造。
- 用 CF Token 加 A 记录 `direct.herongcan.icu → 8.136.19.57`（**必须 `proxied=false` 灰云**），
  certbot 用 DNS-01 签下真证书（`python3-certbot-dns-cloudflare`）。只有 DNS-01 可行：
  HTTP-01 要 80 端口、TLS-ALPN-01 要 443，两个都被备案拦截堵着。
- **A 记录生效的那一刻起，域名访问立刻 5/5 失败，IP 访问仍旧 5/5 成功。**
  删掉 A 记录后也不恢复（4/4 仍失败）—— 域名是被拉进了黑名单，不是随记录动态生效。

铁证：`SSL certificate verify ok` + `ALPN h2` 都成功，紧接着 `Recv failure: Connection reset by peer`；
假域名做 SNI 打同一 IP → 200；真域名强制 HTTP/1.1 → 仍 000。
**这是按 SNI 的定向阻断，换端口、上 TLS、关 HTTP/2 都绕不过。**

根因是备案核查：响应头 `Server: Beaver`，403 页面跳 `aliyun.com/beian/beian-block?id=...`。
用域名打 8080（明文）0.068 s 就返回 403，用 IP 打同一端口 200 —— 印证了本文开头「跟端口无关」那句。

**所以：未备案域名不能指向境内服务器，这是制度约束，不是技术问题。**
以后要测这类假设，**必须在 DNS 记录已经生效的前提下测**，否则测出来的「通」是假的。

#### 现在的入口：`https://8.136.19.57:8443`（2026-11-23 新增）

CF 太慢、直连域名被拦，最后落在这个入口：

| 项 | 值 |
|---|---|
| 地址 | `https://8.136.19.57:8443` |
| 实测 | **12/12 成功，平均 0.122 s**（最慢 0.142 s） |
| 证书 | 自签，SAN 为 `IP:8.136.19.57`，365 天 |
| 浏览器 | 首次红字警告，点「高级 → 继续前往」即可 |
| 链路 | 客户端 → nginx:8443（TLS 终止）→ `127.0.0.1:8080` |

为什么是自签：**Let's Encrypt 不给裸 IP 签证书**，而域名一指向境内就被拦（见上），两头堵死。
要无警告只剩两条路：上境外机器（标准 443），或 ICP 备案。

- nginx 配置：`/etc/nginx/sites-available/cls-direct`
- 证书：`/etc/nginx/ssl/direct.crt` / `.key`
- 自签不会自动续期，**365 天后要重新生成**，否则浏览器直接报过期：

```bash
ssh root@8.136.19.57 'openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout /etc/nginx/ssl/direct.key -out /etc/nginx/ssl/direct.crt -days 365 \
  -subj "/CN=8.136.19.57" -addext "subjectAltName=IP:8.136.19.57" && systemctl reload nginx'
```

#### 日常维护

```bash
# 改配置 → 必须 restart，reload 不生效
ssh root@IP 'vim /etc/cloudflared/config.yml && systemctl restart cloudflared'

# 看隧道是否 4 条连接都在
ssh root@IP 'journalctl -u cloudflared -n 20 --no-pager | grep Registered'

# 隧道指标（连接数、回源错误）
ssh root@IP 'curl -s http://127.0.0.1:20241/metrics | grep -E "ha_connections|request_errors"'
```

#### 三个必须记住的坑

1. **`APP_TRUST_XFF=true` 不能丢。** cloudflared 会把真实客户端 IP 写进 `X-Forwarded-For`，
   不开这一项，所有访客都记成 `127.0.0.1`，统计全废 —— 而且**不报错**，只是数字永远不动。
   `deploy.sh` 原本每次整个重写 `.env`，会把这一行冲掉；2026-11-16 已改成和 `APP_READONLY`
   同样的逻辑（显式给了就用，没给就回读远端），并写进 `.env`。**改动那段代码前先看懂注释**，
   否则下一次发版会静默把访客统计弄坏。
2. **8080 和 8443 都公网可达（安全组开着）。** 区别是 8080 走**明文**，登录口令和令牌裸奔，
   同链路节点可嗅探；8443 有 TLS，是加密的。**建议收掉 8080**（安全组入方向只留 22 / 8443），
   收掉后 `IP:8080` 失效，但 `IP:8443` 和 `cloudflared` 都不受影响（后者走 `127.0.0.1`）。
   只收安全组不够彻底：容器仍绑在 `0.0.0.0`，最好同时把 `BIND_ADDR` 改成 `127.0.0.1`
   —— 注意 `deploy.sh` 每次发版都会重写 `.env`，只改文件会在下次部署后恢复，得连脚本一起改。
3. **`vip` / `X-Forwarded-For` 只信最左段。** 见 `ClientIp.of()`：`trustForwarded=true` 时取 XFF 第一个逗号前的部分。
   多级代理（CDN 套 CDN）时这会取到上一跳代理的地址，不是真实客户端。

#### 想换成更快的那两条路

| 备选 | 适合 | 代价 |
|---|---|---|
| 香港 / 新加坡轻量（域名直接 A 记录 + Caddy） | 正经给一批人用 | 迁机器、迁库（单文件 SQLite，`backup.sh` 已有 rsync 逻辑）、约 24–30 元/月 |
| 境内 + ICP 备案 | 长期方案 | 备案要包年包月实例 + 剩余 > 3 个月，等 1–3 周 |

两条路的核心差别只是「谁来签证书」：Caddy 一行 `reverse_proxy` 就自动签 Let's Encrypt。
但**必须同时改三处**，否则起点是 Caddy 起不来、终点是 8080 把 HTTPS 绕过去：

1. `/opt/cls-news/.env` 里 `BIND_ADDR=127.0.0.1`（只监听本机，让 Caddy 独占公网）；
2. `deploy/cloud/deploy.sh` 里 `echo "BIND_ADDR=0.0.0.0"` 改成 `127.0.0.1` —— **不能只改 .env**，
   脚本每次发版都会重写 `.env`，只改文件会在下一次部署后恢复成 0.0.0.0；
3. 安全组收掉 8080（只留 22/80/443）。

### 2. DeepSeek 余额要盯

当前是**手动模式**（`app.analysis.auto=false`），所以余额用完的表现不是「页面不更新」，而是
**点「分析」直接报错**（`/api/news/{id}/analyze` 返回非 200，页面红字）；`pending` 会一直堆着不动，
那是没人点，不是故障。

成本参考：单条约 400~800 token，日增 400 条电报，全分析一遍 deepseek-flash 大概**每月几块钱**；
只分析感兴趣的那部分更省。`daily-cap: 300` 是防误触的保险，超限返回 429 而不是静默跳过。

---

## 六、踩过的坑（实测记录）

### 拉不到 Docker Hub 镜像

阿里云北京实测：`docker pull eclipse-temurin:17-jre` 报
`dial tcp 50.87.93.246:443: i/o timeout`，直连不通。

解决：从国内源拉，再打回标准标签，**Dockerfile 一个字都不用改**（因为构建时会命中本地镜像缓存）。

```bash
ssh root@IP 'docker pull docker.m.daocloud.io/library/eclipse-temurin:17-jre \
  && docker tag docker.m.daocloud.io/library/eclipse-temurin:17-jre eclipse-temurin:17-jre'
```

实测 daocloud 24 秒拉完（115MB）。可用源（2026-11 实测）：

| 源 | 状态 |
|---|---|
| `docker.m.daocloud.io` | 可用 |
| `docker.1ms.run` | 可用 |
| `docker.xuanyuan.me` | 可用 |
| `dockerpull.org` | 超时 |
| `mirror.ccs.tencentyun.com` | 超时（在阿里云上访问不到） |

### 报 `COPY target/cls-news-0.1.0.jar: not found`

不是 rsync 没传上去，是 **`.dockerignore` 把 `target/` 整体排除了**。

Docker 的忽略规则和 gitignore 一样：**父目录被整体排除后，`!` 无法再把里面的文件救回来**。
必须写成排除子项：

```
target/*
!target/cls-news-0.1.0.jar
```

### Ubuntu 24.04 的 sshd 默认拒绝密码登录

`ssh root@IP` 只返回 `Permission denied (publickey)`，即使控制台设过密码也一样。

这是 Ubuntu 从 23.10 起改的默认值（`PasswordAuthentication no`）。新机器要么绑定密钥对，
要么先通过控制台 VNC（不走 sshd）进去改配置。阿里云轻量的「命令助手」也可以直接下发脚本，
是最快的把公钥塞进去的路子。

### 查数据不能用 `docker compose exec cls-news sqlite3`

运行镜像是 `eclipse-temurin:17-jre`（JRE，不含 sqlite3 命令行）。查数据走 API 或宿主机装 sqlite3：

```bash
# 推荐：走 API 的 stats 字段（先换票）
TOK=$(curl -s -X POST -u admin:密码 http://IP:8080/api/auth/token \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -H "Authorization: Bearer $TOK" http://IP:8080/api/news | python3 -m json.tool | head -20

# 或者宿主机装一个
ssh root@IP 'apt-get install -y sqlite3 && docker run --rm -v cls-news_cls-data:/data \
  -v /tmp:/out alpine sh -c "cp /data/news.db /out/ && chmod 644 /out/news.db" && sqlite3 /tmp/news.db "select analysis_status,count(*) from news group by 1"'
```

---

## 七、笔记本那套还要不要留

`deploy/laptop/` 留着，当**本地开发环境**用：

```bash
./deploy/laptop/start.sh     # 前台跑，改代码时看日志方便
```

不用再装 LaunchAgent 常驻了 —— 那个 `install.sh` 是「笔记本当服务器」的方案，
现在有云服务器，把常驻交给服务器。要卸载本地常驻：

```bash
launchctl bootout gui/$(id -u)/com.herc.clsnews 2>/dev/null
rm -f ~/Library/LaunchAgents/com.herc.clsnews.plist
```
