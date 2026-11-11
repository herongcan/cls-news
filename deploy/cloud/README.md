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
> 反正都是 IP 直连，用非标端口能省掉整个备案流程，代价只是地址后面多写 `:8080`。

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

# 看采集和分析进度
ssh root@IP "cd /opt/cls-news && docker compose exec cls-news \
  sqlite3 /app/data/news.db 'select analysis_status, count(*) from news group by 1'"

# 看有没有报错
ssh root@IP "cd /opt/cls-news && docker compose exec cls-news \
  sqlite3 /app/data/news.db 'select publish_time, substr(last_error,1,80) from news where analysis_status=\"failed\" order by id desc limit 10'"
```

### 更新代码

改完代码直接重跑：

```bash
./deploy/cloud/deploy.sh root@你的公网IP
```

脚本会沿用远端已有的 key；访问密码是固定的 `admin/admin`，不会变，同事不会掉线。
想换密码就在命令前加 `APP_PASSWORD=新密码`。

### 备份

SQLite 就一个文件，每天拷一份足够：

```bash
ssh root@IP 'cat > /etc/cron.daily/cls-backup <<"EOF"
#!/bin/sh
mkdir -p /root/backup
docker run --rm -v cls-news_cls-data:/data -v /root/backup:/backup \
  alpine tar czf /backup/news-$(date +%F).tar.gz -C /data .
find /root/backup -name "news-*.tar.gz" -mtime +14 -delete
EOF
chmod +x /etc/cron.daily/cls-backup'
```

保留 14 天。哪天误删了数据，解压回去重启即可。

---

## 五、两个你必须知道的限制

### 1. 现在没有 HTTPS

IP 直连 + HTTP，Basic Auth 的密码是**明文过公网**的。同一个 WiFi 下会抓包的人能看到。

我的判断是能接受，理由：密码是内部共享的、内容本身是公开新闻、过期就换。
但你要清楚这是「够用」而不是「安全」。

想上 HTTPS 有两条路：

- **买域名 + 备案**：域名十几块，但国内服务器备案要 1-2 周（需要域名实名、拍照核验）。
  备案后用 80/443 + Caddy 自动证书，地址变成 `https://news.你的域名.com`，同事体验最好。
- **自签证书**：10 分钟搞定，但每个同事浏览器都会跳「不安全」警告，
  得手动点「继续访问」。十个人就是十次解释，不划算。

十来个同事的内部工具，我的建议是**先不上**。等有人说「地址太长记不住」的时候，再去做域名那套。

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
