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
    actor   TEXT                                      -- rw | demo | ro；老数据为 NULL，按全站口径算
);

CREATE INDEX IF NOT EXISTS idx_call_ts ON analysis_call (ts);

-- 访客统计：按「IP + 日期」去重。
-- 主键就是去重规则本身，所以「今日访客」= COUNT(*) WHERE day=?，
-- 「总访客」= COUNT(DISTINCT ip)，不需要额外去重逻辑。
-- hits 记的是当天这个 IP 打开页面的次数（PV），只存不展示，留着以后看趋势。
CREATE TABLE IF NOT EXISTS visit (
    ip       TEXT    NOT NULL,
    day      TEXT    NOT NULL,                        -- yyyy-MM-dd，服务器本地时区
    first_ts INTEGER NOT NULL,
    last_ts  INTEGER NOT NULL,
    hits     INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (ip, day)
);

CREATE INDEX IF NOT EXISTS idx_visit_day ON visit (day);

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
