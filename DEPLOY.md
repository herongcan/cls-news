# 部署上线

这份文档只回答一件事：**怎么把它从一个「你本机能跑的 jar」变成「一个 24 小时在线、只有你能看」的服务。**

---

## 零、已经实测过的部分（不是你照着猜的）

下面这些不是「应该可以」，是在本机 Docker 上真跑过一遍的结果：

| 验证项 | 结果 |
|---|---|
| 镜像构建 | ✅ 成功，`cls-news:0.1.0`，519MB，首次约 2 分钟（之后有缓存约 16 秒） |
| 容器启动 | ✅ `Up (healthy)`，健康检查 `FailingStreak: 0` |
| 时区 | ✅ 容器内 `date` 返回 `CST +0800`，日志时间是本地时间 |
| 运行用户 | ✅ `uid=999(app)`，非 root |
| 数据落盘 | ✅ `/app/data/news.db`，属主 `app:app` |
| 抓取 | ✅ 首轮入库 50 条，两轮后 54 条，`lastError` 为空 |
| 重启保留数据 | ✅ 重启前 52 条 → 重启后 54 条（原数据在 + 新抓 2 条） |
| 鉴权拦截 | ✅ 无凭据 401、错密码 401、正确凭据 200、`/healthz` 放行 |
| 空密码保护 | ✅ `APP_AUTH=true` 且密码为空时，**进程直接退出**并说明原因 |
| 白名单绕过 | ✅ `/healthz/../api/status` 等 6 种穿越变体全部 401/400，无一泄露 |
| key 缺失降级 | ✅ 采集正常、`analysisReady=false`、`pending` 不被刷成 `failed` |

**没测到的**：真实云服务器（apt 源速度、安全组、备案）、Caddy 自动签证书、长时间运行的内存曲线。

---

## 一、先回答「现在能不能上线」

分三类看，不要说「能跑就能上线」。

### 已经具备的

| 能力 | 现状 | 为什么这算具备 |
|---|---|---|
| 单进程交付 | 一个 jar，36MB | 没有外部中间件、没有前端构建，部署面最小 |
| 数据持久化 | SQLite 单文件 | 重启不丢；`DB_PATH` 可指向挂载卷 |
| 抓取容错 | 失败只记 `lastError`，不退出 | 财联社接口变了不会导致服务挂掉 |
| 分析容错 | 独立 worker + 每日上限 | 模型挂了不影响采集，账单有天花板 |
| 崩溃恢复 | 启动时把 `analyzing` 打回 `pending` | 进程被杀不会让某条消息永久卡死 |
| 健康检查 | `/healthz`（免鉴权，真读一次库） | 容器和负载均衡能判断死活 |
| 访问控制 | HTTP Basic，可开关 | **公网必须开**，见下 |
| 容器化 | Dockerfile + compose | 依赖、时区、JVM 参数全部固化 |

### 上线前你必须拍板的

| 决定 | 选项 | 建议 |
|---|---|---|
| 谁能访问 | 只有你 / 十来个同事 / 公开 | **只有你**。公开就等于做一个财联社转贴站，风险性质变了 |
| 有没有域名 | 有 / 只用 IP | 没域名就只用 IP + 强密码，或 SSH 隧道（更安全） |
| 服务器位置 | 国内 / 海外 | 抓国内站用国内机器。**没有域名就不用备案** |

### 仍然不构成生产级的（我明确说，不粉饰）

| 缺口 | 影响 | 什么时候必须补 |
|---|---|---|
| 只能跑一个实例 | SQLite 写锁 + 无分布式协调，两个容器会抢同一条记录重复调模型 | 需要扛并发或高可用时，换 Postgres + 改认领逻辑 |
| 没有监控告警 | 挂了只有你自己发现 | 真依赖它做决策时，加个定时 curl + 告警 |
| 备份要手动 | 磁盘坏 = 数据全丢 | 数据有历史价值时，配 cron 备份（见第五节） |
| Basic 认证无登录态 | 每次请求都带密码，无登出 | 要多人使用时换 OIDC 或至少加 HTTPS |
| 无 WAF / 限流 | 被刷只能靠机器扛 | 公开访问时 |

**结论：按「只有你一个人看」的口径，它已经够上线了。按「给公司同事用」的口径，还差监控、备份、限流。**

---

## 二、两种部署形态，先选一种

### 形态 A：服务器 + SSH 隧道（推荐，最省事也最安全）

服务只监听 `127.0.0.1`，公网完全摸不到。你在任何机器上：

```bash
ssh -L 18080:127.0.0.1:18080 root@你的服务器IP
# 然后浏览器打开 http://localhost:18080
```

优点：**不需要域名、不需要备案、不需要 HTTPS、不怕扫描器**，Basic 密码即使明文也没有中间人。
缺点：每台设备要开隧道；手机上不方便。

### 形态 B：服务器 + 域名 + HTTPS

直接浏览器访问 `https://news.你的域名.com`，Basic Auth 密码由 TLS 保护。

优点：手机、任何设备都能开。
缺点：域名要备案（国内机器）、要配证书、暴露在公网要面对扫描。

> **必须配 HTTPS 的理由**：Basic 认证的凭据是 Base64，不是加密。纯 HTTP 下等于明文密码在公网上传输。

---

## 三、部署步骤（以一台 Ubuntu 云服务器为例）

### 1. 装 Docker

```bash
# 官方脚本；国内网络慢的话用阿里云的镜像脚本
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
docker --version
```

### 2. 把项目传上去

```bash
# 在本地项目目录
rsync -avz --exclude target --exclude data --exclude .env --exclude .idea \
  ./cls-news/ root@服务器IP:/opt/cls-news/
```

或者推到 git 仓库再在服务器上 clone。**注意 `.env` 不要进仓库**（`.gitignore` 已排除）。

### 3. 配置密码与 key

```bash
cd /opt/cls-news
cp .env.example .env
chmod 600 .env
vim .env        # 填 APP_PASSWORD 和 DEEPSEEK_API_KEY
```

生成一个像样的密码：

```bash
openssl rand -base64 18
```

填完确认一下 **`APP_PASSWORD` 那行不是空的**（`cat .env` 看一眼）：

```bash
grep -c '^APP_PASSWORD=.\+' .env    # 必须输出 1
```

> 不要用 `APP_PASSWORD=` 空着启动。**开启了鉴权但密码为空时，程序会拒绝启动** —— 这是故意的，
> 因为「以为开了鉴权」比「明说没开」危险得多。

`DEEPSEEK_API_KEY` 可以先空着：服务照常启动、新闻照常抓，只是 `analysisReady=false`、分析暂停。
补上 key 后 `docker compose up -d` 重启就会自动开始分析，不需要 `retry-failed`。

### 4. 构建镜像

国内机器必须带镜像源参数，否则拉 `maven` / `eclipse-temurin` 时会 TLS 握手超时（我实测过）：

```bash
docker build \
  --build-arg MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 \
  --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre \
  -t cls-news:0.1.0 .
```

海外机器或 CI 直接 `docker build -t cls-news:0.1.0 .` 即可。

> 如果服务器上 buildx 报 `operation not permitted`（部分受限环境），加 `DOCKER_BUILDKIT=0` 用传统构建器。
>
> 更省事的做法：在本地 build 好，`docker save cls-news:0.1.0 | gzip > cls-news.tar.gz`，传上去 `docker load`。
> 服务器上就不用装 Maven、不用拉 300MB 构建镜像。

### 5. 启动

```bash
docker compose up -d
docker compose ps          # 等 STATUS 变成 healthy
docker compose logs -f     # 看启动日志
```

第一次启动会立刻抓一轮，约 5 秒后库里就有 50 条。

### 6. 验证（四条，缺一不可）

```bash
# ① 健康检查：必须返回 {"status":"UP",...}
curl -s localhost:18080/healthz

# ② 鉴权生效：必须返回 401
curl -s -o /dev/null -w "%{http_code}\n" localhost:18080/api/status

# ③ 凭据正确：必须返回 200
curl -s -o /dev/null -w "%{http_code}\n" -u "admin:你的密码" localhost:18080/api/status

# ④ 数据在长：隔几分钟看 total 有没有变化
curl -s -u "admin:你的密码" localhost:18080/api/status

# ⑤ 分析状态：false 说明 key 没配好（采集仍正常）
curl -s -u "admin:你的密码" localhost:18080/api/status | grep -o '"analysisReady":[a-z]*'
```

> 第 ② 条最容易被跳过，也最要命。**如果它返回 200，说明你的 `.env` 没被读到**，
> 此刻你的全文转贴正在公网上裸奔。检查 `docker compose config | grep APP_AUTH`。

### 7. HTTPS（可选项，当前未启用）

**现状**：域名 `herongcan.icu` 已备案，compose 直接映射宿主机 80，走 HTTP。
想补 HTTPS 时再执行本节，应用和 compose 都不用改结构。

用 Caddy，它是配 HTTPS 最短的路径（自动申请、自动续期）：

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

`/etc/caddy/Caddyfile`：

```
herongcan.icu {
    reverse_proxy 127.0.0.1:8080
}
```

```bash
sudo systemctl reload caddy
```

同时要改两处，否则 Caddy 起不来或者 HTTPS 能被绕过：

- compose 端口映射改成 `127.0.0.1:8080:8080`（80 让给 Caddy）
- `deploy/cloud/deploy.sh` 里 `HOST_PORT` 改回 `8080`

**不要让 8080 直接暴露到公网**，否则别人可以绕过 Caddy 用 HTTP 访问，HTTPS 就白配了。
安全组只开 22 / 80 / 443。

---

## 四、日常运维

```bash
cd /opt/cls-news

docker compose logs -f --tail=100      # 实时日志
docker compose restart                  # 改了 .env 之后
docker compose down                     # 停止（数据在卷里，不会丢）
docker compose up -d --build            # 更新代码后重建

docker stats cls-news                   # 内存/CPU 实际占用
```

### 改配置的三种情况

| 改什么 | 怎么做 | 要不要重建镜像 |
|---|---|---|
| 密码、API key、抓取间隔 | 改 `.env` → `docker compose up -d` | 不要 |
| 提示词 `prompts/analyze-news.md` | 改文件 → 重建镜像 → `up -d` | 要（打在 jar 里） |
| Java 代码 | 同上 | 要 |

> 提示词改一次就要重建镜像，是这个设计的已知代价。好处是配置和代码一起版本化，
> 不会出现「线上提示词和仓库里不一致」。如果以后频繁调提示词，把 `prompts/` 挂成卷即可。

---

## 五、备份（唯一必须做的运维动作）

数据只有一个 SQLite 文件，丢了就是全丢。**不要用 `cp` 直接拷 `.db`**：WAL 模式下还有 `-wal` 文件，
只拷主库会得到一份缺最近写入的备份。

正确做法是用 SQLite 自己的备份命令：

```bash
# 手动备份
docker exec cls-news sh -c 'command -v sqlite3 >/dev/null || exit 1'
docker run --rm -v cls-news_cls-data:/data -v $(pwd)/backup:/backup alpine \
  sh -c 'apk add -q sqlite && sqlite3 /data/news.db ".backup /backup/news-$(date +%F).db"'
```

或者更简单可靠的**停服拷贝**（几秒钟的停机，对自用工具完全可接受）：

```bash
docker compose stop
docker run --rm -v cls-news_cls-data:/data -v $(pwd)/backup:/backup alpine \
  cp /data/news.db /backup/news-$(date +%F).db
docker compose start
```

配成 cron，每天凌晨 4 点跑一次，保留 7 天：

```bash
# crontab -e
0 4 * * * cd /opt/cls-news && ./backup.sh >> backup.log 2>&1
```

### 恢复

```bash
docker compose stop
docker run --rm -v cls-news_cls-data:/data -v $(pwd)/backup:/backup alpine \
  sh -c 'cp /backup/news-2026-11-11.db /data/news.db && rm -f /data/news.db-wal /data/news.db-shm'
docker compose start
```

> 恢复时**必须一并删掉 `-wal` 和 `-shm`**，否则旧 WAL 会覆盖你刚恢复的数据。

### 从本机迁移已有数据到服务器

本机的 `data/news.db` 已经有一百多条真实数据，想带过去：

```bash
# 本地
docker compose stop          # 或先停掉本机 java 进程
sqlite3 data/news.db ".backup /tmp/news.db"

# 上传后写入卷
scp /tmp/news.db root@服务器IP:/tmp/
ssh root@服务器IP "docker run --rm -v cls-news_cls-data:/data -v /tmp:/backup alpine \
  sh -c 'cp /backup/news.db /data/news.db && chown -R 999:999 /data'"
```

---

## 六、故障排查

| 现象 | 最可能的原因 | 怎么确认 |
|---|---|---|
| 容器一直 `unhealthy` | `/healthz` 不通，或镜像里没有 curl | `docker exec cls-news curl -s localhost:8080/healthz` |
| 启动直接退出 | `.env` 里 `APP_AUTH=true` 但密码为空 | `docker compose logs \| tail -30` 会明确写出来 |
| 页面上有凭据但要不到数据 | `DB_PATH` 没指向 `/app/data/news.db` | `docker exec cls-news ls -l /app/data` |
| `total` 不增长 | 财联社接口变了 / 被风控 | `/api/status` 看 `lastError` 和 `runs` |
| `failed` 在涨 | key 失效或余额不足 | `docker compose logs \| grep 分析失败`，修好后 `POST /api/analysis/retry-failed` |
| 日志时间差 8 小时 | 镜像里没设 `TZ` | `docker exec cls-news date` |
| 磁盘满 | 日志没限流 | `docker system df`，compose 已配 `max-size: 10m / max-file: 3` |
| 写入失败 read-only | Linux 绑定挂载 uid 不匹配 | 已用命名卷规避；若改绑挂载需 `chown 999:999 ./data` |

---

## 七、最后三条实话

1. **「上线」在这里的含义是「一个只有你能打开的链接」，不是「一个产品」。** 别在这个阶段加账号体系、加多人权限、加移动端适配 —— 那会把你重新拉回「一直在构建、从不被检验」的老路。它现在值钱的地方是：你每天打开它看新闻，连续一个月。

2. **合规风险不因「只自己看」而消失，只是降到可接受。** 全文字段、每 90 秒一轮、公开可访问，这三件事同时成立才构成问题。现在的配置是：全文 + 90 秒 + 仅自己。守住第三条。

3. **最可能让你放弃的不是技术问题，是「没有新消息的焦虑」。** 财联社电报约 30 条/小时，夜里更少，打开发现没更新是正常的。别因此把间隔调到 10 秒 —— 那只会招来风控，然后把 `lastError` 变成常态。
