#!/usr/bin/env node
/**
 * cls-news 的 MCP server（stdio，零依赖）。
 *
 * 为什么是「独立小进程 + 调 HTTP」而不是「让 Spring Boot 自己当 MCP server」：
 * 应用侧的 /api/** 已经是稳定契约，加一层 MCP 端点要把 JSON-RPC、会话、SSE 塞进
 * 一个 JRE 容器里，为了一个「只读查询」的用途不值得。这个文件可以随时删掉，
 * 应用完全不知道它存在。
 *
 * 三条硬规矩：
 *  1. **stdout 只能出协议帧**。console.log 一次，整个通道就废了，Codex 侧表现为
 *     「server 启动了但工具全不可用」，且没有任何报错。所有日志必须走 stderr。
 *  2. **只发 GET，且路径必须在白名单里**。这个 server 的权限等于它拿到的凭证，
 *     把 POST /api/news/{id}/analyze 暴露出去，模型一积极就能把 daily-cap 烧光。
 *  3. **输出要截断**。工具结果会原样进模型上下文，一条正文 2000 字、一次 30 条，
 *     就是 6 万字，直接挤掉真正有用的对话。
 *
 * 为什么用 node:http 而不是 fetch：
 * Codex 启动 MCP 子进程时用的 `node` 来自 **它自己继承的 PATH**，和你终端里的
 * 不是同一个。实测这台机器上 `which node` 是 v22（WorkBuddy 自带），而 Codex
 * 拿到的是 /usr/local/bin/node = **v16.20.2**，没有 fetch，于是启动即失败：
 *   MCP server stderr: 需要 Node >= 18（当前 16.20.2），缺内置 fetch
 * 所以这里刻意只用 Node 16 就有的 API —— 只要 `node` 能解析到 16 以上就能跑，
 * 不依赖任何特定版本的绝对路径（那种路径带版本号，升级一次就失效）。
 *
 * 环境变量：
 *  - CLS_BASE   应用地址，默认 http://127.0.0.1:8080
 *  - CLS_AUTH   Basic 凭证，格式 user:pass（推荐）
 *  - CLS_USER / CLS_PASS  分开给的写法，CLS_AUTH 优先
 *  - CLS_TIMEOUT_MS  单次请求超时，默认 10000
 */

import http from 'node:http';
import https from 'node:https';


const BASE = (process.env.CLS_BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const AUTH = process.env.CLS_AUTH
  || (process.env.CLS_USER ? `${process.env.CLS_USER}:${process.env.CLS_PASS || ''}` : '');
const TIMEOUT_MS = Number(process.env.CLS_TIMEOUT_MS || 10000);

// 单次工具返回的字符上限：超了从尾部截断并注明，别让模型以为读到的是全部
const MAX_CHARS = 8000;
// 单条正文的展示上限
const TEXT_CHARS = 600;

function log(...args) {
  // 必须 stderr。见文件头第 1 条。
  console.error('[cls-mcp]', ...args);
}

// ---------- 只读白名单 ----------

const ALLOWED = [
  /^\/api\/news$/,
  /^\/api\/news\/search$/,
  /^\/api\/news\/\d+$/,
  /^\/api\/status$/,
];

function assertAllowed(pathname) {
  if (!ALLOWED.some((re) => re.test(pathname))) {
    // 走到这里说明是我的代码写错了，不是用户输入问题，所以直接抛
    throw new Error(`拒绝访问非白名单路径: ${pathname}`);
  }
}

/**
 * GET 一次。不用 fetch —— 见文件头「为什么用 node:http」。
 * 响应体很小（几十 KB），所以一次性收集 Buffer 再解码，不做流式处理。
 */
function httpGet(url, headers) {
  return new Promise((resolve, reject) => {
    let u;
    try {
      u = new URL(url);
    } catch {
      return reject(new Error(`地址不合法: ${url}`));
    }
    const mod = u.protocol === 'https:' ? https : http;
    const req = mod.request(u, { method: 'GET', headers, timeout: TIMEOUT_MS }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('error', reject);
      res.on('end', () => {
        resolve({
          status: res.statusCode || 0,
          statusText: res.statusMessage || '',
          body: Buffer.concat(chunks).toString('utf8'),
        });
      });
    });
    // timeout 只覆盖 socket 空闲；GET 的响应不会长时间无数据，够用
    req.on('timeout', () => req.destroy(new Error(`超时 ${TIMEOUT_MS}ms`)));
    req.on('error', reject);
    req.end();
  });
}

/**
 * GET 一次并解析 JSON。GET 是幂等的，所以网络错和 5xx 重试一次是安全的；
 * 401/404 是确定性结果，重试只是白等。
 */
async function api(pathname, query) {
  assertAllowed(pathname);
  const qs = query && Object.keys(query).length ? '?' + new URLSearchParams(query).toString() : '';
  const url = BASE + pathname + qs;
  const headers = { Accept: 'application/json', 'Accept-Encoding': 'identity' };
  if (AUTH) headers.Authorization = 'Basic ' + Buffer.from(AUTH).toString('base64');

  let lastErr;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await httpGet(url, headers);
      if (res.status === 401) throw new Error('401：凭证无效（检查 CLS_AUTH 是否为 user:pass）');
      if (res.status === 404) throw new Error('404：没有这条记录');
      if (res.status < 200 || res.status >= 300) {
        if (res.status >= 500 && attempt === 0) {
          lastErr = new Error(`HTTP ${res.status}`);
          continue;
        }
        throw new Error(`HTTP ${res.status} ${res.statusText}`);
      }
      if (!AUTH) log('未配置 CLS_AUTH，直连无鉴权实例（仅限本地开发）');
      try {
        return JSON.parse(res.body);
      } catch {
        throw new Error(`响应不是合法 JSON（前 120 字符）: ${res.body.slice(0, 120)}`);
      }
    } catch (e) {
      // 401/404 已被转换成人话，不再重试
      if (/^(401|404)：/.test(e.message)) throw e;
      lastErr = e;
      if (attempt === 1) break;
    }
  }
  throw new Error(`请求失败 ${pathname}：${lastErr?.message || '未知错误'}`);
}

// ---------- 渲染 ----------

function ts(unix) {
  if (!unix) return '-';
  return new Date(unix * 1000).toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai', hour12: false,
  });
}

function stocksOf(n) {
  const list = (n.stocks || []).map((s) => (typeof s === 'string' ? s : s.name || s.code)).filter(Boolean);
  return list.length ? list.join('、') : '-';
}

function fmtNews(n, { withAnalysis = false } = {}) {
  const lines = [
    `#${n.id}  ${ts(n.time)}  [${n.level || '-'}] ${n.title || '(无标题)'}`,
    (n.text || '').slice(0, TEXT_CHARS) + ((n.text || '').length > TEXT_CHARS ? '…' : ''),
    `个股: ${stocksOf(n)}   分析: ${n.analysisStatus || '-'}   ${n.url || ''}`,
  ];
  const a = n.analysis;
  if (withAnalysis && a) {
    lines.push(
      `判断: ${a.sentiment || '-'}  置信度: ${a.confidence ?? '-'}`,
      `理由: ${a.reason || a.summary || '-'}`,
    );
    if (a.impact) lines.push(`影响: ${a.impact}`);
  }
  return lines.filter(Boolean).join('\n');
}

/**
 * 拼多条，按**整条**裁剪而不是按字符切。
 * 按字符切会把最后一条切成半句，模型读到的是残句却以为是全部内容 —— 这比少给几条危险。
 */
function joinNews(items) {
  const out = [];
  let len = 0;
  for (const n of items) {
    const s = fmtNews(n);
    if (out.length && len + s.length > MAX_CHARS) {
      out.push(`[已省略 ${items.length - out.length} 条：单次返回有字符上限。缩小 limit，或用 before 游标翻页]`);
      break;
    }
    out.push(s);
    len += s.length + 2;
  }
  return out.join('\n\n');
}

function clip(text) {
  if (text.length <= MAX_CHARS) return text;
  return text.slice(0, MAX_CHARS) + `\n\n[已截断，共 ${text.length} 字符]`;
}

// ---------- 工具定义 ----------

const TOOLS = [
  {
    name: 'latest_news',
    description: '取财联社最新电报（按发布时间倒序）。用于回答「最近有什么消息 / 今天有什么大事」。',
    inputSchema: {
      type: 'object',
      properties: {
        limit: { type: 'integer', minimum: 1, maximum: 30, description: '条数，默认 8，上限 30' },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: 'search_news',
    description: '按关键词搜索历史电报（匹配标题和正文）。查某个公司、板块、事件的历次提及。',
    inputSchema: {
      type: 'object',
      properties: {
        q: { type: 'string', minLength: 1, maxLength: 50, description: '关键词，如「半导体」「降准」' },
        limit: { type: 'integer', minimum: 1, maximum: 30, description: '条数，默认 8' },
        before: { type: 'integer', description: '游标：上一页返回的 nextBefore，用于翻下一页' },
      },
      required: ['q'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: 'get_news',
    description: '按 id 取单条电报的完整正文与已有分析结论。',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'integer', minimum: 1, description: '新闻 id，如 1312' } },
      required: ['id'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: 'news_stats',
    description: '采集与分析状态：总条数、待分析积压、已分析数、失败数、最近一次抓取是否正常。回答「数据新不新 / 积压多少」时用。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
];

// ---------- 工具实现 ----------

function clampInt(v, def, min, max) {
  const n = Number.parseInt(v, 10);
  if (!Number.isFinite(n)) return def;
  return Math.max(min, Math.min(n, max));
}

async function callTool(name, args = {}) {
  switch (name) {
    case 'latest_news': {
      const limit = clampInt(args.limit, 8, 1, 30);
      const d = await api('/api/news', { limit });
      const items = d.items || [];
      if (!items.length) return '库里还没有电报。';
      return joinNews(items);
    }

    case 'search_news': {
      const q = String(args.q || '').trim();
      if (!q) throw new Error('q 不能为空');
      const limit = clampInt(args.limit, 8, 1, 30);
      const query = { q: q.slice(0, 50), limit };
      if (args.before) query.before = clampInt(args.before, 0, 1, Number.MAX_SAFE_INTEGER);
      const d = await api('/api/news/search', query);
      const items = d.items || [];
      if (!items.length) return `没有匹配「${q}」的电报。`;
      const head = `匹配「${q}」共 ${d.total ?? '?'} 条，本页 ${items.length} 条`
        + (d.hasMore ? `，还有更多（下一次传 before=${items[items.length - 1].id}）` : '，已到底');
      return head + '\n\n' + joinNews(items);
    }

    case 'get_news': {
      const id = clampInt(args.id, 0, 1, Number.MAX_SAFE_INTEGER);
      if (!id) throw new Error('id 必须是正整数');
      const n = await api(`/api/news/${id}`);
      return clip(fmtNews(n, { withAnalysis: true }));
    }

    case 'news_stats': {
      const s = await api('/api/status');
      return [
        `新闻总数: ${s.total}   待分析: ${s.pending}   已分析: ${s.analyzed}   失败: ${s.analysisFailed}`,
        `采集: 每 ${Math.round((s.intervalMs || 0) / 1000)}s 一轮，上次运行 ${ts(s.lastRunAt)}，`
          + `抓取 ${s.lastFetched ?? '-'} 条 / 新增 ${s.lastInserted ?? '-'} 条`,
        `分析: 模型 ${s.analysisProvider || '-'}，就绪 ${s.analysisReady}，`
          + `自动 ${s.analysisAuto}，今日已用 ${s.analyzedToday}/${s.analysisCap}，用量 ${s.analysisUsage || '-'}`,
        s.lastError ? `最近错误: ${s.lastError}` : '最近错误: 无',
      ].join('\n');
    }

    default:
      throw new Error(`未知工具: ${name}`);
  }
}

// ---------- JSON-RPC ----------

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

function reply(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function fail(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

async function handle(msg) {
  const { id, method, params } = msg;
  const isNotification = id === undefined || id === null;

  // 通知（无 id）一律不回复，包括 notifications/initialized、cancelled
  if (isNotification) return;

  switch (method) {
    case 'initialize':
      return reply(id, {
        // 回显客户端版本：MCP 的连接建立就靠这一步对齐，硬写一个自己支持的版本
        // 会让某些客户端直接断连
        protocolVersion: params?.protocolVersion || '2025-06-18',
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: 'cls-news', version: '1.0.0' },
        instructions: '财联社电报查询。只读，不会修改任何数据。',
      });

    case 'ping':
      return reply(id, {});

    case 'tools/list':
      return reply(id, { tools: TOOLS });

    case 'tools/call': {
      const name = params?.name;
      try {
        const text = await callTool(name, params?.arguments || {});
        // 业务失败用 isError，不要用 JSON-RPC error：后者在 Codex 里表现为
        // 「工具崩了」，模型拿不到可读的原因，也就没法自己纠正参数重试
        return reply(id, { content: [{ type: 'text', text }], isError: false });
      } catch (e) {
        log('tool call failed:', name, e.message);
        return reply(id, { content: [{ type: 'text', text: `调用失败：${e.message}` }], isError: true });
      }
    }

    default:
      return fail(id, -32601, `Method not found: ${method}`);
  }
}

// ---------- 主循环 ----------

function main() {
  const major = Number(process.versions.node.split('.')[0]);
  if (major < 16) {
    log(`需要 Node >= 16（当前 ${process.versions.node}）`);
    process.exit(1);
  }
  // 打出版本和可执行文件路径：Codex 用的 node 常和你终端里的不是同一个，
  // 出问题时光看版本号就能判断是不是解析到了老版本
  log(`启动 [node ${process.versions.node} @ ${process.execPath}]，目标 ${BASE}${AUTH ? '，已带凭证' : '，无凭证'}`);

  // stdin 关闭时不能立刻 exit：此刻可能还有工具调用在等 HTTP 回来，
  // 直接退会把结果丢掉（客户端表现为「工具无响应」，且日志里什么都没有）。
  let pending = 0;
  let closing = false;
  const maybeExit = () => {
    if (closing && pending === 0) process.exit(0);
  };

  let buf = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buf += chunk;
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        // 坏帧不能回 error（没有 id 无从对应），只能记日志继续
        log('收到非法 JSON 帧:', line.slice(0, 120));
        continue;
      }
      pending++;
      handle(msg)
        .catch((e) => {
          log('handle 异常:', e.message);
          if (msg?.id !== undefined) fail(msg.id, -32603, e.message);
        })
        .finally(() => {
          pending--;
          maybeExit();
        });
    }
  });

  process.stdin.on('end', () => {
    closing = true;
    // 兜底：HTTP 卡住时最多等 15 秒，不要变成僵尸进程
    setTimeout(() => process.exit(0), 15000).unref();
    maybeExit();
  });
}


main();
