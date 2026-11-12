# 笔记本当服务器：给十来个同事访问

没有域名、没有云服务器、只有一台 Mac 笔记本 —— 这条路能走通，但要把代价看清楚再走。

## 先看结论

```
同事不同网  →  必须内网穿透  →  笔记本跑服务 + cpolar 给个免费公网地址
```

三步，10 分钟能发出链接：

```bash
# 1. 装成常驻服务（默认账号密码 admin/admin、开机自启、崩溃自愈、防休眠）
cd /Users/herc/WorkBuddy/2026-11-10-12-44-38/cls-news
./deploy/laptop/install.sh

# 2. 装穿透工具（见下一节），后台跑起来
cpolar http 8080

# 3. 把 cpolar 打印出来的 https 地址 + 账号密码发给同事
```

## 这台笔记本当服务器，有三条硬约束

这不是配置问题，是物理限制，必须接受：

| 约束 | 说明 | 后果 |
|---|---|---|
| **盖子不能合** | macOS 合盖必休眠（无外接显示器时），`caffeinate` 也挡不住 | 合盖 = 服务挂，同事打不开 |
| **不能断网** | 家里/公司的网一断，链接就失效 | 宽带重播、WiFi 抖动都会短暂中断 |
| **不能关机/重启** | LaunchAgent 登录后才拉起，锁屏状态可以 | 重启后要等重新登录 |

屏幕可以熄（`pmset displaysleepnow`），盖子不能合。放桌上当个小主机用。

## 内网穿透：选哪个

同事在国内，所以优先国内节点的工具。

### 方案 A：cpolar（推荐，免费够用）

国内节点，免费版给一个固定的 `*.cpolar.cn` / `*.r2.cpolar.top` 子域名，带宽 1Mbps。

对十个同事看文本列表页，1Mbps 够（整页 HTML + 增量 JSON 也就几十 KB）。

```bash
# 1. 注册账号拿 authtoken：https://www.cpolar.com/
# 2. 下载 macOS 版解压后：
sudo mv cpolar /usr/local/bin/
cpolar authtoken 你的token
# 3. 起隧道（前台试一次，成功后再挂后台）
cpolar http 8080
```

输出的 `https://xxxx.cpolar.cn` 就是给同事的地址，已经自带 HTTPS，不用买证书。

免费版的代价：带宽 1Mbps、并发连接数有限、隧道进程挂了要手动重启。
十个人同时刷新会有点挤，但不会挂 —— 这个服务本身是静态页 + 几十 KB JSON，压力极小。

长期跑就把它也做成常驻：

```bash
cpolar service install
cpolar service start
```

### 方案 B：Cloudflare Tunnel（免费无限带宽，但国内不稳）

```bash
brew install cloudflared
cloudflared tunnel --url http://localhost:8080
```

给一个 `https://xxx-xxx-xxx.trycloudflare.com`。优点是免费、不限带宽、自动 HTTPS；
缺点是国内访问 `trycloudflare.com` 经常超时，而且**每次重启域名都变**，同事的链接会失效。
只建议当 A 方案测速不通时的备胎。

### 方案 C：Tailscale Funnel

固定 `*.ts.net` 域名，但国内访问同样不稳，且要每台设备装客户端。不推荐给同事用。

## 为什么不建议就这么长期跑

笔记本当服务器能跑通，但它有个绕不过去的问题：**你的开发机只要休眠、重启、装系统、出差，十个人就都用不了**。

而且 `sleep 1`（本机实测：空闲 1 分钟就休眠）意味着任何一次忘了开 `caffeinate`，服务就冻住，
同事看到的是"页面转圈"，你自己还完全不知道。

所以我的建议是分两步：

- **今天**：笔记本 + cpolar，先把闭环跑起来，让同事用上、拿到真实反馈。
- **两周内**：阿里云/腾讯云轻量 2核2G，新用户 99 元/年左右。`Dockerfile` 和
  `docker-compose.yml` 都在项目里，`docker compose up -d` 直接起，笔记本彻底解放。

这个顺序的好处是：你现在花的 99 元，是**确认了真的有人用**之后才花的，
而不是先花 99 元再祈祷有人用。

## 给同事的访问说明（可以直接复制）

```
财联社快讯（内部工具）

地址：https://xxxx.cpolar.cn
账号：admin
密码：xxxxxx

首次打开浏览器会弹一个登录框，输入上面的账号密码即可。
页面每 30 秒自动刷新，红色是利好，绿色是利空。
```

## 运维备忘

```bash
# 看服务状态
launchctl list | grep clsnews

# 看日志
tail -f logs/app.log

# 重启
launchctl kickstart -k gui/$(id -u)/com.herc.clsnews

# 看数据条数
sqlite3 data/news.db "select analysis_status, count(*) from news group by 1;"

# 卸载常驻
./deploy/laptop/uninstall.sh
```

## 一个必须知道的风险

`app.display.fulltext = true`，即这个工具存了财联社**全文**。十来个同事内部看，风险不大；
但如果链接被转发到群里、朋友圈，就等于做了个公开的财联社转贴站，版权风险是立刻兑现的。

两个选项，看你怎么权衡：

- 保持全文，但**别把链接往大群里发**，只私聊给需要的人；
- 或者改成 `fulltext: false`（只留摘要 + 原文链接），链接就可以放心一点传播。

改法：`src/main/resources/application.yml` 里 `app.display.fulltext` 改 `false`，
然后 `./mvnw -DskipTests package` 重新打包、重启服务。
