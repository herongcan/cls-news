# 云服务器部署手册

目标：十来个同事，通过一个公网地址打开就能看，你自己不用守着笔记本。

---

## 一、买什么

**腾讯云轻量应用服务器，2核2G4M，99 元/年。**

推荐它的唯一理由：**续费同价不涨价**。阿里云那个 38 元是秒杀款，抢不到就是 68，
而且次年续费会往上跳；腾讯云这个 99 是首购续费一个价，省得明年再折腾一次搬家。

下单时这几项别选错：

| 选项 | 选什么 | 为什么 |
|---|---|---|
| 地域 | **北京 / 上海 / 广州**（离你近的） | 财联社 API 和 DeepSeek 都在国内，海外机房抓财联社容易被限流 |
| 镜像 | **Ubuntu 24.04 LTS** | 下面命令都按这个写；22.04 也可以 |
| 套餐 | 2核2G / 50G SSD / 4M | 这个服务 `mem_limit: 512m`，2G 富余很多 |
| 时长 | 1 年 | 先别买三年，用得好再续 |

不要买香港/新加坡节点——便宜一点，但财联社对海外 IP 有风控，会出现「采集时好时坏，
日志里全是超时」，很容易误判成代码问题。

---

## 二、买完先做三件事

### 1. 放行端口（最容易忘，忘了就是「服务明明起来了但打不开」）

腾讯云控制台 → 轻量应用服务器 → 你的实例 → **防火墙** → 添加规则：

```
应用类型：自定义
协议：TCP
端口：8080
来源：0.0.0.0/0
备注：cls-news 访问端口
```

22 端口默认已放行。80/443 用不上，可以先关掉。

> 为什么用 8080 不用 80：国内服务器**用 IP 访问 80 端口不需要备案，但用域名必须备案**。
> 注意备案拦截按 HTTP Host 头判定、**与端口无关**：域名没备案时，走 80 和走 8080
> 都会返回运营商的拦截页。所以「换个非标端口就能用域名」是不成立的，
> 只能备案，或者继续用 IP 直连。

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

> **1 GiB 内存的机器**（阿里云试用、轻量最低配）：swap 不是可选项。
> 容器上限会被 `deploy.sh` 自动降到 448m（JVM 堆约 313MB），
> 加上系统本身 ~200MB，正好卡在 1G 的天花板上，没有 swap 兜底很容易被 OOM 杀掉。
> 如果要在这类机器上同时跑别的服务，建议先关掉不用的（尤其是宝塔面板）。

### 3. 装 Docker

```bash
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
systemctl enable --now docker

docker --version           # 期望 Docker version 2x.x
docker compose version     # 期望 Docker Compose version v2.x
```

---

## 三、部署（在你自己的 Mac 上执行）

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

脚本会依次做：本地打 jar → 同步代码 → 写远端 `.env`（账号密码固定 `admin` / `admin`）→ 构建镜像 → 起容器 →
轮询 `/healthz` 直到就绪 → 把访问地址和密码打印出来。

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
APP_PASSWORD=admin
BIND_ADDR=0.0.0.0
HOST_PORT=8080
MEM_LIMIT=512m
DOCKERFILE=deploy/cloud/Dockerfile.runtime
DEEPSEEK_API_KEY=sk-xxxx
```

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
curl -s -o /dev/null -w '%{http_code}\n' http://IP:8080/healthz          # 200
curl -s -o /dev/null -w '%{http_code}\n' http://IP:8080/api/news         # 401
curl -s -u admin:admin http://IP:8080/api/status | python3 -m json.tool  # analysisReady
```

第 3 条重点看 `analysisReady`：`false` 说明 `.env` 里的 key 没生效，
同事点「分析」只会拿到 503。

**7. 回滚**（镜像还在，一条命令）

```bash
ssh root@IP 'cd /opt/cls-news && docker compose down && docker compose up -d'
```

数据在命名卷 `cls-news_cls-data` 里，`down` 不会丢；**`down -v` 会丢**。

### 部署完的自检

```bash
# 1. 健康检查（不需要密码，返回 UP 就是活的）
curl http://你的公网IP:8080/healthz

# 2. 鉴权是否生效（不带凭据应该 401）
curl -i http://你的公网IP:8080/api/news | head -3

# 3. 带凭据应该 200
curl -u admin:密码 -s http://你的公网IP:8080/api/news | head -c 200
```

三条都对了，再打开浏览器确认页面能出列表。

---

## 四、日常运维

```bash
# 看日志（分析失败、采集超时都在这）
ssh root@IP 'cd /opt/cls-news && docker compose logs -f --tail=50'

# 重启
ssh root@IP 'cd /opt/cls-news && docker compose restart'

# 看采集和分析进度（走 API，最省事）
curl -s -u admin:admin http://IP:8080/api/status | python3 -m json.tool

# 查库：运行镜像是 JRE，里面没有 sqlite3，别用 docker compose exec 去查。
# 宿主机装了 sqlite3 才能这么读（只读没问题，库是 WAL 模式）。
ssh root@IP "sqlite3 /var/lib/docker/volumes/cls-news_cls-data/_data/news.db \
  \"select analysis_status, count(*) from news group by 1\""

# 看有没有分析失败
ssh root@IP "sqlite3 /var/lib/docker/volumes/cls-news_cls-data/_data/news.db \
  \"select id, substr(last_error,1,80) from news where analysis_status='failed' order by id desc limit 10\""
```

### 看访问明细（谁、什么设备、什么时候）

访客统计分两张表，口径不同，别混着看：

| 表 | 口径 | 用途 |
|---|---|---|
| `visit` | IP + 自然日去重 | 页面顶栏「今日访客 / 累计」，含历史脏数据 |
| `access_log` | 每次打开记一行（同 IP 60 秒内只记一次） | 设备、UA、时间分布，保留 90 天 |

`access_log` **没有做成 HTTP 接口**，只能查库。这是有意的：那个接口对"任何持有凭证的人"开放，
而现在是十几个人共用一个账号，把每个人的 IP 和设备摊给所有人看没有必要。

```bash
D=/var/lib/docker/volumes/cls-news_cls-data/_data/news.db

# 最近 30 条：时间 / IP / 设备 / 浏览器
ssh root@IP "sqlite3 -header -column $D \
  \"select datetime(ts,'unixepoch','localtime') as 时间, ip, device, os, browser
    from access_log order by ts desc limit 30\""

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

**历史脏数据不会自己消失。** 改成"登录后才上报"之前的 `visit` 记录里，
83% 是公网扫描器（只来过一次就再没出现）。它们会一直计入 `visitTotal`：

```bash
# 先只看，别直接删
ssh root@IP "sqlite3 -header -column $D \
  \"select ip, day, hits from visit where hits=1 and ip not in (select ip from access_log) limit 50\""
```

不提供自动清理，是因为这里面也混着"真来过一次就没再来的同事"，删不删由你判断。

### 更新代码

改完代码直接重跑：

```bash
./deploy/cloud/deploy.sh root@你的公网IP
```

脚本会沿用远端已有的 key；访问密码是固定的 `admin/admin`，不会变，同事不会掉线。
想换密码就在命令前加 `APP_PASSWORD=新密码`。

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

#### 演练：不动生产地验证备份能不能用（至少每月一次）

备份失败会静默，恢复失败会致命。演练就是让第二个问题在平时暴露出来。

```bash
# 自动挑最新一份，只读，不停服务，一个字节都不写
/opt/cls-news/deploy/cloud/restore.sh --drill
```

输出长这样，**重点看最后三行的 RPO**：

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

### 1. 现在没有 HTTPS

**当前状态：域名 herongcan.icu 解析已生效，但 ICP 备案未完成，因此不能对外使用。**

实测：`http://herongcan.icu:8080/` 在浏览器里返回的是运营商的「域名暂时无法访问」页面，
**不是应用页面**。拦截按 HTTP Host 头判定，跟端口无关，换端口绕不过去。
所以同事现在只能用 `http://8.136.19.57:8080`。

备案卡在「没有可备案的服务器」：阿里云要求 ECS 为包年包月、购买时长 >3 个月、
中国内地节点、有公网带宽；按量付费和免费试用实例都不生成备案服务码。
现有实例是 `ecs.e-c1m1.large`（杭州），需先在控制台确认为哪种计费方式。

备案通过后：把 `deploy.sh` 的 `DOMAIN_READY` 设为 1 重新部署，访问地址会自动切成域名。

Basic Auth 的密码是**明文过公网**的（Base64 只是编码），同一 WiFi 下会抓包的人能还原出
`admin:密码`。这一点在不上 HTTPS 的前提下无法消除，只能靠强密码 + 定期更换降低影响。

想补 HTTPS 时必须先过备案这一关，通过后在宿主机装一层 Caddy 即可（应用和 compose 不用动结构）：

```bash
# apt 源在国内可达，不用拉 Docker 镜像（这台机器直连 Docker Hub 会超时）
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
  | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
  | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

`/etc/caddy/Caddyfile`：

```
herongcan.icu {
    reverse_proxy 127.0.0.1:8080
}
```

然后改两处：compose 的端口映射回到 `127.0.0.1:8080:8080`（关掉公网直连），
`deploy.sh` 的 `HOST_PORT` 改回 8080。`systemctl reload caddy` 生效，证书自动申请续期。

### 2. DeepSeek 余额要盯

分析是自动跑的，余额用完不会报错到页面上，只是新消息停在 `pending`，
看起来像「新闻不更新了」。每月看一眼余额，或者加个余额告警。

成本参考：单条约 300 token，一天两三百条电报，deepseek-flash 大概**每月几块钱**。

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
# 推荐：走 API 的 stats 字段
curl -s -u admin:密码 http://IP:8080/api/news | python3 -m json.tool | head -20

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
