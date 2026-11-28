-- 幂等建表：每次启动都会执行，已存在则跳过
-- 注意 id 故意不用 AUTOINCREMENT：本表靠 item_id 唯一约束去重，
-- 每次抓取 50 条里绝大多数是重复的，AUTOINCREMENT 会在冲突时也消耗序号，
-- 导致 id 每小时虚增两千。普通 INTEGER PRIMARY KEY 会复用「未落库的序号」，
-- id 就能和发布时间保持紧凑的单调关系。
CREATE TABLE IF NOT EXISTS news (
    id              INTEGER PRIMARY KEY,
    item_id         TEXT    NOT NULL UNIQUE,          -- 来源侧唯一 id，去重靠它
    title           TEXT    NOT NULL DEFAULT '',
    content         TEXT    NOT NULL DEFAULT '',
    url             TEXT,
    publish_time    INTEGER NOT NULL,                 -- epoch 秒
    level           TEXT,                             -- A / B / C，A 最重要
    stocks_json     TEXT,                             -- 来源自带的关联股票
    created_at      INTEGER NOT NULL,
    analysis_status TEXT    NOT NULL DEFAULT 'pending',
                                                      -- pending | done | failed | skipped
    analysis_json   TEXT,                             -- 大模型输出，见 prompts/analyze-news.md
    analyzed_at     INTEGER,
    model           TEXT
);

CREATE INDEX IF NOT EXISTS idx_news_publish ON news (publish_time DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_news_status  ON news (analysis_status);

-- 每次真正调用大模型记一行。
-- 为什么不直接数 news.analyzed_at：同一行可以被「重新分析」多次，按行计数永远到不了上限，
-- 而钱是一次一次花的。日配额必须按调用次数算，否则这个上限只是给人看的。
CREATE TABLE IF NOT EXISTS analysis_call (
    id      INTEGER PRIMARY KEY,
    ts      INTEGER NOT NULL,                         -- epoch 秒
    news_id INTEGER,
    actor   TEXT                                      -- rw | guest | ro | auto；NULL = 加列前的老数据
);

CREATE INDEX IF NOT EXISTS idx_call_ts ON analysis_call (ts);

-- 访客统计：按「IP + 日期」去重。
-- 主键就是去重规则本身，所以「今日访客」= COUNT(*) WHERE day=?，
-- 「总访客」= COUNT(DISTINCT ip)，不需要额外去重逻辑。
-- hits 记的是当天这个 IP 打开页面的次数（PV），只存不展示，留着以后看趋势。
-- 旧口径：按「IP + 自然日」去重。已退役，不再写入 —— 表留在库里只是为了
-- 历史数据还能查、还能和页面上新的数字对得上账。新代码请用下面的 visitor。
CREATE TABLE IF NOT EXISTS visit (
    ip       TEXT    NOT NULL,
    day      TEXT    NOT NULL,                        -- yyyy-MM-dd，服务器本地时区
    first_ts INTEGER NOT NULL,
    last_ts  INTEGER NOT NULL,
    hits     INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (ip, day)
);

CREATE INDEX IF NOT EXISTS idx_visit_day ON visit (day);

-- 访客聚合（当前口径）：按「设备标识 + 自然日」去重。
--
-- k 是去重键，两种形态：
--   'v:' + vid  —— 浏览器/小程序本地生成并持久化的随机串，同一个人的多天访问共用一个 k
--   'ip:' + ip  —— 没拿到 vid 时（无痕模式、清过缓存、老客户端）退回按 IP
--
-- 为什么不直接给 visit 加一列 vid：visit 的主键 (ip, day) 就是去重规则，
-- 同一出口 IP 下的两个人会被并进同一行，vid 只能存下最后一个 —— 加了列也算不准。
-- SQLite 又不支持改主键，所以另起一张表，粒度从一开始就定对。
--
-- vid 的局限写在明处：它能被清空（无痕模式）、能被伪造（客户端随便生成）。
-- 所以它只是「比 IP 更细的去重维度」，不是身份，不能当账号用。
CREATE TABLE IF NOT EXISTS visitor (
    k        TEXT    NOT NULL,
    day      TEXT    NOT NULL,
    ip       TEXT    NOT NULL,                        -- 留档排查用，不参与去重
    vid      TEXT,                                    -- 空 = 这次没拿到 vid，k 用的是 ip
    first_ts INTEGER NOT NULL,
    last_ts  INTEGER NOT NULL,
    hits     INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (k, day)
);

CREATE INDEX IF NOT EXISTS idx_visitor_day ON visitor (day);

-- 访问明细：每次「登录成功后打开页面」一行。
--
-- 和 visit 表的区别：visit 是聚合口径（IP+日 去重），这张表是流水。
-- 分开的理由是 visit 的主键就是去重规则，往里面加 vid 会改变它的语义，
-- 已经存在的历史数据也会失去可比性。所以新增明细表，visit 保持原样。
--
-- vid 是前端 localStorage 里的随机串。有了它，同一个公司出口 IP 下的
-- 十个人才能各自算一个访客（只按 IP 去重时他们是一个）。但 vid 可以被
-- 清空（无痕模式）、也可以被伪造，所以它只是"辅助去重维度"，不是身份。
CREATE TABLE IF NOT EXISTS access_log (
    id      INTEGER PRIMARY KEY,
    ts      INTEGER NOT NULL,                         -- epoch 秒
    day     TEXT    NOT NULL,                         -- yyyy-MM-dd，与 visit 同口径
    ip      TEXT    NOT NULL,
    vid     TEXT,                                     -- 可空：无痕模式 / 老客户端
    ua      TEXT,                                     -- 原始 UA，截断 512
    device  TEXT,                                     -- wechat|wecom|qq|mobile|tablet|desktop|bot|unknown
    os      TEXT,                                     -- ios|android|windows|macos|linux|bot|unknown
    browser TEXT,                                     -- chrome|safari|edge|firefox|wechat|bot|unknown
    path    TEXT,
    referer TEXT
);

CREATE INDEX IF NOT EXISTS idx_access_ts ON access_log (ts DESC);
CREATE INDEX IF NOT EXISTS idx_access_day ON access_log (day, vid);

-- 登录令牌（access / refresh）。以前放在内存里，进程一重启所有人掉线：
-- 采集任务改了要重启、改配置要重启、容器滚动更新也会重启 —— 用户那边的表现是
-- 「刷着刷着突然要重新登录」，而他手里明明什么都没做错。
--
-- 存的是 **SHA-256 令牌哈希**，不是令牌原文。库文件会被备份、会被复制到别处排查问题，
-- 原文落库等于把可用的登录凭据一起备份出去；哈希不可反推（令牌本身就是 32 字节随机数）。
--
-- session 是「同一次登录」的标识：access 与 refresh 配对签出时共用，刷新时保持不变，
-- 登出时按它一次删掉这一串。不能按 username 删 —— 那样同一个人另一台设备会被顺带踢掉。
--
-- expires_at 用**毫秒**：前端拿到的 accessExpiresAt 也是毫秒，两边不用换算。
CREATE TABLE IF NOT EXISTS auth_token (
    token_hash TEXT    PRIMARY KEY,
    kind       TEXT    NOT NULL,                      -- access | refresh
    username   TEXT    NOT NULL,
    role       TEXT    NOT NULL,
    session    TEXT    NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_token_session ON auth_token (session);
CREATE INDEX IF NOT EXISTS idx_token_expires ON auth_token (expires_at);

-- ============ Agent 层（设计见 docs/agent-plan.md §5） ============
-- 一次"用户点了一下按钮"就是一行。这里的核心不是存结果，而是**存钱花在哪了**：
-- 谁触发的、基于哪些数据、用了哪个模板、策略是 full 还是 delta、花了多少 token。
-- 没有这张表，Agent 出问题只能靠猜 —— 而 Agent 的失败形态从来不是报错，
-- 是"悄悄换了个检索范围，然后给了一个看着挺像的答案"。

CREATE TABLE IF NOT EXISTS agent_run (
    id            TEXT PRIMARY KEY,       -- r_20261127T162012_a3f9：可排序、可读、可贴群里
    kind          TEXT NOT NULL,          -- template | research | followup
    template_id   TEXT,                   -- hot-today / nvda-bullish / a-share-drop
    params_json   TEXT,                   -- {window:'1d', keywords:[], question:'...'}
    thread_id     TEXT,                   -- Step 3 会话记忆预留（现在建好，省一次 SQLite 重建表）
    parent_run_id TEXT,                   -- 追问链
    trigger       TEXT NOT NULL,          -- 只允许 'user'：见 AgentService 的首行校验
    trigger_by    TEXT NOT NULL,          -- 具体账号，审计用
    window_key    TEXT NOT NULL,          -- '2026-11-27'，跨天判定（跨天必 full，不能吃昨天缓存）
    strategy      TEXT NOT NULL,          -- full | delta
    base_run_id   TEXT,                   -- delta 所基于的那次 full；full 时为 NULL
    watermark     INTEGER NOT NULL,       -- 生成时候选集的 max(news.id)，缓存命中就看它
    status        TEXT NOT NULL,          -- running | succeeded | failed | rejected
    answer_json   TEXT,                   -- {title, bullets[], insufficient}
    citations_json TEXT,                  -- 校验通过的 id（与工具真实返回求交后的结果）
    steps_json    TEXT,                   -- 这次生成做了哪些步骤（确定性数字，见 Steps）；老行为 NULL
    candidates    INTEGER,                -- 送进模型的条数
    tokens_in     INTEGER NOT NULL DEFAULT 0,
    tokens_out    INTEGER NOT NULL DEFAULT 0,
    model         TEXT,
    latency_ms    INTEGER,
    error         TEXT,
    created_at    INTEGER NOT NULL,
    finished_at   INTEGER
);

CREATE INDEX IF NOT EXISTS idx_run_created ON agent_run (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_run_brief   ON agent_run (kind, template_id, watermark);
-- 找"今天最近一次 full"是 delta 判定的第一步，单独建索引
CREATE INDEX IF NOT EXISTS idx_run_base    ON agent_run (template_id, window_key, strategy, status, created_at DESC);

-- 同一缓存键在"正在跑"和"已成功"两种状态下都只允许一行。
-- 这不是性能索引，是**防重复扣费的保险**：两个请求同时通过应用层检查时，
-- 第二个在 INSERT 那一刻就被数据库拒绝，一分钱不花 —— 只挡 succeeded 是不够的，
-- 那样两个请求都能插 running、都能调模型、都花钱，只有后写的撞索引作废（钱已经花出去了）。
--
-- 键从 (template_id, watermark) 扩到四列，每加一列都对应一个实测出来的漏洞：
--   window_key  —— 跨天但采集没进新数据时 watermark 与昨天相同，旧索引会让今天的
--                  full 写不进去（UPDATE 抛唯一冲突 → 返回 500）。缓存本来就不该跨天。
--   params_json —— 同一模板带不同关键词是两次不同的检索，不该共用一条缓存。
--                  不带这一列时，一次带自定义关键词的 run 会把该水位线的缓存占住，
--                  后续用模板默认关键词的请求会拿到别人的答案。
--
-- 迁移顺序：先把历史上"同键多条 running"收敛成一条，再建索引（否则建索引会失败），
-- 最后删旧索引。CREATE INDEX IF NOT EXISTS 对已存在的同名索引一个字都不会改，
-- 所以改名而不是原地改 —— 否则老库永远升不上来。
UPDATE agent_run
   SET status = 'failed',
       error = '迁移：同缓存键存在多条 running，保留最新一条',
       finished_at = strftime('%s','now')
 WHERE status = 'running' AND kind = 'template'
   AND EXISTS (
       SELECT 1 FROM agent_run b
        WHERE b.status = 'running' AND b.kind = 'template'
          AND b.template_id = agent_run.template_id
          AND b.window_key  = agent_run.window_key
          AND b.watermark   = agent_run.watermark
          AND IFNULL(b.params_json,'') = IFNULL(agent_run.params_json,'')
          AND (b.created_at > agent_run.created_at
               OR (b.created_at = agent_run.created_at AND b.id > agent_run.id)));

CREATE UNIQUE INDEX IF NOT EXISTS uq_brief_active
    ON agent_run (template_id, window_key, watermark, params_json)
    WHERE status IN ('running', 'succeeded') AND kind = 'template';

DROP INDEX IF EXISTS uq_brief_key;
DROP INDEX IF EXISTS uq_brief_watermark;

-- 一次 run 内部的每一步（Step 1 只有 llm 和 guard 两种，Step 2 才多出 tool）。
-- raw_output 截断存 ≤4KB：完整版动辄几十 KB，磁盘只有 40G；
-- 更要紧的是存完整版会和"模型实际看到的截断版"不一致，排查时反而误导。
CREATE TABLE IF NOT EXISTS agent_step (
    run_id       TEXT NOT NULL,
    seq          INTEGER NOT NULL,
    kind         TEXT NOT NULL,           -- llm | guard | error | tool（Step 2）
    prompt_chars INTEGER,
    raw_output   TEXT,
    tokens_in    INTEGER,
    tokens_out   INTEGER,
    latency_ms   INTEGER,
    error        TEXT,
    ts           INTEGER NOT NULL,
    PRIMARY KEY (run_id, seq)
);
