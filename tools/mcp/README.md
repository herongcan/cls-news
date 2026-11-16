# cls-mcp —— 把新闻库接给 Codex / Claude 用

一个 **stdio MCP server**，让 AI 客户端（Codex CLI、Claude Desktop、Cursor…）能直接查你的财联社电报。

零依赖、单文件、不改动应用一行代码。原理就一句话：**它是个薄壳，内部调你已经在跑的 `/api/**`**。

```
Codex ──stdio(管道)──> cls-mcp.mjs ──HTTP GET + Basic──> cls-news(8.136.19.57:8080)
```

## 为什么是独立进程，而不是让 Spring Boot 自己当 MCP server

| 方案                             | 代价                                                         |
| ------------------------------ | ---------------------------------------------------------- |
| 应用内加 MCP 端点（Spring AI Starter） | 引一堆依赖进 512m 容器、要重启发版、JSON-RPC 会话和 SSE 塞进 JRE 里，为了一个只读查询不值得 |
| **独立小进程调 HTTP（当前）**            | 100 行；应用完全不知道它存在，删掉也不影响任何东西                                |

`/api/**` 已经是稳定契约，再加一层协议不必动它。

## 注册到 Codex

```bash
# 最省事：一条命令
codex mcp add clsnews \
  --env CLS_BASE=http://8.136.19.57:8080 \
  --env CLS_AUTH=admin:admin \
  -- node /绝对路径/tools/mcp/cls-mcp.mjs

codex mcp list        # 确认出现 clsnews，Status=enabled
```

或者直接写 `~/.codex/config.toml`：

```toml
[mcp_servers.clsnews]
command = "node"
args = ["/绝对路径/tools/mcp/cls-mcp.mjs"]
env = { CLS_BASE = "http://8.136.19.57:8080", CLS_AUTH = "admin:admin" }
```

> **凭证别写进仓库。** `CLS_AUTH` 是 `user:pass` 明文，写进 `~/.codex/config.toml` 就只留在本机。  
> 换个方式也可以：`--bearer-token-env-var` 只对 HTTP 传输有效，stdio 走 `--env`。

## 四个工具

| 工具            | 用途                    | 参数                                   |
| ------------- | --------------------- | ------------------------------------ |
| `latest_news` | 最近的电报，回答「今天有什么大事」     | `limit` 1–30，默认 8                    |
| `search_news` | 按关键词查历史，看某个公司/板块被提过几次 | `q`（必填，≤50 字）、`limit`、`before`（翻页游标） |
| `get_news`    | 单条完整正文 + 已有分析结论       | `id`                                 |
| `news_stats`  | 总条数、待分析积压、上次抓取是否正常    | 无                                    |

`news_stats` 值得单独说：它顺手能回答「数据新不新」——`pending` 一千多条、`lastRunAt` 是几分钟前，就说明采集在跑。

## 环境变量

| 变量                      | 默认                      | 说明                             |
| ----------------------- | ----------------------- | ------------------------------ |
| `CLS_BASE`              | `http://127.0.0.1:8080` | 应用地址。默认是本地开发                   |
| `CLS_AUTH`              | 空                       | Basic 凭证 `user:pass`。空 = 不带认证头 |
| `CLS_USER` / `CLS_PASS` | 空                       | 分开给的写法，`CLS_AUTH` 优先           |
| `CLS_TIMEOUT_MS`        | `10000`                 | 单次请求超时                         |

要求 **Node >= 16**。刻意不用 `fetch`（Node 18 才有），改用 `node:http` —— 原因见下面第 ③ 个坑。

## 三条设计约束（踩过才知道）

**1. 只发 GET，路径必须在白名单里**

```js
const ALLOWED = [/^\/api\/news$/, /^\/api\/news\/search$/, /^\/api\/news\/\d+$/, /^\/api\/status$/];
```

这个进程的权限等于它拿到的凭证。**绝对不能把 `POST /api/news/{id}/analyze` 暴露出去**——那会消耗 `daily-cap=300` 的分析配额，模型一积极就能给你烧光。同理，`/api/visits/logs` 也别加，那会把同事的 IP 送给模型。

**2. stdout 只能出协议帧**

MCP over stdio 是「每行一个 JSON-RPC」。任何一次 `console.log` 都会让客户端解析失败，表现为「server 启动了但工具全不可用」，而且**没有任何报错**。所有日志必须走 `console.error`。

**3. 输出按整条裁剪，不按字符切**

工具结果是原样进模型上下文的。一条正文 600 字、一次 30 条就是 1.8 万字，会挤掉真正有用的对话。超过 8000 字符时**丢弃末尾的整条**并注明「已省略 N 条」，而不是把最后一条切成半句——切半句模型会以为是完整内容。

## 三个容易卡住的点

**① Codex 里的 MCP 工具调用要过审批策略**

```
mcp: clsnews/latest_news started
mcp: clsnews/latest_news (failed)
MCP tool call requires approval, but approval policy is never
```

`codex exec`（非交互）默认 `approval: never`，会直接拒绝。交互式会话里会弹确认；要在非交互里跑通：

```bash
codex exec --dangerously-bypass-approvals-and-sandbox ...
```

`codex exec` 没有 `--full-auto` 这个参数（试过，不存在）。

**② stdin 关闭时不能立刻退出**

Codex 结束会话会关掉管道。如果此时还有工具调用在等 HTTP，直接 `process.exit` 会把结果吞掉。现在按 pending 计数延后退出，另有 15 秒兜底，避免变成僵尸进程。

**③ Codex 用的 `node` 不是你终端里的那个**（这条最容易误判成「服务端没部署好」）

实际遇到过：`/mcp` 里显示 `clsnews: failed (0 tools)`，日志只有一行

```
MCP server stderr (node): [cls-mcp] 需要 Node >= 18（当前 16.20.2），缺内置 fetch
```

`which node` 明明是 v22，但 Codex 启动子进程用的是**它自己继承的 PATH**，在这台机器上解析到了 `/usr/local/bin/node` = v16.20.2。同一台机器两个 node，版本差 6 个大版本。

所以：

- 脚本改成只用 Node 16 就有的 API，**不再依赖特定 node 路径**；
- 启动日志会打出版本和可执行文件全路径，以后再出问题一眼能看出来：

```
[cls-mcp] 启动 [node 16.20.2 @ /usr/local/bin/node]，目标 http://...
```

查 Codex 侧的失败原因，不要去猜，直接读它自己的日志（里面会转储子进程的 stderr）：

```bash
sqlite3 ~/.codex/logs_2.sqlite \
  "select datetime(ts,'unixepoch','localtime'), substr(feedback_log_body,1,200)
     from logs where feedback_log_body like '%clsnews%' order by ts desc limit 5;"
```

## 自测

不用装 Codex 也能验：

```bash
printf '%s\n%s\n' \
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}' \
'{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"latest_news","arguments":{"limit":3}}}' \
| CLS_BASE=http://8.136.19.57:8080 CLS_AUTH=admin:admin node tools/mcp/cls-mcp.mjs
```

正常会输出两行 JSON，第二行的 `result.content[0].text` 就是新闻正文。

## 排查

| 现象                            | 原因                                                                            |
| ----------------------------- | ----------------------------------------------------------------------------- |
| `/mcp` 里显示 `failed (0 tools)` | 子进程没起来或握手失败。**先看 Codex 日志里的 `MCP server stderr`**，九成是 node 版本或路径问题（见 ③），不是服务端 |
| 工具列表为空                        | 客户端没拿到 `tools/list` 响应；先跑上面的自测，看是不是 stdout 被日志污染                              |
| `401：凭证无效`                    | `CLS_AUTH` 格式不对，必须是 `user:pass`（冒号不能少）                                        |
| 所有调用都 `isError`               | `CLS_BASE` 不通。注意这台服务器**境外拉 docker 镜像会超时**，但 API 本身正常                          |
| 调用成功但内容为空                     | `latest_news` 返回「库里还没有电报」= 应用侧真的没数据，跟 MCP 无关                                  |
