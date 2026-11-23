# cls-news · 财联社电报本地采集器

个人自用版：抓财联社电报 → 存 SQLite → 网页展示（首屏 10 条，可搜索、可向下翻历史）→ DeepSeek 判利好利空 → 顺带记访客明细。

一个 Spring Boot 进程 + 一个 SQLite 文件，没有别的。启动、抓取、分析、展示、接口、访客统计全部在这一个 jar 里。

**当前状态：已部署在阿里云一台 2 核 ECS 上，容器 + 命名卷，推 `main` 由 GitHub Actions 自动发版。**
线上入口是 `https://news.herongcan.icu`（Cloudflare Tunnel，域名未备案，见下方「部署现状」）；`http://8.136.19.57:8080` 仍可直连作退路。

- 要上线、排查线上问题 → [DEPLOY.md](DEPLOY.md)
- 云主机上的备份 / 恢复 / 演练 → [deploy/cloud/README.md](deploy/cloud/README.md)

---

## 跑起来

```bash
cd cls-news
./run.sh
```

然后打开 <http://localhost:8080>

`run.sh` 里已经指定了 `JAVA_HOME=/opt/homebrew/opt/openjdk`（你这台机器默认 java 是 1.8，
Spring Boot 3 需要 17+）。要用别的 JDK：`CLS_JAVA_HOME=/path/to/jdk17 ./run.sh`。

打包成 jar：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk
./mvnw -q package
java -jar target/cls-news-0.1.0.jar
```

> **用 `./mvnw` 而不是 `mvn`。** 项目自带 Maven Wrapper（3.9.9），首次执行会把 Maven 下到
> `~/.m2/wrapper/dists/`，之后直接用。这样本地和 GitHub runner 跑的是同一个 Maven 版本，
> 不会因为某台机器装的是 3.5.9 而编译失败——`spring-boot 3.5.4` 的 compiler 插件要求 ≥ 3.6.3。

用 Docker 跑（推荐，环境全部固化）：

```bash
cp .env.example .env && vim .env      # 填密码和 key
docker compose up -d                  # 默认只绑 127.0.0.1，端口 HOST_PORT（默认 8080）
# 打开 http://localhost:8080
```

`HOST_PORT=18080 BIND_ADDR=0.0.0.0 docker compose up -d` 可以换端口并对外暴露 ——
但 `BIND_ADDR` 改成 `0.0.0.0` 时 `APP_AUTH` 必须是 `true`，否则就是把全文库开给公网。

### 可能踩到的两个本机问题

1. **8080 被占用** → 加 `--server.port=18080` 换端口。
2. **Maven 报 `Connect to 127.0.0.1:7897 failed`** → 你的 `~/.m2/settings.xml` 里配了
   Clash 代理且 `active=true`，Clash 没开就会失败。三个解法任选：
   - 打开 Clash；
   - 或者用一份去掉 `<proxies>` 的临时配置：`mvn -s /tmp/mvn-settings-direct.xml spring-boot:run`；
   - 或者干脆不碰 Maven，直接 `java -jar target/cls-news-0.1.0.jar`（依赖已经下到本地了）。

---

## 实测得到的三条硬事实（改代码前务必先看）

这三点不是文档里抄的，是拿真实接口探出来的，直接决定了实现方式：

| 事实 | 影响 |
|---|---|
| `rn` 上限 50，传 100 返回空数组 | 单次最多抓 50 条 |
| `last_time` / `last_id` / `id` 等游标**全部被服务端忽略**，永远返回最新 50 条 | 服务端没有增量能力，去重只能靠本地 `item_id` 唯一索引 |
| 签名 = `md5( sha1(按 key 排序后的 queryString) )`，`sign` 自己不参与计算 | 参数顺序错了就 401/空数据 |

**由此产生一个必须知道的限制**：拿不到历史分页，所以库里的「历史」是从服务第一次跑起来那天开始累积的，
**翻页翻不到跑之前的内容**。实测日增约 400 条（90 秒一轮 × 50 条/轮的上限远没跑满，是财联社自己发得没那么密）。

刚部署完的第一屏只有抓到的 50 条，往下翻很快到底，这是数据边界不是 bug。
页面顶部「已收录 N 条」是真实数字，不是给它填的假数。

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | **唯一免鉴权**。真读一次库，UP 返回 200 / DOWN 返回 503 |
| GET | `/api/news?limit=10` | 首屏，按发布时间倒序（`limit` 夹在 1~200） |
| GET | `/api/news?since=<nextSince>&limit=50` | 增量，返回 id 更新的条目（**id 升序**），一次最多 `limit` 条 |
| GET | `/api/news?before=<minId>&limit=10` | 向下翻历史，返回 id 更小的条目（倒序） |
| GET | `/api/news/search?q=关键词&limit=10` | 标题 + 正文 LIKE 搜索，结构与 `/api/news` 对齐 |
| GET | `/api/news/{id}` | 单条 |
| POST | `/api/news/{id}/analyze?force=false` | 手动触发单条分析 |
| GET | `/api/analysis/state?ids=1,2,3` | 批量取这组 id 的分析状态（多人同时用时同步已渲染的行） |
| POST | `/api/analysis/retry-failed` | 把 `failed` 全部打回 `pending` 重跑 |
| GET | `/api/status` | 抓取状态、总数、分析进度与 token 用量、上次错误、`analysisReady`、当前账号的 `role`（`rw` / `ro`） |
| POST | `/api/visit/ping` | 前端每次进页面调一次，记访客（聚合 + 明细） |
| GET | `/api/visits` | 访客聚合：今日/累计人数（按设备标识去重）、设备与小时分布、近 N 天 |
| GET | `/api/visits/logs` | 访客明细（IP / UA / 设备 / 时间），支持 `day` `ip` `device` 过滤与 `format=csv`。**游客与只读账号访问返回 403** |

`/api/news` 的响应里带 `stats`，页面只发一个请求就能同时更新列表和状态栏。
页面每 30 秒拉一次增量（`limit=50`），首屏和翻页各取 10 条。

### 游标契约（`nextSince` / `hasMore`）

```
GET /api/news?since=X&limit=N
→ items[]     id 升序（按 id 排，不按 publish_time）
  nextSince   这次真正交付出去的最后一条 id；items 为空时原样返回 X
  hasMore     游标之后还有没有积压
  maxId       全局最大 id，仅为兼容老客户端保留，**不要拿它推进游标**
```

**为什么不能用 `maxId` 当游标**：一次最多只返回 `N` 条，而 `maxId` 是库里最新那条。
拿它推进等于「没拿到的那部分也算看过了」—— 停机、锁屏、批量补数据之后积压超过一页时，
中间那段再也不会被拉，表现为「少了几条，手动刷新才回来」。`nextSince` 只推进到真正交付的位置，
`hasMore` 为真就继续追（前端最多追 5 轮，追不完下一轮接着追）。

**为什么 `since` 的排序键必须是 id**：过滤条件是 `id > ?`，排序若按 `publish_time`，
一页装不下时「本页最后一条」和「本页最大 id」就不是同一行 —— 回填的老消息 id 大、发布时间小，
排最前面把一页占满，游标落在它身上，中间那批被永久跳过。这条有专门的回归用例
（`NewsStoreCursorTest`），别「顺手优化」回去。

**前端只推进到真正交付的位置**：搜索期间不插队也不推进游标，退出搜索时重新拉首屏。
代价是增量窗口内按「入库顺序」呈现，回填的旧消息会短暂跳到最上面一次，下一次整屏刷新恢复。


### 访客明细：直接用 curl 看

明细接口是给排查用的，页面上没有入口，形状也按命令行设计：

```bash
A='admin:你的密码'; H='http://你的IP:8080'
curl -u "$A" "$H/api/visits/logs"                  # 今天最新 50 条
curl -u "$A" "$H/api/visits/logs?limit=200"        # 最多 500，超过会被夹紧
curl -u "$A" "$H/api/visits/logs?before=1234"      # 翻下一页（取上一页返回的 nextBefore）
curl -u "$A" "$H/api/visits/logs?day=2026-11-12"   # 指定自然日，格式必须是 yyyy-MM-dd
curl -u "$A" "$H/api/visits/logs?ip=114.247."      # IP 前缀匹配，只记得网段时用
curl -u "$A" "$H/api/visits/logs?device=wechat"    # wechat|wecom|qq|mobile|tablet|desktop|bot|unknown
curl -u "$A" "$H/api/visits/logs?format=csv" -o visits.csv   # 导出，字段全带引号可放心用 Excel
```

返回里几个字段值得说明：

| 字段 | 含义 |
|---|---|
| `requesterIp` | **这条 curl 自己**进来的 IP。和你刚上报的那条对得上，就说明 IP 记录生效了 |
| `total` / `count` | 过滤条件下的总行数 / 本页行数（`hasMore` 由 `nextBefore` 非 0 判断） |
| `time` / `ts` | 同一条记录的两种时间：给人看的本地时间和给脚本算的 epoch 秒 |
| `vid` | 前端生成的随机 UUID。同 IP 下的两个人靠它区分，无痕模式下为空 |

三条口径上的坑：

- **明细不是每次打开页面一行。** 同一 IP 60 秒内只落一行（`AccessLogStore` 里的内存限流），
  否则连按 F5 二十次就是二十行，"访问次数"退化成"刷新频率"。
- **人数看 `/api/visits` 的 `today` / `total`，不要拿明细的 `total` 当人数。** 明细的
  `total` 是行数（且有 60 秒限流）；人数走 `visitor` 表，按「设备标识 + 自然日」去重。
  同一张出口 IP 下的两个人，vid 不同就算两个 —— 这正是它比 IP 口径准的地方。
  `todayByVid` 是拿明细表另算一遍做交叉验证，正常和 `today` 相等或差一两个。
- **`vid` 为空时退回按 IP 算。** 无痕模式、清过缓存、老客户端都拿不到 vid，
  这时 `k` 会存成 `ip:...`，不会所有匿名访问被并成一个人。
- **这个接口会返回同事的 IP。** 密码是共用的，谁拿到密码谁就能看全部明细 ——
  自用工具可接受，但别把它开给外部，也别在没上 HTTPS 的时候用（Basic 是明文 Base64）。

---

## 访问控制（放公网前必看）

开启了 `app.security.enabled=true` 时，除了 `/healthz`、`/`、`/index.html` 之外全部路径要求
HTTP Basic 认证。**登录框不在浏览器里，而在页面里** —— 原因见下表最后一行。

```bash
APP_AUTH=true APP_USER=admin APP_PASSWORD='你的密码' java -jar target/cls-news-0.1.0.jar
```

四个设计上故意做的选择：

| 选择 | 原因 |
|---|---|
| 密码为空时**拒绝启动** | 最危险的状态不是「没开鉴权」，而是「以为开了、其实密码是空串」 |
| 白名单用**完全相等**匹配，不用 `startsWith`，并拦掉 `..` `//` `;` | 实测过：用 `startsWith` 时 `/healthz/../api/status` 会被放行，能否挡住全看 Tomcat 的实现，等于把安全交给别人的默认值 |
| 不引入 `spring-boot-starter-security` | 那套自带登录页、CSRF、会话，要关的比要开的多；40 行 Filter 行为完全可预期 |
| **`index.html` 免鉴权，凭证由页面自己收** | 移动端（微信 / QQ / UC 内核、Android WebView）收到 `401 + WWW-Authenticate` 不会弹原生输入框，而是把 401 渲染成白页 —— 电脑上正常，同事用手机点开只看到「需要登录」三个字。所以页面先放行，登录层做在页面里，输完把 Base64 塞进 `Authorization` 头打 `/api/**` |

代价要说清楚：`index.html` 本身谁都能拿到（一个空壳，只有标题和样式），数据全在 `/api/**` 后面。
另外 401 **只对非 `/api/` 请求**发 `WWW-Authenticate`，否则 Safari 会在页内登录层上面再弹一个原生框、输完还关不掉。

### 三档角色：可写 / 游客 / 只读

主账号是**可写**的——能触发分析（烧 `daily-cap`）、能读 `/api/visits/logs`（含每个人的 IP），
所以不能直接发给别人。另外两档是给外人用的：

```bash
APP_AUTH=true APP_USER=admin APP_PASSWORD='主密码' \
APP_READONLY='friend:给朋友的密码' \
APP_GUEST='guest:guest' APP_GUEST_DAILY_CAP=10 \
java -jar target/cls-news-0.1.0.jar
```

| 能力 | 可写 `rw` | 游客 `guest` | 只读 `ro` |
|---|---|---|---|
| `GET` 列表 / 搜索 / 单条 / 状态 | ✅ | ✅ | ✅ |
| `GET /api/visits`（聚合访客数、设备分布） | ✅ | ❌ 403 | ✅ |
| `GET /api/visits/logs`（明细，含 IP / UA） | ✅ | ❌ 403 | ❌ 403 |
| `POST /api/news/{id}/analyze` | ✅ 受 `daily-cap` | ✅ 另受 `guest-daily-cap` | ❌ 403 |
| `POST /api/news/{id}/analyze?force=true`（无视新鲜度重算） | ✅ | ❌ 403 | ❌ 403 |
| `POST /api/analysis/retry-failed` | ✅ | ❌ 403 | ❌ 403 |
| `POST /api/visit/ping`（访客上报，无副作用） | ✅ | ✅ | ✅ |

几个实现上的选择：

| 选择 | 原因 |
|---|---|
| 角色判定在 **Filter 里**，不在每个 Controller 上 | 新增接口不会因为漏注解而失效，默认「非 GET 一律挡」 |
| 游客允许 `POST .../analyze`，拒绝 `force=true` | 游客就是来点那一下看结论的；`force` 是改完 prompt 后重算用的，点一次就重复烧一次钱 |
| 游客连 `/api/visits` 聚合也拒 | 站上有多少人在用是运营数据，跟演示无关 |
| 只读拦 `/api/visits/logs` 但不拦 `/api/visits` | 聚合数无害，明细里有具体 IP |

**配额是两层**：全局 `daily-cap` 管今天全站花多少钱，`guest-daily-cap` 管这个角色最多花几次。
只判角色额度的话，游客能突破全站上限。调用记录按 `actor`（`rw` / `guest` / `ro` / `auto`）落库，
后台自动分析记 `auto` 不吃游客额度；额度按**角色**汇总，多开几个游客账号不会翻倍。
老库的 `analysis_call` 缺 `actor` 列时启动会自动 `ALTER TABLE`，历史记录按全站口径算。

**游客密码是故意公开写在登录页上的**（`index.html` 的 `.gate-guest`），藏起来等于没人能试，
所以止损线就是那个额度：`APP_GUEST_DAILY_CAP=0` 等于关闭游客权限。
改账号时记得同步改页面文案，两处不一致会让来试的人一直 401。

前端配合：游客登录后顶栏收起访客统计（连分隔点）、挂「游客」标记、分析按钮照常渲染，
额度用完时行内显示服务端的 429 文案且**不给「重试」按钮**；只读则是「只读」标记 + 无分析按钮。
这些都只是界面层，真正的拦截在服务端。

格式写错（少了冒号）或用户名与已有账号重名时会**打 warn 并跳过**，不会静默生效——
发出去的账号一直 401 却查不出原因，比启动失败更难排查。

### 登录框只在「服务端真的在鉴权」时才出现

`app.security.enabled` 默认是 `false`（本地开发直接 `java -jar` 就是这个状态），此时
`BasicAuthFilter` 这个 Bean **根本不会被创建**，所有 `/api/**` 一律 200。

如果这时候照旧摆一个登录框，它会「接受任何账号密码」—— 因为登录框的唯一判据是
「`/api/status` 返回成功了吗」，而没人校验的接口总是成功。实测过：本地跑起来输 `1/1`
也能进，看起来像鉴权漏了，其实是**没有任何人校验**。这层没有校验作用的界面比明说
「没开鉴权」更危险，会让人以为数据是受保护的。

所以页面启动时先**不带凭证**打一次 `/api/status`：

| 结果 | 含义 | 页面行为 |
|---|---|---|
| `200` | 服务端没开鉴权 | 不出现登录框，顶栏挂「本地模式 · 无鉴权」标记 |
| `401` | 服务端在鉴权 | 出登录框，输错就是「账号或密码不对」 |

**本地要验证登录流程，必须显式把鉴权打开**，否则你测的是一个不存在的关卡：

```bash
APP_AUTH=true APP_USER=admin APP_PASSWORD=t3st java -jar target/cls-news-0.1.0.jar
# 然后用错的密码试一次，应该停在「账号或密码不对」
curl -u 1:1 localhost:8080/api/status    # 期望 401
```

> Basic 认证的凭据是 Base64，**不是加密**。公网必须套 HTTPS，否则等同明文密码。
> 最省事的替代方案：端口只绑 `127.0.0.1`，用 SSH 隧道访问 —— 不需要域名、备案、证书。

**上反向代理（Caddy / Nginx / CDN）时必须设 `APP_TRUST_XFF=true`**，否则 `remoteAddr` 全是代理机地址，
所有访客会被算成一个人。反过来，没有代理时**不要**开：`X-Forwarded-For` 客户端可以随便伪造，
一条 `curl -H 'X-Forwarded-For: 8.8.8.8'` 就能往访客表里塞一个不存在的 IP。


---

## 配置（`src/main/resources/application.yml`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.source` | `cls-api` | 改 `mock` 可断网看界面 |
| `app.interval-ms` | `90000` | 服务端抓取间隔。**不要**设成 30000，见下方合规 |
| `app.display.fulltext` | `true` | 改成 `false` 只返回 60 字摘要（公开口径） |
| `app.analysis.enabled` | `true` | 大模型分析总开关，关掉之后**连手动点击也会被拒绝**，采集照常 |
| `app.analysis.auto` | `false` | `false` = 只有点「分析」才调模型；`true` = 抓进来就排队自动分析。**默认手动**：日增 400+ 条，全自动等于把没人在意的一半也送去模型，而且队列从旧往新跑，首屏最新的反而最后才分析 |
| `app.analysis.provider` | `deepseek` | `deepseek` 真实调用 / `stub` 空实现 |
| `app.analysis.daily-cap` | `300` | 每日分析调用上限，防误触烧钱；超限返回 429 而不是静默跳过 |
| `app.analysis.api-key` | 空 | 留空则回落到环境变量 / `~/.codex/auth.json` |
| `app.analysis.model` | `deepseek-flash` | 实测可用：`deepseek-flash`、`deepseek-v4-pro` |
| `app.analysis.max-tokens` | `2000` | **别调小**：推理型模型的思考过程也算在内，600 会让 JSON 被截断 |
| `app.analysis.timeout-ms` | `30000` | 单次模型调用硬超时（`sendAsync` + `get(timeout)`，不是靠 HTTP 超时） |
| `app.http.timeout-ms` | `12000` | 抓财联社的超时 |
| `spring.task.scheduling.pool.size` | `4` | **别改回 1**：单线程时一个卡住的模型调用会把采集一起拖死 |
| `app.security.enabled` | `false` | 环境变量 `APP_AUTH`。放公网必须 `true` |
| `app.security.username` / `password` | `admin` / `admin` | 环境变量 `APP_USER` / `APP_PASSWORD`。**公网部署必须用 `APP_PASSWORD` 覆盖掉这个默认值**；密码设成空串 + 开启鉴权 = 启动失败 |
| `app.security.read-only` | 空 | 环境变量 `APP_READONLY`，格式 `user:pass,user2:pass2`。只能 GET，写接口 403，看不到访客明细。发这个给朋友 / 面试官，别发主账号 |
| `app.security.guest` | 空 | 环境变量 `APP_GUEST`，格式同上。能点分析（受 `guest-daily-cap` 约束），访客数据整个不可见。密码公开写在登录页上 |
| `app.security.guest-daily-cap` | `10` | 环境变量 `APP_GUEST_DAILY_CAP`。游客每天能分析几条，按角色汇总；设 `0` 等于关闭游客权限 |
| `app.security.trust-forwarded-headers` | `false` | 环境变量 `APP_TRUST_XFF`。挂反向代理时才开 |
| `spring.datasource.url` | `./data/news.db` | 环境变量 `DB_PATH`。容器里指向挂载卷 `/app/data/news.db` |


关于频率：最初的需求是「半分钟爬一次」，实际把两个 30 秒拆开了 —— **页面每 30 秒拉增量**
（用户感知实时），**服务端每 90 秒抓一次**（请求量减半，被风控的概率低很多）。
页面刷新频率在 `index.html` 末尾的 `setInterval` 里，服务端频率在 yml 里，互不影响。

---

## 大模型（已接入 DeepSeek）

`provider=deepseek` 时创建 `DeepSeekAnalyzer`，实测单条 1.5~3 秒、约 400~800 token。

**key 不需要写进任何文件**，取值顺序：

1. `--app.analysis.api-key=sk-xxx`（优先级最高，临时换 key 用）
2. 环境变量 `DEEPSEEK_API_KEY`
3. `~/.codex/auth.json` 里的 `OPENAI_API_KEY`（仅当 `base-url` 指向 deepseek 时才读，避免把别家的 key 发出去）

**提示词不在代码里**：直接改 `src/main/resources/prompts/analyze-news.md` 的 `## System` / `## User` 两段，重启生效，不用重新编译。占位符 `{{publishTime}}` `{{level}}` `{{stocks}}` `{{content}}`。

### 四个实测踩到的坑（都已处理）

| 坑 | 后果 | 处理 |
|---|---|---|
| `max_tokens=600` 太小 | 推理型模型的思考也计入 completion，JSON 被从中间截断；极端情况正文为空 | 默认 2000；并检查 `finish_reason=length` 给出明确报错 |
| 单线程调度 + 模型请求挂住 | 机器休眠导致 HTTP 超时失效，`send()` 永久阻塞 → **采集和分析一起停摆**（实测 35 分钟零抓取） | 调度线程池设为 4；模型调用改 `sendAsync` + `get(timeout)` 硬超时兜底 |
| 模型会编股票代码 | 实测把新易盛写成 `sz300500`（真值 `sz300502`） | 用财联社原文 `stock_list` 里的权威代码按名称回填 |
| 模型偶尔硬给方向 | 信心不足也给 bullish/bearish | `confidence < 0.5` 一律降级为 `neutral`（提示词里有约定，代码再兜一层） |

### 第一次跑批的分布（84 条，早期实测）

这组数字是刚接通模型时一次跑 84 条得到的，用来确认「提示词没把模型逼成只会喊涨」。
现在的量级是收录约 1200 条、已分析 120+ 条，分布没有重新统计过（页面上的方向标签就是当时的输出）。

| 指标 | 实测值 |
|---|---|
| JSON 非法 | 0 / 84 |
| 中性占比 | 64%（说明没有在硬编方向，低于 20% 才要警惕） |
| 平均置信度 | 0.45（最高 0.72） |
| 关联个股 | 56 条 |
| 累计 token | 约 3.2 万（84 条） |

手动触发单条：

```bash
curl -u admin:你的密码 -X POST 'http://localhost:8080/api/news/1/analyze'
curl -u admin:你的密码 -X POST 'http://localhost:8080/api/analysis/retry-failed'   # 调大 token 或换 key 后重跑失败项
```

> 开了鉴权之后这两个 curl 必须带 `-u`，否则拿到的是 401 —— 以前没带也能调，是因为当时还没开鉴权。


---

## 分析结果存哪、会不会重复调模型

**结果落库，和新闻同一条记录。** `news` 表里四个字段：`analysis_status`、`analysis_json`（模型返回的完整 JSON，原样存字符串）、`analyzed_at`、`model`。重启后页面直接读 `analysis_json` 渲染，不需要重新调模型。

状态机：

```
pending ──(原子认领)──> analyzing ──> done
                           └────────> failed
```

| 场景 | 会不会再调模型 |
|---|---|
| 重启服务，历史 `done` 记录 | **不会**，一条都不碰（实测 108 条：`analyzed_at` 与内容哈希完全未变） |
| 重启服务，库里还有 `pending`（从没分析过） | 会，逐条补上 |
| 进程在分析中途被杀，留下 `analyzing` | 会重跑**这一次**（启动时打回 pending，日志会提示「发现 N 条上次未完成的分析」） |
| 分析失败 `failed` | **不会自动重试**，需要 `POST /api/analysis/retry-failed` |
| 同一条消息重复入库 | 不会，`item_id` 唯一索引拦住，`INSERT OR IGNORE` |

所以准确的表述是：**正常路径下每条消息只调一次模型；唯一例外是分析到一半进程被杀，那条会多调一次。** 这是 at-least-once，不是 exactly-once —— 想要严格一次得在调用前先把 key 写库、再靠幂等去重，对这个工具不值得。

**认领是原子的**：`UPDATE ... WHERE id IN (SELECT ... LIMIT ?) RETURNING *`，不是「先 SELECT 再 UPDATE」。原因是我测试时真的起过两个实例连同一个 sqlite，两边读到同一批 `pending` 各调一次模型 —— 同一条消息被扣两次钱。改成一条 SQL 后，抢到的那条立刻变成 `analyzing`，另一个实例就看不见了。

> 顺带一个运维提醒：**不要同时跑两个实例**。虽然认领已经能防重复分析，但两边都会各自抓取、各自打日志到同一个 `run.log`，排查时分不清谁是谁。用 `lsof data/news.db` 能看到有几个进程在连这个库。

---

## 合规（口径已经变了，这段需要你拍一次板）

设计这个工具时的口径是「只自己看」：全文只存本机、不暴露公网。**现在实际状态和这个口径已经不一致了**，
如实写在这里，而不是把文档改得很漂亮：

| | 当初的口径 | 现在的事实 |
|---|---|---|
| 是否公网可访问 | 不暴露 | 暴露在 `http://<IP>:8080`，靠 Basic 密码挡着 |
| 谁能看 | 只有我 | 十来个同事共用同一个账号 |
| 正文 | `fulltext = true` 存全文 | 仍然 `true`，也就是每个登录的人都能看到财联社全文 |

也就是说：**现在实质上是一个「小范围内可访问的全文转贴」。** 风险从「理论问题」变成了「取决于多少人知道地址」。

三件事按重要性排：

1. **账号要收**。一个共用密码 + 全文，等于访问控制只剩一层，且泄露后无法定位是谁。
   现在支持三种角色（见「三档角色」），但**角色 ≠ 一人一号**：还是查不出具体是谁在访问。
   要真正做到能追责，得给每人一个账号并记到访问日志里。
2. **`fulltext` 该改成 `false`**。改成 `false` 后接口只返回 60 字摘要 + 原文链接，页面点标题跳财联社原文 ——
   对「看个大概」的同事来说几乎没损失，但性质从「转载」退回「索引」。
   **发只读账号给朋友之前，这一条更应该先做** —— 人数越多，「小范围」这个前提越站不住。
3. 保留版权声明与原文链接（页面已经带了原文链接，声明没有）。

采集侧是合规的，这部分没变：90 秒一轮、单轮 50 条，属于低频访问，UA / Referer 如实声明，没有绕过任何反爬。


---

## 自动部署（GitHub Actions）

`.github/workflows/deploy.yml`。推到 `main` 就跑，也可以手动触发（Actions → deploy → Run workflow）。

**但「推到 main」不等于一定触发**，`paths-ignore` 里有三类改动会被跳过：

```yaml
paths-ignore:
  - '**.md'            # 只改文档不会发版（改 README 不会触发部署）
  - 'deploy/laptop/**' # 本机 launchd 那套和云上无关
  - 'tools/eval/**'    # 标注/评分脚本，不进运行镜像
```

所以「我改了代码但线上没变」的第一个排查点不是流水线，而是**那次提交里到底有没有非文档文件**。
想强制发一次就手动 Run workflow。

**它和本地 `deploy.sh` 是同一份逻辑**，不是第二套：流水线只负责在 runner 上 `./mvnw package`，
然后调 `deploy/cloud/deploy.sh`，脚本靠 `CI` 环境变量跳过「本地构建」那一段。
维护两份部署脚本必然漂移，所以没有另写一份 CI 专用版本。

### 部署现状（2026-11 中）

| 项 | 现状 |
|---|---|
| 入口 | `https://news.herongcan.icu`，证书由 Cloudflare 边缘签发，源站不装证书、不开 80/443 |
| 域名 | `news.herongcan.icu` CNAME → `<tunnel-id>.cfargotunnel.com`。**未做 ICP 备案**，走 Cloudflare Tunnel 出站连接绕开拦截（详情见 [deploy/cloud/README.md](deploy/cloud/README.md) 第五节） |
| HTTPS 的真实代价 | CF 免费版把境内流量分配到美西/欧洲 PoP（实测 `cf-ray` 落 `LAX`/`AMS`），首字节 **1.2–3 秒、偶发 10 秒+**，而 IP 直连是 69ms。解决了「有没有 HTTPS」，没解决「快不快」 |
| 备案拦截的判定方式 | 按 **HTTP Host 头**，跟端口无关。带未备案域名走 80 拦、走 8080 一样拦，换端口绕不过去；用 IP 直连不在拦截范围 —— 这就是现在同事用 `IP:8080` 的原因 |
| 回滚 | 没有自动回滚，`git revert` 后重新推一次 |
| 备份 | 每日 03:30 自动 `sqlite3 .backup` + gzip，见下节 |

### 三条决定了方案边界的实测事实

| 事实 | 后果 |
|---|---|
| 目标机到 `registry-1.docker.io` 超时（实测 `000`） | **不能**「CI 构建镜像 → 推 Docker Hub → 服务器 pull」；基础镜像 `eclipse-temurin:17-jre` 靠远端本地缓存 |
| 目标机 2 核 / 1740MB | 不能在上面跑 Maven，否则 OOM killer 只留一句 `Killed`；构建必须在 runner |
| SQLite 单文件 + 单命名卷 `cls-news_cls-data` | 不能多实例、不能滚动更新；重启窗口约 20–40 秒（实测一次完整部署 56 秒），故 `concurrency` 不取消进行中的任务 |

所以这条路是「CI 编译出约 36MB 的 jar → rsync 上传 → 远端 `docker compose up -d --build`」，
跟手敲脚本完全一致，只是不用再开终端。


### 一次性配置

**1. 生成部署专用密钥**（不要带 passphrase，CI 里没有交互终端）

```bash
ssh-keygen -t ed25519 -N '' -C 'gha-cls-news' -f ~/.ssh/cls-news-deploy
ssh-copy-id -i ~/.ssh/cls-news-deploy.pub root@8.136.19.57
```

**2. 填 Secrets**（仓库 Settings → Secrets and variables → Actions → New repository secret）

| 名称 | 值 | 必填 |
|---|---|---|
| `SSH_HOST` | `8.136.19.57`（**纯 IP，不要带 `root@`**，`ssh-keyscan` 不认） | 是 |
| `SSH_KEY` | `cat ~/.ssh/cls-news-deploy` 的**全部内容**，含首尾 `-----BEGIN/END-----` 两行 | 是 |
| `DEEPSEEK_API_KEY` | `sk-...` | 否。不配就沿用远端 `/opt/cls-news/.env` 里已有的 key |

**3. 确认安全组**：阿里云控制台 → 该实例 → 安全组 → 入方向，22 端口来源必须是 `0.0.0.0/0`。
主机侧已经全放开了（`sshd` 监听 `0.0.0.0:22`、`ufw` inactive、`iptables` 默认 ACCEPT，
无 fail2ban），唯一可能挡住 GitHub runner 的就是安全组。之前能部署成功只能证明
**你本地那个 IP** 进得来，不代表境外 runner 也进得来。

### 运行一次会看到什么

1. `./mvnw -B package`（含 47 个单元测试；首次约 2 分钟，有 Maven 缓存后 40 秒左右）
2. 校验 `target/cls-news-0.1.0.jar` 是 Spring Boot fat jar（否则服务器上会报
   `no main manifest attribute`，得翻半天日志）
3. `ssh-keyscan` 写 `known_hosts`（不用 `StrictHostKeyChecking=no`，那个等于关掉中间人防护）
4. 跑 `deploy.sh`：连通性 → docker 检查 → 复用远端 key → rsync → 写 `.env` → compose → healthz 轮询
5. 失败时自动 `docker compose logs --tail=100` 打到 job 日志里 —— 不用等人有空 ssh 上去

### 出问题先看哪

| 现象 | 原因 |
|---|---|
| `连不上 ... 检查仓库 Secrets 里的 SSH_HOST / SSH_KEY` | 安全组没放行境外，或 `SSH_HOST` 带了 `root@` |
| `ssh-keyscan 没拿到 ... host key` | 22 端口从公网不可达（安全组） |
| `远端 .env 里的 DEEPSEEK_API_KEY 异常` | secret 没配且远端 `.env` 也被清过；补 secret 重跑 |
| rsync 卡住直到 20 分钟超时 | 跨境链路问题；`ServerAliveInterval` 会让它早点失败而不是静默挂住 |
| healthz 90 秒不就绪 | 看 job 末尾自动抓的日志；多数是 jar 起不来（内存/端口） |

### 明确不做的事

- **不推镜像仓库**：服务器拉不动 Docker Hub；要用 registry 得先接阿里云 ACR，收益不抵改造量。
- **不自动回滚**：镜像 tag 固定 `cls-news:0.1.0`，旧镜像会被覆盖。要回滚就 `git revert` 后重新触发。
- **不上 K8s / SAE / 函数计算**：有状态的单实例 + 本地 SQLite 卷，迁移成本远大于收益。
- **不在服务器上编译**：1.7G 内存跑 Maven 必死，这条是踩过的坑。

### 另一条路：云效 Flow（当前没在用）

`deploy/cloud/deploy-flow.sh` 是给云效用的变体，和 `deploy.sh` 分工不同。**现在实际只走 GitHub Actions，
这个脚本是备选，留着是因为真要用的时候不用重写：**

| | `deploy.sh` | `deploy-flow.sh` |
|---|---|---|
| 在哪跑 | 本地 / GitHub runner | **目标机本机** |
| 传输 | ssh + rsync | 不需要，Runner 已把制品下载到本地 |
| 做什么 | 构建 + 传输 + 重建 + 验健康 | 解压制品 + 换 jar + 重建 + 验健康 |

云效的 Runner 装在这台机器上、主动往外连，所以这条链路**不需要公网开放 22 端口**，
安全组可以收紧到只放行自己的 IP。脚本不碰 `.env`，API key 沿用 `deploy.sh` 写好的那份。

---

## 备份与恢复（在线，不影响线上）

数据库就一个文件，所以备份本质是「在库还在写的状态下拿到一份一致的副本」。
`deploy/cloud/backup.sh` 做的是：

1. `sqlite3 .backup`（Online Backup API）—— **不是 `cp`**。WAL 模式下 `synchronous=NORMAL` 时，
   最近的事务还躺在 `-wal` 里没有回写主文件，`cp` 出来的主文件会少一段数据（实测差 1 行）；
2. 对副本跑 `PRAGMA integrity_check`，不是 `ok` 就直接失败，不产出「看起来成功」的坏备份；
3. `PRAGMA wal_checkpoint(TRUNCATE)` 并删掉副本的 `-wal` / `-shm` —— 只打包主文件却留下 `-wal`，
   恢复时 SQLite 会因为 salt 不匹配而回放一个不属于这份副本的 WAL；
4. gzip + 权限 600，保留 7 天，可选上传 OSS。

```bash
# 服务器上
sudo systemctl list-timers cls-news-backup.timer     # 每天 03:30，Persistent=true（关机错过会补跑）
sudo /opt/cls-news/deploy/cloud/backup.sh            # 手动备份一次
```

恢复脚本支持**演练模式**，不动生产：

```bash
sudo ./restore.sh <备份文件> --drill    # 只读：完整性、行数、数据跨度、与线上对比算 RPO
sudo ./restore.sh <备份文件> --yes      # 真恢复：现场先另存一份，再无条件删 -wal/-shm
```

实测一次演练的结论：备份 678 行 / 线上 768 行 / RPO 落后 2 小时 / 分析状态分布与线上一致。

**当前 RPO 是 24 小时**（每天一次）。要实现小时级 RPO，把 `cls-news-backup.timer` 的
`OnCalendar=daily` 改成 `OnCalendar=hourly` 即可，脚本本身不用动 —— 库才 600KB 上下，一小时一份不心疼。
异地副本（OSS）还没做，这是现在最大的单点：机器整台挂了，备份和原库一起没。


---

## 目录

```
src/main/java/com/herc/clsnews/
├── config/AppProps.java         全部可调参数（一个类装完，没有散落的 @Value）
├── source/
│   ├── NewsSource.java          来源适配层（换接口只改这里）
│   ├── ClsApiSource.java        真实接口 + 签名
│   └── MockSource.java          假数据
├── model/                       News / NewsView / RawItem
├── store/
│   ├── NewsStore.java           新闻：列表、增量、翻页、搜索、分析状态
│   ├── VisitStore.java          访客：设备标识 + 自然日去重的聚合口径（含旧 IP 表迁移）
│   └── AccessLogStore.java      访客：明细写入 + 60 秒限流 + 设备/小时分布 + 明细查询
├── job/
│   ├── IngestJob.java           抓取调度（间隔 app.interval-ms）
│   ├── AnalysisWorker.java      分析调度（enabled=false 时不创建）
│   └── AccessLogCleaner.java    每天 04:40 删 90 天前的访问明细（错开 03:30 备份）
├── ai/
│   ├── AnalysisService.java     分析编排：认领、缓存判定、日额度、报错码
│   ├── Analyzer.java            接模型的唯一入口
│   ├── DeepSeekAnalyzer.java    DeepSeek 实现
│   ├── PromptTemplate.java      读 md 提示词 + 占位符渲染
│   └── StubAnalyzer.java        空实现（provider=stub 时用）
└── web/
    ├── NewsController.java      /api/news|search|analysis|status|visits（9 个接口）
    ├── VisitController.java     /api/visit/ping
    ├── HealthController.java    /healthz（唯一免鉴权）
    ├── BasicAuthFilter.java     Bearer / Basic 两路鉴权，白名单 + 角色矩阵 + 路径穿越检查
    ├── AccountStore.java        内存账号表（rw / guest / ro），常数时间比较
    ├── TokenStore.java          内存令牌表：签发、校验、一次性旋转、按 session 作废
    ├── AuthController.java      /api/auth/token|refresh|logout
    ├── LikePattern.java         用户输入 → LIKE 模式（通配符转义）
    ├── ClientIp.java            取真实 IP（默认不信 XFF）
    └── UserAgent.java           UA → device / os / browser，纯静态零依赖

src/main/resources/
├── static/                     前端构建产物（index.html + assets/，旧版留作 legacy.html 回滚入口）
├── prompts/analyze-news.md      提示词 + 输出结构（改这里就够了）
└── schema.sql                   建表（news / analysis_call / visit / visitor / access_log）

src/test/java/com/herc/clsnews/
├── store/NewsStoreCursorTest    游标边界：since 排序键、截断、翻页不重不漏
├── store/NewsStoreClaimTest     分析状态迁移 + 两线程抢同一条
├── web/LikePatternTest          % _ \ 转义，以及「搜 100% 不该命中 100 元」
├── web/AuthMatrixTest           rw / guest / ro × 各端点，401 不带 challenge
├── web/NewsApiCursorTest        /api/news 的 nextSince / hasMore 契约
├── web/AnalysisJsonTest         analysis_json 脏数据、少字段、截断
├── ai/QuotaTest                 游客额度 / 全站上限 / 按 actor 计数 / 跨天
└── support/                     临时 sqlite + standalone MockMvc 测试台

deploy/
├── cloud/                       云上那一套：deploy.sh、backup.sh、restore.sh、systemd timer
└── laptop/                      本机 launchd 常驻（已不用，留作备用）

tools/eval/                      分析质量评估：labels.csv 人工标注 + score.py 打分
```

---

## 测试

```bash
./mvnw test                    # 47 个用例，约 3 秒
./mvnw test -Dtest=NewsApiCursorTest   # 只跑游标那一组
```

**不起 `@SpringBootTest`。** 起全容器会让 `@Scheduled` 的采集与分析任务真的跑起来（要假 key、
会真花钱），失败原因还会被一堆 Bean 初始化日志埋掉。现在的做法是：`store` 与过滤器用真实
`JdbcTemplate` + `@TempDir` 里的临时 sqlite 构造，接口层用 standalone `MockMvc` 直接挂
`NewsController`，只把「会联网的那两个」（`IngestJob` / `NewsSource`）换成假对象。

**建表直接执行生产用的 `schema.sql`**，不手抄一份 —— 抄一份的话 schema 改了测试还是绿的。

| 用例 | 钉住什么 |
|---|---|
| `NewsStoreCursorTest` | `since` 的过滤键是 id，排序键也必须是 id；截断时游标落在已交付那条；`before` 翻页不重不漏 |
| `NewsApiCursorTest` | 积压超过一页时 `nextSince` 停在交付处（**不是全局 `maxId`**）；空页游标原地不动；`before` 不碰增量游标 |
| `NewsStoreClaimTest` | `claimOne` / `claimPending` / `resetStuck` / `releaseClaims` 的状态迁移；两个线程抢同一条只有一个成功 |
| `LikePatternTest` | `%` `_` `\` 转义，以及「搜 `100%` 不该命中 `100元`」（只断言字符串不够，要真查库） |
| `QuotaTest` | 游客 10 条封顶、全站上限对所有人、按 actor 分开计数、跨天清零 |
| `AuthMatrixTest` | rw / guest / ro × 各端点；**任何 401 都不带 `WWW-Authenticate`**；白名单不能被 `..` / `%2f` 绕过 |
| `AnalysisJsonTest` | 脏 JSON 只影响那一条、少字段照常渲染、`/api/analysis/state` 不整条 500 |

**怎么确认这些测试真的有用**：把实现改回旧写法，看它是不是红的。实测两次：

```
since 改回 ORDER BY publish_time  → NewsStoreCursorTest 2 条红（expected 2,3 / was 3,2）
游标改回 store.maxId()            → NewsApiCursorTest 1 条红（nextSince 期望 52，实际 62）
```

改回去再跑就全绿 —— 说明断言咬的是行为，不是「代码长什么样」。

---

## 下一步（按优先级，2026-11-13 的现状）

上一版这里列过四件事：跑满一天、抽查 20 条、攒 30 条人工标注做回归、部署上云。前三件里前两件做完了，
**标注回归还停在「`tools/eval` 已导出 30 条、`human_sentiment` 栏还空着」**。剩下的是这些：

| 优先级 | 事情 | 为什么 |
|---|---|---|
| 1 | **把访问密码从 `admin/admin` 换掉** | 现在知道地址的人试一下就能进，而里面是财联社全文。改密码要改 `.env` 的 `APP_PASSWORD` 再重新部署 —— 没有热更新，因为 `BasicAuthFilter` 的 `doFilter` 是 `final`，CGLIB 代理不到，配置中心的刷新对它无效 |
| 2 | **备份改小时级 + 加异地副本** | 现在 RPO 24 小时，且备份和原库在同一台机器上，机器挂了等于没备份 |
| 3 | **`fulltext` 改 `false`** | 让性质从「小范围转载」退回「索引」，见上面合规那节 |
| 4 | **换库之前先扩测试** | `src/test` 已有 47 个用例（游标边界 / 状态迁移 / 并发抢锁 / LIKE 转义 / 配额 / 权限矩阵 / JSON 兜底），CI 里 `./mvnw package` 会执行。但这些只覆盖 store 与 filter，`IngestJob`、`AnalysisWorker`、`ClsApiSource` 的解析还没有用例 —— 想换数据库或改采集协议之前得先补上这些 |
| 5 | 隧道链路太慢 | 迁香港/新加坡轻量（域名直接 A 记录 + Caddy 自动签证书），或把 ICP 备案办下来走境内直连。两者都要改 `BIND_ADDR=127.0.0.1` 并收掉安全组的 8080 |
| 6 | 清理 `visit` 表的历史脏数据 | 79 条里有 66 个是只来过一次的扫描器 IP（旧拦截器口径留下的），要不要删需要你拍板 |

采集和分析侧目前没有已知问题：`lastError` 长期为空、日增 400+ 条、CPU 1%、`load average 0.00`。
`daily-cap: 300` 是当初按「日增 550 条的一半」估的，现在 pending 积压是常态（手动模式没人点就不会减少），
这个数是否卡住过积压值得看一眼 —— 如果 `analysisCapped` 经常是 true，说明限额定低了。

