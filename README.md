# cls-news · 财联社电报本地采集器

个人自用版：抓财联社电报 → 存 SQLite → 网页展示最新 100 条 → DeepSeek 判利好利空。

一个 Spring Boot 进程 + 一个 SQLite 文件，没有别的。启动、抓取、分析、展示、接口全部在这一个 jar 里。

**要上线（放进 Docker、配访问密码、跑在云服务器上）看 [DEPLOY.md](DEPLOY.md)。**

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
mvn -q package
java -jar target/cls-news-0.1.0.jar
```

用 Docker 跑（推荐，环境全部固化）：

```bash
cp .env.example .env && vim .env      # 填密码和 key
docker compose up -d
# 打开 http://localhost:18080
```

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

**由此产生一个必须知道的限制**：拿不到历史分页，所以「最新 100 条」是随运行时间自然累积的。
财联社电报约 30 条/小时，服务跑满约 3 小时能攒到 100 条以上；刚启动时先显示抓到的 50 条。
页面顶部「已收录 N 条」是真实数字，不是我给它填的假数。

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | **唯一免鉴权**。真读一次库，UP 返回 200 / DOWN 返回 503 |
| GET | `/api/news?limit=100` | 首屏，按发布时间倒序 |
| GET | `/api/news?since=<maxId>&limit=100` | 增量，返回 id 更新的条目（升序） |
| GET | `/api/news/{id}` | 单条 |
| POST | `/api/news/{id}/analyze` | 手动触发单条分析 |
| POST | `/api/analysis/retry-failed` | 把 `failed` 全部打回 `pending` 重跑 |
| GET | `/api/status` | 抓取状态、总数、分析进度与 token 用量、上次错误、`analysisReady` |

`/api/news` 的响应里带 `stats`，页面只发一个请求就能同时更新列表和状态栏。

---

## 访问控制（放公网前必看）

开启了 `app.security.enabled=true` 时，除 `/healthz` 外全部路径要求 HTTP Basic 认证，
浏览器会弹原生登录框，不需要改前端。

```bash
APP_AUTH=true APP_USER=admin APP_PASSWORD='你的密码' java -jar target/cls-news-0.1.0.jar
```

三个设计上故意做的选择：

| 选择 | 原因 |
|---|---|
| 密码为空时**拒绝启动** | 最危险的状态不是「没开鉴权」，而是「以为开了、其实密码是空串」 |
| 白名单用**完全相等**匹配，不用 `startsWith`，并拦掉 `..` `//` `;` | 实测过：用 `startsWith` 时 `/healthz/../api/status` 会被放行，能否挡住全看 Tomcat 的实现，等于把安全交给别人的默认值 |
| 不引入 `spring-boot-starter-security` | 那套自带登录页、CSRF、会话，要关的比要开的多；40 行 Filter 行为完全可预期 |

> Basic 认证的凭据是 Base64，**不是加密**。公网必须套 HTTPS，否则等同明文密码。
> 最省事的替代方案：端口只绑 `127.0.0.1`，用 SSH 隧道访问 —— 不需要域名、备案、证书。

---

## 配置（`src/main/resources/application.yml`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.source` | `cls-api` | 改 `mock` 可断网看界面 |
| `app.interval-ms` | `90000` | 服务端抓取间隔。**不要**设成 30000，见下方合规 |
| `app.display.fulltext` | `true` | 改成 `false` 只返回 60 字摘要（公开口径） |
| `app.analysis.enabled` | `true` | 大模型分析总开关，关掉只采集不分析 |
| `app.analysis.provider` | `deepseek` | `deepseek` 真实调用 / `stub` 空实现 |
| `app.analysis.daily-cap` | `300` | 每日分析调用上限，防烧钱 |
| `app.analysis.api-key` | 空 | 留空则回落到环境变量 / `~/.codex/auth.json` |
| `app.analysis.model` | `deepseek-flash` | 实测可用：`deepseek-flash`、`deepseek-v4-pro` |
| `app.analysis.max-tokens` | `2000` | **别调小**：推理型模型的思考过程也算在内，600 会让 JSON 被截断 |
| `spring.task.scheduling.pool.size` | `4` | **别改回 1**：单线程时一个卡住的模型调用会把采集一起拖死 |
| `app.security.enabled` | `false` | 环境变量 `APP_AUTH`。放公网必须 `true` |
| `app.security.username` / `password` | `admin` / 空 | 环境变量 `APP_USER` / `APP_PASSWORD`。密码空 + 开启鉴权 = 启动失败 |
| `spring.datasource.url` | `./data/news.db` | 环境变量 `DB_PATH`。容器里指向挂载卷 `/app/data/news.db` |

关于频率：你说的是「半分钟爬一次」。我把两个 30 秒拆开了 —— **页面每 30 秒拉增量**
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

### 跑完 84 条的实际分布

| 指标 | 实测值 |
|---|---|
| JSON 非法 | 0 / 84 |
| 中性占比 | 64%（说明没有在硬编方向，低于 20% 才要警惕） |
| 平均置信度 | 0.45（最高 0.72） |
| 关联个股 | 56 条 |
| 累计 token | 约 3.2 万（84 条） |

手动触发单条：

```bash
curl -X POST http://localhost:18080/api/news/1/analyze
curl -X POST http://localhost:18080/api/analysis/retry-failed   # 调大 token 或换 key 后重跑失败项
```

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

## 合规（当前口径：只自己看）

- `app.display.fulltext = true`，全文只存在本机 SQLite，不对外转载；
- 不把站点暴露到公网、不加登录分享给别人 —— 这是当前口径的前提；
- 服务端 90 秒一轮，单轮 50 条，属于低频访问；UA / Referer 如实声明；
- 若要改成公开访问，先把 `fulltext` 改成 `false`，并在页面上保留版权声明与原文链接。

---

## 目录

```
src/main/java/com/herc/clsnews/
├── config/AppProps.java        全部可调参数
├── source/NewsSource.java      来源适配层（换接口只改这里）
│   ├── ClsApiSource.java       真实接口 + 签名
│   └── MockSource.java         假数据
├── store/NewsStore.java        六条 SQL，直接 JDBC
├── job/IngestJob.java          抓取调度
├── job/AnalysisWorker.java     分析调度（enabled=false 时不创建）
├── ai/Analyzer.java            接模型的唯一入口
├── ai/DeepSeekAnalyzer.java    DeepSeek 实现
├── ai/PromptTemplate.java      读 md 提示词 + 占位符渲染
├── ai/StubAnalyzer.java        空实现（provider=stub 时用）
└── web/NewsController.java     6 个接口
src/main/resources/
├── static/index.html           整页，无构建、无 CDN
├── prompts/analyze-news.md     提示词 + 输出结构（改这里就够了）
└── schema.sql                  建表
```

---

## 下一步（按优先级）

1. 跑满一天，确认 `已收录` 稳定增长、`lastError` 一直为空 —— 先证明采集是可靠的；
2. 抽查 20 条分析结论是否站得住，重点看有没有「编造原文没提的信息」；
3. 攒 30 条人工标注做回归，再决定要不要调提示词；
4. 只有到这一步，才考虑部署到云上。
