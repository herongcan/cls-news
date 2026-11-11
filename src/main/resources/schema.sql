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
    news_id INTEGER
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
